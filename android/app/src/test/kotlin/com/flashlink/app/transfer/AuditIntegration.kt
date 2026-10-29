package com.flashlink.app.transfer

import java.io.*
import java.net.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import com.flashlink.app.transfer.Protocol.text

/** Deterministic regressions for completion/cancellation races and storage failures. */
object AuditIntegration {
    private var count = 0
    private fun test(name: String, run: ()->Unit) { run(); count++; println("PASS $name") }
    private fun until(queue: BlockingQueue<Map<String,Any?>>, status: String): Map<String,Any?> {
        val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(System.nanoTime()<end) { val event = queue.poll(100,TimeUnit.MILLISECONDS) ?: continue; if(event["status"] == status) return event }
        error("Timed out waiting for $status")
    }
    private class Store(private val root: File, private val failWrite: Boolean = false) : ReceiveStore {
        val partial = File(root,"partial"); val finished = File(root,"finished")
        val rollbacks = AtomicInteger()
        private var stream: FileOutputStream? = null
        override fun open(file: FileMeta): OutputStream {
            if(failWrite) throw IOException("Simulated insufficient storage")
            return FileOutputStream(partial).also { stream = it }
        }
        override fun checkpoint(file: FileMeta,offset: Long,hash: ByteArray) { stream!!.fd.sync() }
        override fun commit(file: FileMeta) { check(partial.length() == file.size) }
        override fun finish() { check(partial.renameTo(finished)) }
        override fun rollback(): Boolean { rollbacks.incrementAndGet(); partial.delete(); finished.delete(); return true }
    }
    private fun hello() = Protocol.payload { writeInt(0x464c4e4b); writeInt(Protocol.VERSION); text(Protocol.id(),64); text("Audit sender"); text("sha256,sequential,approval,resume-v2",128); writeBoolean(true) }
    @JvmStatic fun main(args: Array<String>) {
        test("cancellation during COMPLETE flush cannot delete already-verified destination") {
            val directory = Files.createTempDirectory("flashlink-commit-race-").toFile()
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress()); val client = Socket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close()
            val receiverRef = AtomicReference<TransferSession>(); val intercepted = AtomicBoolean(false)
            val output = object : FilterOutputStream(accepted.getOutputStream()) {
                override fun write(bytes: ByteArray,offset: Int,length: Int) {
                    if(length > 0 && bytes[offset].toInt() == Protocol.COMPLETE && intercepted.compareAndSet(false,true)) receiverRef.get().cancel("Close during completion acknowledgement")
                    out.write(bytes,offset,length)
                }
            }
            val socket = object : Socket() {
                override fun getInputStream() = accepted.getInputStream()
                override fun getOutputStream() = output
                override fun setSoTimeout(timeout: Int) { accepted.soTimeout = timeout }
                override fun setTcpNoDelay(enabled: Boolean) { accepted.tcpNoDelay = enabled }
                override fun close() { accepted.close() }
            }
            val events = LinkedBlockingQueue<Map<String,Any?>>()
            val sent = LinkedBlockingQueue<Map<String,Any?>>()
            val ready = CountDownLatch(2); val store = Store(directory)
            val sender = TransferSession(client,"Sender",{error("not receiving")},{sent.offer(it)},{ok,_ -> if(ok) ready.countDown()})
            val receiver = TransferSession(socket,"Receiver",{store},{events.offer(it)},{ok,_ -> if(ok) ready.countDown()})
            receiverRef.set(receiver)
            try {
                sender.start(); receiver.start(); check(ready.await(5,TimeUnit.SECONDS))
                val bytes = ByteArray(65536) { (it % 251).toByte() }
                val meta = FileMeta(Protocol.id(),"verified.bin",bytes.size.toLong(),"application/octet-stream",MessageDigest.getInstance("SHA-256").digest(bytes))
                sender.send(Manifest(Protocol.id(),listOf(meta)),listOf(Source(meta) { ByteArrayInputStream(bytes) }))
                until(events,"waitingForApproval"); receiver.accept()
                val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                while(!intercepted.get() && System.nanoTime()<end) Thread.sleep(5)
                check(intercepted.get()); Thread.sleep(350)
                check(store.finished.exists() && store.finished.readBytes().contentEquals(bytes)) { "Verified destination was deleted by cancellation after COMPLETE" }
                check(store.rollbacks.get() == 0)
                until(sent,"completed")
            } finally { sender.close(); receiver.close(); directory.deleteRecursively() }
        }
        test("valid hash without a durable final checkpoint is rejected before finalization") {
            val directory = Files.createTempDirectory("flashlink-checkpoint-gap-").toFile()
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress()); val raw = Socket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close(); val events = LinkedBlockingQueue<Map<String,Any?>>()
            val ready = CountDownLatch(1); val store = Store(directory)
            val receiver = TransferSession(accepted,"Receiver",{store},{events.offer(it)},{ok,_ -> if(ok) ready.countDown()})
            try {
                receiver.start(); val wire = Wire(raw.getInputStream(),raw.getOutputStream()); wire.write(Protocol.HELLO,hello()); wire.control(wire.header()); check(ready.await(5,TimeUnit.SECONDS))
                val bytes = byteArrayOf(7,8,9); val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                val meta = FileMeta(Protocol.id(),"bytes.bin",3,"application/octet-stream",hash)
                wire.write(Protocol.OFFER,Manifest(Protocol.id(),listOf(meta)).encode()); until(events,"waitingForApproval"); receiver.accept(); wire.control(wire.header())
                wire.write(Protocol.FILE,Protocol.token(meta.id)); wire.write(Protocol.DATA,bytes); wire.write(Protocol.HASH,hash)
                until(events,"failed"); check(!store.finished.exists())
            } finally { receiver.close(); raw.close(); directory.deleteRecursively() }
        }
        test("storage write failure never reports successful completion") {
            val directory = Files.createTempDirectory("flashlink-storage-failure-").toFile()
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress()); val client = Socket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close(); val events = LinkedBlockingQueue<Map<String,Any?>>()
            val ready = CountDownLatch(2); val store = Store(directory,true)
            val sender = TransferSession(client,"Sender",{error("not receiving")},{},{ok,_ -> if(ok) ready.countDown()})
            val receiver = TransferSession(accepted,"Receiver",{store},{events.offer(it)},{ok,_ -> if(ok) ready.countDown()})
            try {
                sender.start(); receiver.start(); check(ready.await(5,TimeUnit.SECONDS))
                val meta = FileMeta(Protocol.id(),"empty.bin",0,"application/octet-stream",MessageDigest.getInstance("SHA-256").digest())
                sender.send(Manifest(Protocol.id(),listOf(meta)),listOf(Source(meta) { ByteArrayInputStream(byteArrayOf()) }))
                until(events,"waitingForApproval"); receiver.accept()
                val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(5); var failed = false
                while(System.nanoTime()<end && !failed) failed = events.poll(100,TimeUnit.MILLISECONDS)?.get("status") in setOf("failed","interrupted")
                check(failed && !store.finished.exists())
            } finally { sender.close(); receiver.close(); directory.deleteRecursively() }
        }
        test("closing during resume lookup cannot revive a transfer after the connection is closed") {
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress()); val raw = Socket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close(); val events = LinkedBlockingQueue<Map<String,Any?>>()
            val ready = CountDownLatch(1); val entered = CountDownLatch(1); val releaseLookup = CountDownLatch(1); val lookedUp = CountDownLatch(1)
            val pointReads = AtomicInteger()
            val receiver = TransferSession(accepted,"Receiver",{error("not a new offer")},{events.offer(it)},{ok,_ -> if(ok) ready.countDown()},resumeStore = {
                entered.countDown()
                var pending = true
                while(pending) { try { releaseLookup.await(); pending = false } catch (_:InterruptedException) { } }
                lookedUp.countDown()
                object : ReceiveStore {
                    override fun open(file: FileMeta): OutputStream = error("Closed session must not write")
                    override fun commit(file: FileMeta) { error("Closed session must not finalize") }
                    override fun rollback() = true
                    override fun resumePoint(): ResumePoint { pointReads.incrementAndGet(); return ResumePoint(0,0,ResumePoint.emptyHash()) }
                }
            })
            try {
                receiver.start(); val wire = Wire(raw.getInputStream(),raw.getOutputStream()); wire.write(Protocol.HELLO,hello()); wire.control(wire.header()); check(ready.await(5,TimeUnit.SECONDS))
                val meta = FileMeta(Protocol.id(),"empty.bin",0,"",MessageDigest.getInstance("SHA-256").digest())
                wire.write(Protocol.RESUME_REQUEST,Manifest(Protocol.id(),listOf(meta)).encode())
                check(entered.await(5,TimeUnit.SECONDS)); receiver.interrupt("User closed while validating resume")
                until(events,"interrupted"); releaseLookup.countDown(); check(lookedUp.await(5,TimeUnit.SECONDS)); Thread.sleep(100)
                check(pointReads.get() == 0) { "A closed session continued resume IO" }
                check(events.none { it["status"] == "transferring" }) { "A closed session resurrected transfer state" }
            } finally { releaseLookup.countDown(); receiver.close(); raw.close() }
        }
        test("slow but progressing destination finalization survives the idle deadline") {
            val directory = Files.createTempDirectory("flashlink-slow-finalization-").toFile()
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress()); val client = Socket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close(); val received = LinkedBlockingQueue<Map<String,Any?>>()
            val sent = LinkedBlockingQueue<Map<String,Any?>>()
            val ready = CountDownLatch(2); val base = Store(directory)
            val store = object : ReceiveStore by base {
                override fun finish(progress: (Long)->Unit) {
                    FileInputStream(base.partial).use { input -> FileOutputStream(base.finished).use { output ->
                        val buffer = ByteArray(8); var copied = 0L
                        while(true) {
                            val n = input.read(buffer); if(n < 0) break
                            Thread.sleep(250); output.write(buffer,0,n); copied += n; progress(copied)
                        }
                    } }
                }
            }
            val sender = TransferSession(client,"Sender",{error("not receiving")},{sent.offer(it)},{ok,_ -> if(ok) ready.countDown()},stallTimeoutSeconds=2)
            val receiver = TransferSession(accepted,"Receiver",{store},{received.offer(it)},{ok,_ -> if(ok) ready.countDown()},stallTimeoutSeconds=2)
            try {
                sender.start(); receiver.start(); check(ready.await(5,TimeUnit.SECONDS))
                val bytes = ByteArray(96) { it.toByte() }
                val meta = FileMeta(Protocol.id(),"slow.bin",bytes.size.toLong(),"",MessageDigest.getInstance("SHA-256").digest(bytes))
                sender.send(Manifest(Protocol.id(),listOf(meta)),listOf(Source(meta) { ByteArrayInputStream(bytes) }))
                until(received,"waitingForApproval"); receiver.accept()
                until(received,"finalizing"); until(sent,"completed"); until(received,"completed")
                check(base.finished.readBytes().contentEquals(bytes))
            } finally { sender.close(); receiver.close(); directory.deleteRecursively() }
        }
        test("slow checkpoint validation uses real IO liveness rather than an absolute resume timeout") {
            val directory = Files.createTempDirectory("flashlink-slow-resume-").toFile()
            val completed = File(directory,"already-verified"); val bytes = ByteArray(96) { it.toByte() }; completed.writeBytes(bytes)
            val manifest = Manifest(Protocol.id(),listOf(FileMeta(Protocol.id(),"verified.bin",96,"",MessageDigest.getInstance("SHA-256").digest(bytes))))
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress()); val client = Socket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close(); val sent = LinkedBlockingQueue<Map<String,Any?>>()
            val received = LinkedBlockingQueue<Map<String,Any?>>()
            val ready = CountDownLatch(2)
            val receiver = TransferSession(accepted,"Receiver",{error("not a new request")},{received.offer(it)},{ok,_ -> if(ok) ready.countDown()},resumeStore = { request ->
                check(request.encode().contentEquals(manifest.encode()))
                object : ReceiveStore {
                    override fun open(file: FileMeta): OutputStream = error("Completed file must not be retransmitted")
                    override fun commit(file: FileMeta) { error("Completed file must not be overwritten") }
                    override fun rollback() = true
                    override fun resumePoint(activity: ()->Unit): ResumePoint {
                        FileInputStream(completed).use { input ->
                            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8)
                            while(true) {
                                val n = input.read(buffer); if(n < 0) break
                                Thread.sleep(250); hash.update(buffer,0,n); activity()
                            }
                            check(hash.digest().contentEquals(manifest.files.single().sha256))
                        }
                        return ResumePoint(1,0,ResumePoint.emptyHash())
                    }
                }
            },stallTimeoutSeconds=2)
            val sender = TransferSession(client,"Sender",{error("not receiving")},{sent.offer(it)},{ok,_ -> if(ok) ready.countDown()},stallTimeoutSeconds=2)
            try {
                sender.start(); receiver.start(); check(ready.await(5,TimeUnit.SECONDS))
                sender.send(manifest,listOf(Source(manifest.files.single()) { FileInputStream(completed) }),true)
                until(received,"preparing"); until(sent,"completed"); until(received,"completed")
                check(completed.readBytes().contentEquals(bytes))
            } finally { sender.close(); receiver.close(); directory.deleteRecursively() }
        }
        println("$count audit regression tests passed")
    }
}
