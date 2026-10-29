package com.flashlink.app.transfer

import java.io.*
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.flashlink.app.transfer.Protocol.text

interface ReceiveStore {
    fun open(file: FileMeta): OutputStream
    fun commit(file: FileMeta)
    fun checkpoint(file: FileMeta, offset: Long, hash: ByteArray) { }
    fun resumePoint(): ResumePoint = error("Resume data unavailable")
    fun resumePoint(activity: ()->Unit): ResumePoint = resumePoint()
    fun prefix(file: FileMeta, offset: Long, digest: MessageDigest) { require(offset == 0L) }
    fun prefix(file: FileMeta, offset: Long, digest: MessageDigest, activity: ()->Unit) { prefix(file,offset,digest) }
    fun finish() { }
    fun finish(progress: (Long)->Unit) { finish() }
    fun cancelPending() { }
    fun release() { }
    /** Deletes all files from this batch, including verified files, on batch failure. */
    fun rollback(): Boolean
}
data class Source(val meta: FileMeta, val open: () -> InputStream)

/** One socket, one reader, one sequential writer. No file bytes cross Flutter. */
class TransferSession(
    private val socket: Socket,
    private val displayName: String,
    private val storeFactory: (Manifest) -> ReceiveStore,
    private val event: (Map<String, Any?>) -> Unit,
    private val readyChanged: (Boolean, String?) -> Unit,
    private val bufferSize: Int = 262144,
    private val canReceive: () -> Boolean = { true },
    private val canOffer: (Manifest) -> Boolean = { true },
    private val record: (Manifest, Boolean) -> Unit = { _, _ -> },
    private val approved: (Manifest) -> Unit = { },
    private val resumeStore: (Manifest) -> ReceiveStore? = { null },
    private val senderCheckpoint: (Manifest, FileMeta, Long) -> Unit = { _, _, _ -> },
    private val stallTimeoutSeconds: Long = 35,
) : Closeable {
    private val wire = Wire(socket.getInputStream(), socket.getOutputStream(), bufferSize)
    private val reader = Executors.newSingleThreadExecutor()
    private val writer = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean(false)
    private val released = CountDownLatch(1)
    private val heartbeatPending = AtomicBoolean(false)
    fun awaitReleased(): Boolean = released.await(3,TimeUnit.SECONDS)
    private val active = AtomicReference<Job?>(null)
    private val seen = LinkedHashSet<String>()
    @Volatile private var ready = false
    @Volatile private var peer = "Android device"
    @Volatile private var lastIo = System.nanoTime()
    private var lastActivityHeartbeat = 0L
    private class Job(val manifest: Manifest, val outgoing: Boolean, val peer: String, val resuming: Boolean = false) {
        val started = System.currentTimeMillis()
        var meter = Meter()
        val checkpointAcks = ArrayBlockingQueue<ByteArray>(4)
        val resumeAcks = ArrayBlockingQueue<ResumePoint>(1)
        val pause = AtomicBoolean(false)
        var chunk = MessageDigest.getInstance("SHA-256")
        var checkpointOffset = 0L
        var checkpointObserved = false
        @Volatile var awaitingCompletion = false
        val acknowledgements = ArrayBlockingQueue<Pair<Int, String>>(4)
        @Volatile var phase = "waitingForApproval"
        @Volatile var bytes = 0L
        @Volatile var fileBytes = 0L
        @Volatile var finalizedBytes = 0L
        @Volatile var completed = 0
        @Volatile var current: FileMeta? = null
        @Volatile var input: InputStream? = null
        @Volatile var output: OutputStream? = null
        @Volatile var store: ReceiveStore? = null
        var hash: MessageDigest? = null
        var approval: ScheduledFuture<*>? = null
        var lastEvent = 0L
    }
    init { require(bufferSize in Protocol.buffers) }
    fun start() {
        reader.execute {
            try {
                socket.soTimeout = 15000; socket.tcpNoDelay = true
                wire.write(Protocol.HELLO, Protocol.payload {
                    writeInt(0x464c4e4b); writeInt(Protocol.VERSION); text(Protocol.id(),64)
                    text(displayName.take(32)); text("sha256,sequential,approval,resume-v2",128); writeBoolean(true)
                })
                val header = wire.header(); require(header.kind == Protocol.HELLO)
                peer = Protocol.decode(wire.control(header)) {
                    require(readInt() == 0x464c4e4b && readInt() == Protocol.VERSION) { "Incompatible FlashLink protocol" }
                    Protocol.validId(text(64)); val name = text(); require(name.isNotBlank())
                    val capabilities = text(128).split(','); require(capabilities.containsAll(listOf("sha256","sequential","approval","resume-v2")))
                    require(readBoolean()) { "Peer is not ready" }; name
                }
                socket.soTimeout = 120000; ready = true; readyChanged(true, peer)
                val startedSession = System.nanoTime()
                deadlines.scheduleAtFixedRate({
                    if(System.nanoTime()-startedSession > 1_800_000_000_000L) { interrupt("Session expired after 30 minutes; authenticate again and resume"); return@scheduleAtFixedRate }
                    val current = active.get()
                    if(ready && (current == null || current.outgoing && current.phase == "waitingForApproval")) {
                        if(heartbeatPending.compareAndSet(false,true)) Thread {
                            try { wire.write(Protocol.KEEPALIVE,byteArrayOf()); lastIo = System.nanoTime() }
                            catch (_:Exception) { }
                            finally { heartbeatPending.set(false) }
                        }.start()
                    }
                },10,10,TimeUnit.SECONDS)
                deadlines.scheduleAtFixedRate({
                    val job = active.get()
                    if (job != null && job.phase != "waitingForApproval" && System.nanoTime() - lastIo > TimeUnit.SECONDS.toNanos(stallTimeoutSeconds)) {
                        abort("Transfer stalled for 35 seconds.", false)
                    }
                }, 1, 1, TimeUnit.SECONDS)
                readLoop()
            } catch (error: Exception) { abort(error.message ?: "Connection interrupted", false,
                if(error is IOException) "interrupted" else "failed") }
        }
    }
    fun send(manifest: Manifest, sources: List<Source>, resume: Boolean = false) {
        manifest.validate(); check(ready && !closed.get()) { "Open a TCP session first" }
        require(sources.map { it.meta } == manifest.files)
        synchronized(seen) {
            require(!seen.contains(manifest.id)) { "Transfer ID was already used" }
            require(seen.size < 128); seen.add(manifest.id)
        }
        val job = Job(manifest, true, peer, resume).also { if(resume) it.phase = "preparing" }
        check(active.compareAndSet(null, job)) { "Another transfer is active" }
        record(manifest,true); emit(job, true)
        try {
            wire.write(if(resume) Protocol.RESUME_REQUEST else Protocol.OFFER, manifest.encode()); lastIo = System.nanoTime()
            if(!resume) job.approval = deadlines.schedule({ abort("Receiver approval timed out.", false) },120,TimeUnit.SECONDS)
            // Writer waits for a genuine ACCEPT, not a method-channel acknowledgement.
            writer.execute {
                try {
                    val point = if(resume) expectResume(job) else {
                        expectAck(job, Protocol.ACCEPT, manifest.id, 125); approved(manifest); ResumePoint(0,0,ResumePoint.emptyHash())
                    }
                    point.validate(manifest)
                    job.completed = point.completed; job.fileBytes = point.offset; job.checkpointOffset = point.offset
                    job.bytes = manifest.files.take(point.completed).sumOf { it.size } + point.offset
                    if(resume) sources.forEachIndexed { index, source ->
                        if(index >= point.completed) FileValidation.source(source,if(index == point.completed) point.offset else 0,
                            if(index == point.completed) point.prefix else null, cancelled = { closed.get() || job.pause.get() || Thread.currentThread().isInterrupted }, activity = { touchActivity() })
                    }
                    job.completed = point.completed
                    job.bytes = manifest.files.take(point.completed).sumOf { it.size } + point.offset
                    job.meter = Meter(job.bytes); job.phase = "transferring"; lastIo = System.nanoTime(); emit(job, true)
                    val buffer = ByteArray(bufferSize)
                    for ((index,source) in sources.withIndex()) {
                        if(index < point.completed) continue
                        val offset = if(index == point.completed) point.offset else 0L
                        ensureActive(job); job.current = source.meta; job.fileBytes = offset
                        job.checkpointOffset = offset; job.chunk = MessageDigest.getInstance("SHA-256")
                        wire.write(Protocol.FILE, Protocol.token(source.meta.id))
                        val digest = MessageDigest.getInstance("SHA-256")
                        source.open().use { input ->
                            job.input = input
                            FileValidation.seed(input,offset,digest) { touchActivity() }
                            var remaining = source.meta.size - offset
                            while (remaining > 0) {
                                ensureActive(job)
                                if(job.pause.get()) {
                                    checkpoint(job)
                                    wire.write(Protocol.PAUSED,Protocol.token(manifest.id))
                                    abort("Paused at a durable receiver checkpoint",false,"paused"); return@execute
                                }
                                val count = input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
                                if (count < 0) throw EOFException("Selected file became shorter")
                                if (count == 0) continue
                                wire.write(Protocol.DATA,buffer,count); digest.update(buffer,0,count); job.chunk.update(buffer,0,count)
                                remaining -= count; job.bytes += count; job.fileBytes += count
                                lastIo = System.nanoTime(); emit(job)
                                if(job.fileBytes - job.checkpointOffset >= Protocol.CHECKPOINT_BYTES) checkpoint(job)
                            }
                            require(input.read() == -1) { "Selected file changed size" }
                        }
                        job.input = null; ensureActive(job); job.phase = "verifying"; emit(job,true)
                        checkpoint(job)
                        val finalHash = digest.digest()
                        require(MessageDigest.isEqual(finalHash,source.meta.sha256)) { "Source changed during streaming" }
                        wire.write(Protocol.HASH,finalHash); expectAck(job,Protocol.FILE_OK,source.meta.id)
                        job.completed++; job.phase = "transferring"; emit(job,true)
                    }
                    job.phase = "finalizing"; job.awaitingCompletion = true; emit(job,true)
                    wire.write(Protocol.DONE,Protocol.token(manifest.id))
                    expectAck(job,Protocol.COMPLETE,manifest.id)
                    finish(job,"completed",null)
                } catch (error: Exception) {
                    if(active.get() === job) {
                        if(job.pause.get() && job.fileBytes == job.checkpointOffset && !closed.get()) {
                            runCatching { wire.write(Protocol.PAUSED,Protocol.token(manifest.id)) }
                            abort("Paused at the agreed durable checkpoint",false,"paused")
                        } else abort(error.message ?: "Send failed",false,if(error is IOException) "interrupted" else "failed")
                    }
                }
            }
        } catch (error: Exception) { abort(error.message ?: "Could not send request",false) }
    }
    fun accept() {
        val job = active.get() ?: error("No incoming request")
        synchronized(job) {
            check(!job.outgoing && job.phase == "waitingForApproval")
            job.store = storeFactory(job.manifest)
            approved(job.manifest)
            job.meter = Meter(); job.phase = "transferring"; job.approval?.cancel(false); lastIo = System.nanoTime()
        }
        wire.write(Protocol.ACCEPT,Protocol.token(job.manifest.id)); emit(job,true)
    }
    fun reject() {
        val job = active.get() ?: return
        check(!job.outgoing && job.phase == "waitingForApproval")
        wire.write(Protocol.REJECT,Protocol.token(job.manifest.id)); finish(job,"cancelled","Incoming request rejected")
    }
    private fun readLoop() {
        val buffer = ByteArray(Protocol.MAX_DATA)
        while (!closed.get()) {
            val header = wire.header(); lastIo = System.nanoTime()
            if (header.kind == Protocol.DATA) {
                val job = active.get() ?: error("Unexpected file bytes")
                val file = job.current ?: error("Missing file boundary")
                check(!job.outgoing && job.phase == "transferring" && job.output != null)
                require(header.size > 0 && header.size.toLong() <= file.size - job.fileBytes) { "File exceeds declared size" }
                wire.data(buffer,header.size)
                job.output!!.write(buffer,0,header.size); job.hash!!.update(buffer,0,header.size); job.chunk.update(buffer,0,header.size)
                job.bytes += header.size; job.fileBytes += header.size; lastIo = System.nanoTime(); emit(job)
                continue
            }
            val body = wire.control(header)
            when (header.kind) {
                Protocol.OFFER -> {
                    val manifest = Manifest.decode(body)
                    synchronized(seen) { require(!seen.contains(manifest.id)) { "Duplicate transfer ID" } }
                    if(!canOffer(manifest)) { wire.write(Protocol.REJECT,Protocol.token(manifest.id)); continue }
                    val incoming = Job(manifest,false,peer)
                    if (!canReceive() || !active.compareAndSet(null,incoming)) {
                        wire.write(Protocol.REJECT,Protocol.token(manifest.id)); continue
                    }
                    if (seen.size >= 128) error("Session transfer limit reached; reconnect")
                    synchronized(seen) { seen.add(manifest.id) }; record(manifest,false); emit(incoming,true)
                    incoming.approval = deadlines.schedule({ abort("Incoming approval timed out.",false) },120,TimeUnit.SECONDS)
                }
                Protocol.KEEPALIVE -> { require(body.isEmpty()) }
                Protocol.RESUME_REQUEST -> {
                    val manifest = Manifest.decode(body)
                    if(active.get() != null || !canReceive()) { wire.write(Protocol.RESUME_REJECT,Protocol.token(manifest.id)); continue }
                    val job = Job(manifest,false,peer,true).also { it.phase = "preparing" }
                    check(active.compareAndSet(null,job))
                    val store = resumeStore(manifest)
                    ensureActive(job)
                    if(store == null) { active.compareAndSet(job,null); wire.write(Protocol.RESUME_REJECT,Protocol.token(manifest.id)); continue }
                    job.store = store; emit(job,true)
                    val point = store.resumePoint { touchActivity() }; point.validate(manifest)
                    ensureActive(job)
                    job.completed = point.completed; job.fileBytes = point.offset; job.checkpointOffset = point.offset
                    job.bytes = manifest.files.take(point.completed).sumOf { it.size } + point.offset
                    job.phase = "transferring"; job.meter = Meter(job.bytes)
                    emit(job,true); wire.write(Protocol.RESUME_ACCEPT,point.encode())
                }
                Protocol.RESUME_ACCEPT -> {
                    val job = active.get() ?: error("Unexpected resume")
                    check(job.outgoing); job.approval?.cancel(false)
                    check(job.resumeAcks.offer(ResumePoint.decode(body,job.manifest)))
                }
                Protocol.RESUME_REJECT -> { Protocol.readToken(body); abort("Resume rejected: manifest, peer or checkpoint did not match",false,"failed"); return }
                Protocol.PAUSE -> { val job = active.get() ?: error("No transfer to pause"); require(Protocol.readToken(body) == job.manifest.id); job.pause.set(true) }
                Protocol.PAUSED -> {
                    val job = active.get() ?: error("No transfer to pause"); require(!job.outgoing && Protocol.readToken(body) == job.manifest.id)
                    abort("Peer paused at a durable checkpoint",false,"paused"); return
                }
                Protocol.CHECKPOINT -> {
                    val job = active.get() ?: error("Unexpected checkpoint"); val file = job.current ?: error("No file")
                    require(!job.outgoing)
                    Protocol.decode(body) { with(Protocol) {
                        require(text(64) == file.id); val offset = readLong(); val expected = ByteArray(32).also { readFully(it) }
                        require(offset == job.fileBytes && offset >= job.checkpointOffset && offset <= file.size)
                        require(MessageDigest.isEqual(job.chunk.digest(),expected)) { "Checkpoint chunk mismatch" }
                        job.store!!.checkpoint(file,offset,expected); job.checkpointOffset = offset; job.checkpointObserved = true
                    } }
                    wire.write(Protocol.CHECKPOINT_OK,body)
                }
                Protocol.CHECKPOINT_OK -> { val job = active.get() ?: error("Unexpected checkpoint ack"); check(job.outgoing && job.checkpointAcks.offer(body)) }
                Protocol.COMPLETE -> {
                    val job = active.get() ?: error("Unexpected completion acknowledgement")
                    require(job.outgoing && job.awaitingCompletion && Protocol.readToken(body) == job.manifest.id &&
                        job.completed == job.manifest.files.size && job.bytes == job.manifest.total)
                    // A valid receipt is terminal before any subsequent CANCEL/EOF can be processed.
                    if(finish(job,"completed",null)) job.acknowledgements.offer(Protocol.COMPLETE to job.manifest.id)
                }
                Protocol.ACCEPT, Protocol.FILE_OK -> {
                    val job = active.get() ?: error("Unexpected acknowledgement")
                    check(job.outgoing)
                    check(job.acknowledgements.offer(header.kind to Protocol.readToken(body))) { "Too many acknowledgements" }
                    if (header.kind == Protocol.ACCEPT) job.approval?.cancel(false)
                }
                Protocol.REJECT -> {
                    val job = active.get() ?: error("Unexpected rejection")
                    check(job.outgoing && job.phase == "waitingForApproval" && Protocol.readToken(body) == job.manifest.id)
                    // Close to wake any blocked writer and make rejection terminal.
                    abort("Receiver rejected the request.",true)
                }
                Protocol.FILE -> {
                    val job = active.get() ?: error("Unapproved file")
                    check(!job.outgoing && job.phase == "transferring" && job.current == null)
                    val file = job.manifest.files.getOrNull(job.completed) ?: error("Too many files")
                    require(Protocol.readToken(body) == file.id)
                    job.current = file
                    if(job.fileBytes > file.size || job.completed == 0 && job.bytes == 0L) job.fileBytes = 0
                    job.hash = MessageDigest.getInstance("SHA-256")
                    job.store!!.prefix(file,job.fileBytes,job.hash!!) { touchActivity() }
                    job.chunk = MessageDigest.getInstance("SHA-256"); job.checkpointOffset = job.fileBytes; job.checkpointObserved = false
                    job.output = job.store!!.open(file); emit(job,true)
                }
                Protocol.HASH -> {
                    val job = active.get() ?: error("Unexpected hash")
                    val file = job.current ?: error("Missing file")
                    require(!job.outgoing && job.phase == "transferring" && job.output != null && job.fileBytes == file.size &&
                        job.checkpointObserved && job.checkpointOffset == file.size && body.size == 32) { "Missing durable final checkpoint or invalid hash boundary" }
                    job.phase = "verifying"; emit(job,true)
                    job.output!!.close(); job.output = null
                    require(MessageDigest.isEqual(job.hash!!.digest(),body) && MessageDigest.isEqual(body,file.sha256)) { "SHA-256 mismatch; received files removed" }
                    job.store!!.commit(file); job.completed++; job.current = null; job.fileBytes = 0; job.checkpointOffset = 0; job.phase = "transferring"
                    wire.write(Protocol.FILE_OK,Protocol.token(file.id)); emit(job,true)
                }
                Protocol.DONE -> {
                    val job = active.get() ?: error("Unexpected completion")
                    require(!job.outgoing && job.current == null && job.completed == job.manifest.files.size &&
                        job.bytes == job.manifest.total && Protocol.readToken(body) == job.manifest.id)
                    ensureActive(job)
                    job.phase = "finalizing"; emit(job,true)
                    var lastFinalizationHeartbeat = 0L
                    job.store!!.finish { finalized ->
                        ensureActive(job)
                        require(finalized in job.finalizedBytes..job.manifest.total)
                        job.finalizedBytes = finalized
                        val now = System.nanoTime()
                        if(now-lastFinalizationHeartbeat >= 1_000_000_000L) {
                            wire.write(Protocol.KEEPALIVE,byteArrayOf()); lastIo = now; lastFinalizationHeartbeat = now
                        }
                        emit(job)
                    }
                    // Completion wins or loses atomically against Cancel. Never ACK and then delete the files.
                    if(finish(job,"completed",null)) wire.write(Protocol.COMPLETE,Protocol.token(job.manifest.id))
                }
                Protocol.CANCEL -> { Protocol.readToken(body); abort("Peer cancelled the session.",true); return }
                Protocol.ERROR -> { abort("Peer reported a transfer or storage error.",false); return }
                else -> error("Unexpected protocol message")
            }
        }
    }
    fun pause() {
        val job = active.get() ?: error("No transfer to pause")
        check(job.phase in listOf("transferring","verifying"))
        job.pause.set(true)
        if(!job.outgoing) wire.write(Protocol.PAUSE,Protocol.token(job.manifest.id))
    }
    fun interrupt(message: String) { abort(message,false,"interrupted") }
    private fun checkpoint(job: Job) {
        val file = job.current ?: return
        val hash = job.chunk.digest()
        val body = Protocol.payload { with(Protocol) { text(file.id,64); writeLong(job.fileBytes); write(hash) } }
        wire.write(Protocol.CHECKPOINT,body)
        val ack = job.checkpointAcks.poll(stallTimeoutSeconds,TimeUnit.SECONDS) ?: error("Checkpoint acknowledgement timed out")
        ensureActive(job); require(MessageDigest.isEqual(body,ack)) { "Invalid checkpoint acknowledgement" }
        job.checkpointOffset = job.fileBytes; senderCheckpoint(job.manifest,file,job.fileBytes)
    }
    @Synchronized private fun touchActivity() {
        if(closed.get()) throw InterruptedIOException("Connection closed during validation")
        val now = System.nanoTime()
        if(now-lastActivityHeartbeat >= 1_000_000_000L) {
            try { wire.write(Protocol.KEEPALIVE,byteArrayOf()) }
            catch(error: IOException) { throw InterruptedIOException("Connection lost during validation").apply { initCause(error) } }
            lastActivityHeartbeat = now
        }
        lastIo = now
    }
    private fun expectResume(job: Job): ResumePoint {
        while(true) {
            val point = job.resumeAcks.poll(1,TimeUnit.SECONDS)
            if(point != null) return point
            ensureActive(job)
            check(System.nanoTime()-lastIo < TimeUnit.SECONDS.toNanos(stallTimeoutSeconds)) { "Peer stopped validating the checkpoint" }
        }
    }
    private fun expectAck(job: Job, kind: Int, id: String, seconds: Long = stallTimeoutSeconds) {
        var ack: Pair<Int,String>? = null
        while(ack == null) {
            ack = job.acknowledgements.poll(1,TimeUnit.SECONDS)
            if(ack == null) {
                ensureActive(job)
                check(System.nanoTime()-lastIo < TimeUnit.SECONDS.toNanos(seconds)) { "Peer stopped making progress" }
            }
        }
        if(kind != Protocol.COMPLETE || job.phase != "completed") ensureActive(job)
        require(ack.first == kind && ack.second == id) { "Invalid transfer acknowledgement" }
    }
    private fun ensureActive(job: Job) { check(!closed.get() && active.get() === job) { "Transfer cancelled" } }
    private fun emit(job: Job, force: Boolean = false, message: String? = null) {
        synchronized(job) {
            if(active.get() !== job && !(force && job.phase in listOf("completed","failed","cancelled","paused","interrupted"))) return
            val now = System.nanoTime()
            if (!force && now - job.lastEvent < 200_000_000) return
            job.lastEvent = now
            event(mapOf("id" to job.manifest.id,"status" to job.phase,"resuming" to job.resuming,"direction" to if(job.outgoing) "sent" else "received",
                "peer" to job.peer,"files" to job.manifest.files.map { mapOf("name" to it.name,"size" to it.size,"mime" to it.mime) },
                "total" to job.manifest.total,"bytes" to job.bytes,"finalizedBytes" to job.finalizedBytes,"fileBytes" to job.fileBytes,"currentFile" to job.current?.name,
                "fileCount" to job.manifest.files.size,"completedFiles" to job.completed,"started" to job.started,
                "ended" to if(job.phase in listOf("completed","cancelled","failed","paused","interrupted")) System.currentTimeMillis() else null,
                "verified" to (job.phase == "completed"),"message" to message) + job.meter.sample(job.bytes,job.manifest.total))
        }
    }
    private fun finish(job: Job, status: String, message: String?): Boolean {
        if (!active.compareAndSet(job,null)) return false
        job.approval?.cancel(false)
        job.phase = status
        if(status != "completed") job.store?.cancelPending()
        emit(job,true,message)
        if(status != "completed") {
            Thread {
                try { job.input?.close() } catch (_: Exception) { }
                try { job.output?.close() } catch (_: Exception) { }
                runCatching { job.store?.release() }
                val cleaned = status != "cancelled" || runCatching { job.store?.rollback() != false }.getOrDefault(false)
                job.phase = status
                released.countDown()
                if(!cleaned) emit(job,true,"$message Some destination files could not be removed; check your folder.")
            }.start()
        }
        return true
    }

    fun fail(message: String) { abort(message,false) }
    fun cancel(message: String = "Cancelled by you") { abort(message,true) }
    private fun abort(message: String, cancelled: Boolean, state: String = "failed") {
        if (!closed.compareAndSet(false,true)) return
        ready = false
        val job = active.get()
        // Best effort notification, with an independent close deadline for blocked writes.
        Thread { Thread.sleep(250); try { socket.close() } catch (_: Exception) { } }.start()
        writer.shutdownNow()
        Thread {
            try { if(state != "paused") wire.write(if(cancelled) Protocol.CANCEL else Protocol.ERROR,Protocol.token(job?.manifest?.id ?: Protocol.id())) } catch (_: Exception) { }
            try { socket.close() } catch (_: Exception) { }
        }.start()
        if(job == null) released.countDown()
        if (job != null) finish(job,if(cancelled) "cancelled" else state,message)
        readyChanged(false,message)
        reader.shutdownNow(); deadlines.shutdown()
    }
    override fun close() { interrupt("Connection lost; durable checkpoints retained") }
}
