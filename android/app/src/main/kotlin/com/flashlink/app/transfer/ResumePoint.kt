package com.flashlink.app.transfer

import java.io.*
import java.security.MessageDigest

/** Offset is a durable receiver checkpoint, never a sender's optimistic byte count. */
data class ResumePoint(val completed: Int, val offset: Long, val prefix: ByteArray) {
    fun validate(manifest: Manifest) {
        require(completed in 0..manifest.files.size && offset >= 0 && prefix.size == 32)
        if(completed == manifest.files.size) require(offset == 0L)
        else require(offset <= manifest.files[completed].size)
    }
    fun encode() = Protocol.payload { writeInt(completed); writeLong(offset); write(prefix) }
    companion object {
        fun decode(bytes: ByteArray, manifest: Manifest) = Protocol.decode(bytes) {
            ResumePoint(readInt(),readLong(),ByteArray(32).also { readFully(it) }).also { it.validate(manifest) }
        }
        fun emptyHash(): ByteArray = MessageDigest.getInstance("SHA-256").digest()
    }
}

object FileValidation {
    fun hash(input: InputStream, size: Long, prefix: Long = 0, cancelled: ()->Boolean = { Thread.currentThread().isInterrupted }, activity: ()->Unit = {}): Pair<ByteArray,ByteArray> {
        require(size >= 0 && prefix in 0..size)
        val full = MessageDigest.getInstance("SHA-256"); val partial = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(262144); var copied = 0L
        while(copied < size) {
            if(cancelled()) throw InterruptedIOException("Source validation interrupted")
            val n = input.read(buffer,0,minOf(buffer.size.toLong(),size-copied).toInt())
            if(n < 0) throw EOFException("Source or partial file is shorter than its checkpoint")
            if(n == 0) continue
            full.update(buffer,0,n)
            val p = minOf(n.toLong(),(prefix-copied).coerceAtLeast(0)).toInt()
            if(p > 0) partial.update(buffer,0,p)
            copied += n; activity()
        }
        require(input.read() == -1) { "Source size changed" }
        return full.digest() to partial.digest()
    }
    fun source(source: Source, offset: Long = 0, prefix: ByteArray? = null, cancelled: ()->Boolean = { Thread.currentThread().isInterrupted }, activity: ()->Unit = {}) {
        val result = source.open().use { hash(it,source.meta.size,offset,cancelled,activity) }
        require(MessageDigest.isEqual(result.first,source.meta.sha256)) { "Source content changed; this transfer cannot resume" }
        if(prefix != null) require(MessageDigest.isEqual(result.second,prefix)) { "Receiver checkpoint does not match the source" }
    }
    /** Recheck the exact staging bytes while saving, before the temporary document is renamed. */
    fun copyVerified(input: InputStream, output: OutputStream, file: FileMeta,
        buffer: ByteArray = ByteArray(262144), cancelled: ()->Boolean = { Thread.currentThread().isInterrupted },
        progress: (Long)->Unit = {}): Long {
        require(buffer.isNotEmpty())
        val hash = MessageDigest.getInstance("SHA-256"); var copied = 0L; var emptyReads = 0
        while(copied < file.size) {
            if(cancelled()) throw InterruptedIOException("Saving verified files was cancelled")
            val n = input.read(buffer,0,minOf(buffer.size.toLong(),file.size-copied).toInt())
            if(n < 0) throw EOFException("Verified staging file became shorter")
            if(n == 0) { if(++emptyReads >= 16) throw IOException("Staging input stopped returning bytes"); continue }
            emptyReads = 0; output.write(buffer,0,n); hash.update(buffer,0,n); copied += n; progress(copied)
        }
        if(cancelled()) throw InterruptedIOException("Saving verified files was cancelled")
        require(input.read() == -1 && MessageDigest.isEqual(hash.digest(),file.sha256)) { "Verified staging content changed before saving; destination was not finalized" }
        return copied
    }
    fun seed(input: InputStream, offset: Long, digest: MessageDigest, activity: ()->Unit = {}) {
        val buffer = ByteArray(262144); var remaining = offset
        while(remaining > 0) {
            if(Thread.currentThread().isInterrupted) throw InterruptedIOException("Prefix validation interrupted")
            val n = input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
            if(n < 0) throw EOFException("Missing resume prefix")
            if(n == 0) continue
            digest.update(buffer,0,n); remaining -= n; activity()
        }
    }
}

data class ChunkCheckpoint(val start: Long, val end: Long, val hash: ByteArray)
object CheckpointValidation {
    fun verify(file: File, offset: Long, chunks: Iterable<ChunkCheckpoint>, activity: ()->Unit = {}) {
        require(offset >= 0 && file.isFile && file.length() >= offset) { "Missing or truncated partial file" }
        FileInputStream(file).use { input ->
            val buffer = ByteArray(262144); var position = 0L
            for(chunk in chunks) {
                require(chunk.start == position && chunk.end > chunk.start && chunk.end <= offset && chunk.hash.size == 32) { "Invalid checkpoint journal" }
                val digest = MessageDigest.getInstance("SHA-256"); var remaining = chunk.end-chunk.start
                while(remaining > 0) {
                    val n = input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt()); require(n > 0)
                    digest.update(buffer,0,n); remaining -= n; activity()
                }
                require(MessageDigest.isEqual(digest.digest(),chunk.hash)) { "Corrupted partial file; checkpoint rejected" }
                position = chunk.end
            }
            require(position == offset) { "Checkpoint offset has no verified bytes" }
        }
    }
}

/** A completed receipt can reconcile a lost final ACK, but can never authorize new file bytes. */
class CompletedReceiptStore(private val manifest: Manifest) : ReceiveStore {
    override fun open(file: FileMeta): OutputStream = error("Completed receipt is read-only")
    override fun commit(file: FileMeta) = error("Completed receipt is read-only")
    override fun resumePoint() = ResumePoint(manifest.files.size,0,ResumePoint.emptyHash())
    override fun rollback() = true
}
