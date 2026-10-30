package com.flashlink.app.transfer

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

object Protocol {
    const val VERSION = 2
    const val PORT = 45871
    const val MAX_CONTROL = 65536
    const val MAX_DATA = 1048576
    const val HELLO = 1; const val OFFER = 2; const val ACCEPT = 3; const val REJECT = 4
    const val FILE = 5; const val DATA = 6; const val HASH = 7; const val FILE_OK = 8
    const val DONE = 9; const val COMPLETE = 10; const val ERROR = 11; const val CANCEL = 12
    const val PAUSE = 13; const val PAUSED = 14; const val CHECKPOINT = 15; const val CHECKPOINT_OK = 16
    const val RESUME_REQUEST = 17; const val RESUME_ACCEPT = 18; const val RESUME_REJECT = 19
    const val KEEPALIVE = 20
    const val CHECKPOINT_BYTES = 4L * 1024 * 1024
    val buffers = setOf(65536, 262144, 1048576)
    fun id() = UUID.randomUUID().toString()
    fun validId(value: String) { require(UUID.fromString(value).toString() == value) { "Invalid identifier" } }
    fun validName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && name.toByteArray(Charsets.UTF_8).size <= 180 &&
            name.none { it == '/' || it == '\\' || it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }) { "Unsafe file name" }
    }
    fun DataOutputStream.text(value: String, max: Int = 512) {
        val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= max)
        writeInt(bytes.size); write(bytes)
    }
    fun DataInputStream.text(max: Int = 512): String {
        val size = readInt(); require(size in 0..max) { "Invalid text length" }
        val bytes = ByteArray(size); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    fun payload(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(); DataOutputStream(bytes).use { it.block() }
        require(bytes.size() <= MAX_CONTROL); return bytes.toByteArray()
    }
    fun <T> decode(bytes: ByteArray, block: DataInputStream.() -> T): T {
        val input = DataInputStream(ByteArrayInputStream(bytes)); val result = input.block()
        require(input.available() == 0) { "Trailing protocol data" }; return result
    }
    fun token(id: String) = payload { text(id, 64) }
    fun readToken(bytes: ByteArray) = decode(bytes) { text(64).also { validId(it) } }
}

data class FileMeta(val id: String, val name: String, val size: Long, val mime: String, val sha256: ByteArray = ByteArray(32)) {
    override fun equals(other: Any?) = other is FileMeta && id == other.id && name == other.name && size == other.size && mime == other.mime && sha256.contentEquals(other.sha256)
    override fun hashCode() = id.hashCode()
    fun validate() { Protocol.validId(id); Protocol.validName(name); require(size >= 0 && sha256.size == 32); require(mime.length <= 256 && mime.none { it.isISOControl() }) }
}
data class Manifest(val id: String, val files: List<FileMeta>) {
    val total: Long get() = files.fold(0L) { sum, file -> Math.addExact(sum, file.size) }
    fun validate() {
        Protocol.validId(id); require(files.size in 1..128); require(files.map { it.id }.distinct().size == files.size)
        files.forEach { it.validate() }; require(total >= 0)
    }
    fun encode(): ByteArray { validate(); return Protocol.payload {
        with(Protocol) { text(id, 64); writeInt(files.size); writeLong(total)
            files.forEach { text(it.id,64); text(it.name); writeLong(it.size); text(it.mime,256); write(it.sha256) }
        }
    } }
    companion object {
        fun decode(bytes: ByteArray): Manifest = Protocol.decode(bytes) {
            with(Protocol) {
                val id = text(64); val count = readInt(); require(count in 1..128)
                val total = readLong(); require(total >= 0)
                val files = List(count) { FileMeta(text(64), text(), readLong(), text(256), ByteArray(32).also { readFully(it) }) }
                Manifest(id, files).also { it.validate(); require(it.total == total) }
            }
        }
    }
}

class Wire(input: InputStream, output: OutputStream, bufferSize: Int = 262144) : Closeable {
    private val input = DataInputStream(BufferedInputStream(input, bufferSize))
    private val output = DataOutputStream(BufferedOutputStream(output, bufferSize + 5))
    data class Header(val kind: Int, val size: Int)
    fun header(): Header {
        val kind = input.readUnsignedByte(); require(kind in Protocol.HELLO..Protocol.KEEPALIVE) { "Unknown frame" }
        val size = input.readInt(); require(size in 0..if (kind == Protocol.DATA) Protocol.MAX_DATA else Protocol.MAX_CONTROL) { "Invalid frame size" }
        return Header(kind, size)
    }
    fun control(header: Header): ByteArray {
        require(header.kind != Protocol.DATA); return ByteArray(header.size).also { input.readFully(it) }
    }
    fun data(buffer: ByteArray, size: Int) { require(size <= buffer.size); input.readFully(buffer, 0, size) }
    @Synchronized fun write(kind: Int, payload: ByteArray, size: Int = payload.size) {
        require(kind in Protocol.HELLO..Protocol.KEEPALIVE && size in 0..payload.size)
        require(size <= if (kind == Protocol.DATA) Protocol.MAX_DATA else Protocol.MAX_CONTROL)
        output.writeByte(kind); output.writeInt(size); output.write(payload, 0, size)
        // Keep DATA bounded by the configured buffer. Every control boundary flushes,
        // including checkpoints, final hashes and pause/cancel, so peers cannot deadlock.
        if(kind != Protocol.DATA) output.flush()
    }
    /** Sender reads directly after five reserved header bytes: no per-frame allocation or payload copy. */
    @Synchronized fun writeData(frame: ByteArray, size: Int) {
        require(size in 1..Protocol.MAX_DATA && size <= frame.size-5)
        frame[0] = Protocol.DATA.toByte()
        frame[1] = (size ushr 24).toByte(); frame[2] = (size ushr 16).toByte()
        frame[3] = (size ushr 8).toByte(); frame[4] = size.toByte()
        output.write(frame,0,size+5)
    }
    override fun close() { try { input.close() } finally { output.close() } }
}

/** All samples derive from monotonic elapsed time and actual copied bytes. */
class Meter(private val baseline: Long = 0, private val clock: () -> Long = System::nanoTime) {
    private val start = clock()
    private var last = start
    private var lastBytes = baseline
    private var smoothed: Double? = null
    fun sample(bytes: Long, total: Long): Map<String, Any?> {
        val now = clock(); val elapsed = (now - start) / 1e9
        val window = (now - last) / 1e9
        if (window >= 0.25) {
            val speed = (bytes - lastBytes) / window
            smoothed = smoothed?.let { it * 0.65 + speed * 0.35 } ?: speed
            last = now; lastBytes = bytes
        }
        val average = if (elapsed > 0) (bytes - baseline) / elapsed else null
        val speed = smoothed
        return mapOf("elapsedSeconds" to elapsed, "averageBps" to average, "currentBps" to speed,
            "etaSeconds" to if (elapsed >= 1 && bytes > 0 && speed != null && speed > 0) (total - bytes) / speed else null)
    }
}
