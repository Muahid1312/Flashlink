package com.flashlink.app.transfer

import java.io.*
import java.net.*
import java.nio.file.Files
import java.security.*
import java.security.cert.X509Certificate
import javax.net.ssl.*
import java.util.concurrent.*

/** Production resume validation and protocol, real private files and sockets. No simulated progress. */
object RecoveryIntegration {
    private var count = 0
    private var throughputOnly = false
    private fun test(name: String, run: ()->Unit) { if(throughputOnly && !name.startsWith("authenticated encrypted transfer")) return; run(); count++; println("PASS $name") }
    private fun invalid(run: ()->Unit) { check(runCatching(run).isFailure) }
    private fun waitFor(queue: BlockingQueue<Map<String,Any?>>, predicate: (Map<String,Any?>)->Boolean): Map<String,Any?> {
        val until = System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(System.nanoTime()<until) {
            val event = queue.poll(100,TimeUnit.MILLISECONDS) ?: continue
            if(predicate(event)) return event
            check(event["status"] !in setOf("failed","cancelled")) { "Unexpected failure: $event" }
        }
        error("Event timeout")
    }
    private class DiskStore(private val root: File, private val manifest: Manifest) : ReceiveStore {
        private val outputs = mutableMapOf<String,FileOutputStream>()
        private fun path(file: FileMeta) = File(root,file.id)
        private fun journal(file: FileMeta) = File(root,"${file.id}.checkpoint")
        fun chunks(file: FileMeta): List<ChunkCheckpoint> {
            val journal = journal(file); if(!journal.exists()) return emptyList()
            return DataInputStream(FileInputStream(journal)).use { input ->
                val n = input.readInt(); require(n in 0..10000)
                List(n) { ChunkCheckpoint(input.readLong(),input.readLong(),ByteArray(32).also { input.readFully(it) }) }
            }
        }
        private fun offset(file: FileMeta) = chunks(file).lastOrNull()?.end ?: 0L
        override fun open(file: FileMeta): OutputStream {
            root.mkdirs(); RandomAccessFile(path(file),"rw").use { it.setLength(offset(file)) }
            return FileOutputStream(path(file),true).also { outputs[file.id] = it }
        }
        override fun checkpoint(file: FileMeta, offset: Long, hash: ByteArray) {
            outputs[file.id]!!.fd.sync()
            val old = chunks(file); val start = old.lastOrNull()?.end ?: 0L
            val next = if(offset > start) old + ChunkCheckpoint(start,offset,hash) else old
            val temp = File(root,"${file.id}.checkpoint.tmp")
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(stream); out.writeInt(next.size)
                next.forEach { out.writeLong(it.start); out.writeLong(it.end); out.write(it.hash) }; out.flush(); stream.fd.sync()
            }
            Files.move(temp.toPath(),journal(file).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        override fun prefix(file: FileMeta, offset: Long, digest: MessageDigest) {
            if(offset > 0) FileInputStream(path(file)).use { FileValidation.seed(it,offset,digest) }
        }
        override fun commit(file: FileMeta) { File(root,"${file.id}.verified").writeText("verified") }
        override fun resumePoint(): ResumePoint {
            var completed = 0
            for(file in manifest.files) {
                val offset = offset(file)
                if(!path(file).exists() && offset == 0L) return ResumePoint(completed,0,ResumePoint.emptyHash())
                CheckpointValidation.verify(path(file),offset,chunks(file))
                RandomAccessFile(path(file),"rw").use { it.setLength(offset); it.fd.sync() }
                if(File(root,"${file.id}.verified").exists()) {
                    check(offset == file.size)
                    check(FileInputStream(path(file)).use { FileValidation.hash(it,file.size).first }.contentEquals(file.sha256))
                    completed++
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    if(offset > 0) FileInputStream(path(file)).use { FileValidation.seed(it,offset,digest) }
                    return ResumePoint(completed,offset,digest.digest())
                }
            }
            return ResumePoint(completed,0,ResumePoint.emptyHash())
        }
        override fun rollback() = root.deleteRecursively()
    }
    private class Link(val manifest: Manifest, root: File, val resume: Boolean = false, tls: Pair<SSLContext,SSLContext>? = null, buffer: Int = 262144) : Closeable {
        val sent = LinkedBlockingQueue<Map<String,Any?>>()
        val received = LinkedBlockingQueue<Map<String,Any?>>()
        val store = DiskStore(root,manifest)
        val sender: TransferSession; val receiver: TransferSession
        init {
            val server = if(tls == null) ServerSocket(0,1,InetAddress.getLoopbackAddress()) else
                (tls.first.serverSocketFactory.createServerSocket(0,1,InetAddress.getLoopbackAddress()) as SSLServerSocket).apply { needClientAuth = true }
            val client = if(tls == null) Socket(InetAddress.getLoopbackAddress(),server.localPort) else tls.second.socketFactory.createSocket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close(); val ready = CountDownLatch(2)
            if(tls != null) {
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val authenticated = executor.submit { SecureTransport.authenticate(accepted as SSLSocket) }
                    SecureTransport.authenticate(client as SSLSocket); authenticated.get(20,TimeUnit.SECONDS)
                } finally { executor.shutdownNow() }
            }
            sender = TransferSession(client,"Sender",{error("not receiving")},{sent.offer(it)},{ok,_ -> if(ok) ready.countDown()},buffer)
            receiver = TransferSession(accepted,"Receiver",{store},{received.offer(it)},{ok,_ -> if(ok) ready.countDown()},
                bufferSize = buffer, canOffer = { !resume }, resumeStore = { request -> if(resume && request.encode().contentEquals(manifest.encode())) store else null })
            sender.start(); receiver.start(); check(ready.await(5,TimeUnit.SECONDS))
        }
        override fun close() { sender.close(); receiver.close(); sender.awaitReleased(); receiver.awaitReleased() }
    }
    private fun source(file: File, meta: FileMeta, slow: Boolean = false) = Source(meta) {
        if(!slow) FileInputStream(file) else object : FilterInputStream(FileInputStream(file)) {
            override fun read(b: ByteArray,off: Int,len: Int): Int { Thread.sleep(4); return super.read(b,off,len) }
        }
    }
    private fun keys(root: File,name: String): Pair<Array<KeyManager>,X509Certificate> {
        val file = File(root,"$name.p12"); val password = Protocol.id()
        val keytool = File(System.getProperty("java.home"),"bin/keytool").absolutePath
        val process = ProcessBuilder(keytool,"-genkeypair","-alias","node","-keyalg","EC","-groupname","secp256r1","-validity","2","-dname","CN=$name","-storetype","PKCS12","-keystore",file.path,"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
        val store = KeyStore.getInstance("PKCS12"); FileInputStream(file).use { store.load(it,password.toCharArray()) }
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); manager.init(store,password.toCharArray())
        return manager.keyManagers to (store.getCertificate("node") as X509Certificate)
    }
    /** Actual disk/TLS protocol profile on this host, never a phone/radio benchmark. */
    fun profile(size: Long = 100_000_000L, repetitions: Int = 1) {
        require(size > 0 && repetitions in 1..10)
        val root = Files.createTempDirectory("flashlink-profile-").toFile()
        try {
            val source = File(root,"source.bin")
            FileOutputStream(source).use { out ->
                val block = ByteArray(262144) { (it%239).toByte() }; var remaining = size
                while(remaining > 0) { val count = minOf(remaining,block.size.toLong()).toInt(); out.write(block,0,count); remaining -= count }; out.fd.sync()
            }
            val digest = source.inputStream().use { FileValidation.hash(it,size).first }
            val a = keys(root,"profile-a"); val b = keys(root,"profile-b")
            val tls = SecureTransport.context(a.first,SecureTransport.fingerprint(b.second)) to SecureTransport.context(b.first,SecureTransport.fingerprint(a.second))
            for(buffer in Protocol.buffers) repeat(repetitions) { run ->
                val meta = FileMeta(Protocol.id(),"profile.bin",size,"application/octet-stream",digest)
                val manifest = Manifest(Protocol.id(),listOf(meta)); val folder = File(root,"parts").apply { mkdirs() }
                Link(manifest,folder,tls=tls,buffer=buffer).use { link ->
                    link.sender.send(manifest,listOf(Source(meta) { source.inputStream() }))
                    waitFor(link.received) { it["status"] == "waitingForApproval" }
                    val tick = System.nanoTime(); link.receiver.accept()
                    val sent = waitFor(link.sent) { it["status"] == "completed" }
                    waitFor(link.received) { it["status"] == "completed" }
                    val seconds = (System.nanoTime()-tick)/1e9
                    check(File(folder,meta.id).inputStream().use { FileValidation.hash(it,size).first }.contentEquals(digest))
                    println("HOST DISK + PINNED TLS LOOPBACK ONLY bytes=$size buffer=$buffer run=${run+1} seconds=$seconds decimalMBps=${size/seconds/1e6} metrics=${sent["metrics"]}")
                }
                folder.deleteRecursively()
            }
        } finally { root.deleteRecursively() }
    }
    @JvmStatic fun main(args: Array<String>) {
        if(args.firstOrNull() == "--profile") { profile(args.getOrNull(1)?.toLong() ?: 100_000_000L,args.getOrNull(2)?.toInt() ?: 1); return }

        throughputOnly = args.contains("--throughput-only")
        val root = Files.createTempDirectory("flashlink-recovery-").toFile()
        try {
            val original = File(root,"source.bin")
            FileOutputStream(original).use { out -> val buffer = ByteArray(262144) { (it % 239).toByte() }; repeat(128) { out.write(buffer) } }
            val meta = FileMeta(Protocol.id(),"source.bin",original.length(),"application/octet-stream",FileInputStream(original).use { FileValidation.hash(it,original.length()).first })
            val manifest = Manifest(Protocol.id(),listOf(meta)); val partial = File(root,"partial").apply { mkdirs() }
            var checkpoint = 0L
            test("pause persists a nonzero checkpoint; restart resumes without byte-zero retransmission") {
                Link(manifest,partial).use { link ->
                    link.sender.send(manifest,listOf(source(original,meta,true)))
                    waitFor(link.received) { it["status"] == "waitingForApproval" }; link.receiver.accept()
                    waitFor(link.sent) { (it["bytes"] as? Long ?: 0) > meta.size/3 }
                    link.sender.pause()
                    waitFor(link.sent) { it["status"] == "paused" }; waitFor(link.received) { it["status"] == "paused" }
                }
                val recovered = DiskStore(partial,manifest).resumePoint(); checkpoint = recovered.offset
                check(checkpoint > 0 && checkpoint < meta.size)
                Link(manifest,partial,true).use { link ->
                    link.sender.send(manifest,listOf(source(original,meta)),true)
                    val first = waitFor(link.received) { it["status"] == "transferring" }
                    check(first["bytes"] == checkpoint)
                    waitFor(link.sent) { it["status"] == "completed" }; waitFor(link.received) { it["status"] == "completed" }
                }
                check(FileInputStream(File(partial,meta.id)).use { FileValidation.hash(it,meta.size).first }.contentEquals(meta.sha256))
            }
            test("receiver pause and unexpected socket loss preserve resumable bytes") {
                for(unexpected in listOf(false,true)) {
                    val id = Manifest(Protocol.id(),listOf(meta)); val folder = File(root,"loss-$unexpected").apply { mkdirs() }
                    var before = 0L
                    Link(id,folder).use { link ->
                        link.sender.send(id,listOf(source(original,meta,true)))
                        waitFor(link.received) { it["status"] == "waitingForApproval" }; link.receiver.accept()
                        waitFor(link.received) { (it["bytes"] as? Long ?: 0) > Protocol.CHECKPOINT_BYTES }
                        if(unexpected) link.receiver.interrupt("Test socket interruption") else link.receiver.pause()
                        waitFor(link.received) { it["status"] == if(unexpected) "interrupted" else "paused" }
                    }
                    before = DiskStore(folder,id).resumePoint().offset
                    check(before > 0 && before < meta.size)
                    Link(id,folder,true).use { link ->
                        link.sender.send(id,listOf(source(original,meta)),true)
                        val resumed = waitFor(link.received) { it["status"] == "transferring" }; check(resumed["bytes"] == before)
                        waitFor(link.sent) { it["status"] == "completed" }; waitFor(link.received) { it["status"] == "completed" }
                    }
                }
            }
            test("replayed original approval cannot authorize another OFFER after restart") {
                Link(manifest,partial,true).use { link ->
                    link.sender.send(manifest,listOf(source(original,meta)))
                    waitFor(link.sent) { it["status"] == "cancelled" }
                }
                val receipt = CompletedReceiptStore(manifest)
                check(receipt.resumePoint().completed == manifest.files.size)
                invalid { receipt.open(meta) }; invalid { receipt.commit(meta) }
            }
            test("modified and missing sources cannot resume") {
                val changed = File(root,"changed"); original.copyTo(changed)
                RandomAccessFile(changed,"rw").use { it.seek(0); it.writeByte(99) }
                invalid { FileValidation.source(source(changed,meta)) }
                invalid { FileValidation.source(source(original,meta),1,ByteArray(32)) }
                changed.delete(); invalid { FileValidation.source(source(changed,meta)) }
            }
            test("invalid offsets and corrupted/missing partials rejected") {
                invalid { ResumePoint(0,meta.size+1,ByteArray(32)).validate(manifest) }
                invalid { ResumePoint(-1,0,ByteArray(32)).validate(manifest) }
                val bad = File(root,"corrupt").apply { mkdirs() }
                partial.copyRecursively(bad,overwrite=true)
                RandomAccessFile(File(bad,meta.id),"rw").use { it.seek(0); it.writeByte(99) }
                invalid { DiskStore(bad,manifest).resumePoint() }
                File(bad,meta.id).delete(); invalid { DiskStore(bad,manifest).resumePoint() }
            }
            test("unauthorized manifest resume and duplicate offsets fail closed") {
                val altered = Manifest(manifest.id,listOf(meta.copy(name="another.bin")))
                Link(manifest,partial,true).use { link ->
                    link.sender.send(altered,listOf(source(original,altered.files.single())),true)
                    waitFor(link.sent) { it["status"] == "failed" }
                }
                invalid { CheckpointValidation.verify(original,checkpoint,listOf(ChunkCheckpoint(1,checkpoint,ByteArray(32)))) }
            }
            test("pairing pins require complete fingerprints and attempt budget expires") {
                invalid { SecureTransport.normalize("123456") }
                check(SecureTransport.normalize("flashlink:cert:"+"AB".repeat(32)) == "ab".repeat(32))
                var now = 0L; val budget = RetryBudget { now }
                repeat(5) { check(budget.take()) }; check(!budget.take()); now = 60_000_000_000L; check(budget.take())
            }
            val a = keys(root,"a"); val b = keys(root,"b")
            test("mutual pinned TLS encrypts a real socket and authenticates BOTH certificates") {
                val serverContext = SecureTransport.context(a.first,SecureTransport.fingerprint(b.second))
                val clientContext = SecureTransport.context(b.first,SecureTransport.fingerprint(a.second))
                val server = serverContext.serverSocketFactory.createServerSocket(0,1,InetAddress.getLoopbackAddress()) as SSLServerSocket
                server.needClientAuth = true
                val executor = Executors.newSingleThreadExecutor()
                val result = executor.submit<Boolean> {
                    (server.accept() as SSLSocket).use { socket ->
                        SecureTransport.authenticate(socket)
                        check(SecureTransport.fingerprint(socket.session.peerCertificates[0] as X509Certificate) == SecureTransport.fingerprint(b.second))
                        check(!socket.session.cipherSuite.contains("NULL"))
                        val bytes = ByteArray(20); java.io.DataInputStream(socket.inputStream).readFully(bytes)
                        socket.outputStream.write(bytes); true
                    }
                }
                try {
                    (clientContext.socketFactory.createSocket(InetAddress.getLoopbackAddress(),server.localPort) as SSLSocket).use { socket ->
                        SecureTransport.authenticate(socket)
                        check(SecureTransport.fingerprint(socket.session.peerCertificates[0] as X509Certificate) == SecureTransport.fingerprint(a.second))
                        val bytes = ByteArray(20) { it.toByte() }; socket.outputStream.write(bytes)
                        val received = ByteArray(20); DataInputStream(socket.inputStream).readFully(received); check(received.contentEquals(bytes))
                    }
                    check(result.get(20,TimeUnit.SECONDS))
                } finally { server.close(); executor.shutdownNow() }
            }
            test("authenticated encrypted transfer pauses and resumes across fresh TLS sessions") {
                val contexts = SecureTransport.context(a.first,SecureTransport.fingerprint(b.second)) to SecureTransport.context(b.first,SecureTransport.fingerprint(a.second))
                val secureManifest = Manifest(Protocol.id(),listOf(meta)); val folder = File(root,"encrypted-parts").apply { mkdirs() }
                Link(secureManifest,folder,tls=contexts).use { link ->
                    link.sender.send(secureManifest,listOf(source(original,meta,true)))
                    waitFor(link.received) { it["status"] == "waitingForApproval" }; link.receiver.accept()
                    waitFor(link.sent) { (it["bytes"] as? Long ?: 0L) > meta.size/3 }
                    link.sender.pause(); waitFor(link.sent) { it["status"] == "paused" }; waitFor(link.received) { it["status"] == "paused" }
                }
                val start = DiskStore(folder,secureManifest).resumePoint().offset
                check(start > 0 && start < meta.size)
                Link(secureManifest,folder,true,contexts).use { link ->
                    val time = System.nanoTime()
                    link.sender.send(secureManifest,listOf(source(original,meta)),true)
                    waitFor(link.sent) { it["status"] == "completed" }; waitFor(link.received) { it["status"] == "completed" }
                    val elapsed = (System.nanoTime()-time)/1e9
                    println("ENCRYPTED HOST LOOPBACK ONLY resumedBytes=${meta.size-start} elapsedSeconds=$elapsed decimalMBps=${(meta.size-start)/elapsed/1e6}")
                }
                check(FileInputStream(File(folder,meta.id)).use { FileValidation.hash(it,meta.size).first }.contentEquals(meta.sha256))
            }
            test("wrong certificate pin cannot establish an encrypted application session") {
                val server = SecureTransport.context(a.first,"00".repeat(32)).serverSocketFactory.createServerSocket(0,1,InetAddress.getLoopbackAddress()) as SSLServerSocket
                server.needClientAuth = true; val executor = Executors.newSingleThreadExecutor()
                val rejected = executor.submit<Boolean> { (server.accept() as SSLSocket).use { runCatching { SecureTransport.authenticate(it) }.isFailure } }
                try {
                    (SecureTransport.context(b.first,SecureTransport.fingerprint(a.second)).socketFactory.createSocket(InetAddress.getLoopbackAddress(),server.localPort) as SSLSocket).use { runCatching { SecureTransport.authenticate(it) } }
                    check(rejected.get(20,TimeUnit.SECONDS))
                } finally { server.close(); executor.shutdownNow() }
            }
            test("unapproved server fingerprint is rejected with no plaintext fallback") {
                val server = SecureTransport.context(a.first,SecureTransport.fingerprint(b.second)).serverSocketFactory.createServerSocket(0,1,InetAddress.getLoopbackAddress()) as SSLServerSocket
                server.needClientAuth = true; val executor = Executors.newSingleThreadExecutor()
                val accepted = executor.submit { (server.accept() as SSLSocket).use { runCatching { SecureTransport.authenticate(it) } } }
                try {
                    (SecureTransport.context(b.first,"00".repeat(32)).socketFactory.createSocket(InetAddress.getLoopbackAddress(),server.localPort) as SSLSocket).use { socket ->
                        invalid { SecureTransport.authenticate(socket) }
                    }
                    accepted.get(20,TimeUnit.SECONDS)
                } finally { server.close(); executor.shutdownNow() }
            }
            println("$count recovery/security integration tests passed. Android Keystore/SQLite/foreground-service device tests remain separate.")
        } finally { root.deleteRecursively() }
    }
}
