package com.flashlink.app.transfer

import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import com.flashlink.app.transfer.Protocol.text

/** Executable JVM tests use actual loopback sockets and production protocol/streaming code. */
object ProtocolIntegration {
    private var tests = 0
    private fun test(name: String, block: () -> Unit) { block(); tests++; println("PASS $name") }
    private fun invalid(block: () -> Unit) { check(runCatching(block).isFailure) }
    private class Generated(private var remaining: Long) : InputStream() {
        override fun read(): Int = if(remaining-- > 0) 42 else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if(remaining <= 0) return -1
            val count = minOf(remaining,length.toLong()).toInt(); java.util.Arrays.fill(buffer,offset,offset+count,42.toByte())
            remaining -= count; return count
        }
    }
    private class Store : ReceiveStore {
        var bytes = 0L; var commits = 0; var rolledBack = false
        override fun open(file: FileMeta) = object : OutputStream() {
            override fun write(value: Int) { bytes++ }
            override fun write(value: ByteArray, offset: Int, length: Int) { bytes += length }
        }
        override fun commit(file: FileMeta) { commits++ }
        override fun rollback(): Boolean { rolledBack = true; return true }
    }
    private class Pairing(buffer: Int = 262144) : Closeable {
        val aEvents = LinkedBlockingQueue<Map<String,Any?>>()
        val bEvents = LinkedBlockingQueue<Map<String,Any?>>()
        val storeA = Store(); val storeB = Store()
        val ready = CountDownLatch(2)
        val a: TransferSession; val b: TransferSession
        init {
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress())
            val client = Socket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close()
            a = TransferSession(accepted,"Owner",{storeA},{aEvents.offer(it)},{ok,_ -> if(ok) ready.countDown()},buffer)
            b = TransferSession(client,"Client",{storeB},{bEvents.offer(it)},{ok,_ -> if(ok) ready.countDown()},buffer)
            a.start(); b.start(); check(ready.await(5,TimeUnit.SECONDS))
        }
        override fun close() { a.close(); b.close() }
    }
    private fun await(queue: BlockingQueue<Map<String,Any?>>, status: String): Map<String,Any?> {
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(System.nanoTime()<deadline) {
            val value = queue.poll(1,TimeUnit.SECONDS) ?: continue
            if(value["status"] == status) return value
            check(value["status"] !in listOf("failed","cancelled")) { "Unexpected terminal: $value" }
        }
        error("Timed out waiting for $status")
    }
    private fun sources(sizes: List<Long>) = sizes.map { size -> Source(FileMeta(Protocol.id(),"duplicate.bin",size,"application/octet-stream",if(size <= 16777216) Generated(size).use { FileValidation.hash(it,size).first } else ByteArray(32))) { Generated(size) } }
    @JvmStatic fun main(args: Array<String>) {
        test("framing survives single-byte reads") {
            val bytes = ByteArrayOutputStream(); Wire(ByteArrayInputStream(byteArrayOf()),bytes).write(Protocol.OFFER,byteArrayOf(1,2,3))
            val slow = object : FilterInputStream(ByteArrayInputStream(bytes.toByteArray())) {
                override fun read(b: ByteArray,off: Int,len: Int) = super.read(b,off,minOf(1,len))
            }
            val wire = Wire(slow,ByteArrayOutputStream()); val header = wire.header()
            check(header.kind == Protocol.OFFER && wire.control(header).contentEquals(byteArrayOf(1,2,3)))
        }
        test("truncated and oversized frames rejected") {
            invalid { Wire(ByteArrayInputStream(byteArrayOf(2,0,0)),ByteArrayOutputStream()).header() }
            val bytes = Protocol.payload { writeByte(Protocol.OFFER); writeInt(Protocol.MAX_CONTROL+1) }
            invalid { Wire(ByteArrayInputStream(bytes),ByteArrayOutputStream()).header() }
        }
        test("manifest rejects traversal, negative sizes, duplicates and overflow") {
            val id = Protocol.id()
            for(name in listOf("../x","x/y","x\\y","..","x\u0000y")) invalid { Manifest(id,listOf(FileMeta(Protocol.id(),name,1,""))).validate() }
            invalid { Manifest(id,listOf(FileMeta(Protocol.id(),"x",-1,""))).validate() }
            val file = FileMeta(Protocol.id(),"x",1,"")
            invalid { Manifest(id,listOf(file,file)).validate() }
            invalid { Manifest(id,List(2) { FileMeta(Protocol.id(),"x",Long.MAX_VALUE,"") }).validate() }
        }
        test("64-bit file lengths and bounded UTF-8 round trip") {
            val manifest = Manifest(Protocol.id(),listOf(FileMeta(Protocol.id(),"large.bin",5_000_000_000L,"application/octet-stream")))
            check(Manifest.decode(manifest.encode()) == manifest)
            invalid { Manifest.decode(manifest.encode()+byteArrayOf(0)) }
            invalid { Protocol.decode(byteArrayOf(0,0,0,2,0xc3.toByte(),0x28)) { text() } }
        }
        test("monotonic speed, smoothing and ETA") {
            var now = 0L; val meter = Meter { now }
            check(meter.sample(0,20_000_000)["etaSeconds"] == null)
            now = 1_000_000_000L
            val value = meter.sample(10_000_000,20_000_000)
            check(value["averageBps"] == 10_000_000.0 && value["currentBps"] == 10_000_000.0 && value["etaSeconds"] == 1.0)
            now = 2_000_000_000L; check((meter.sample(15_000_000,20_000_000)["currentBps"] as Double) == 8_250_000.0)
        }
        test("incompatible handshake fails closed before file access") {
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress())
            val raw = Socket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close()
            val rejected = CountDownLatch(1); val store = Store()
            val receiver = TransferSession(accepted,"Receiver",{store},{},{ok,_ -> if(!ok) rejected.countDown()})
            try {
                receiver.start()
                val wire = Wire(raw.getInputStream(),raw.getOutputStream())
                wire.write(Protocol.HELLO,Protocol.payload { writeInt(0x464c4e4b); writeInt(Protocol.VERSION+1); text(Protocol.id(),64); text("Other"); text("sha256,sequential,approval,resume-v2",128); writeBoolean(true) })
                check(rejected.await(5,TimeUnit.SECONDS)); check(store.bytes == 0L && store.commits == 0)
            } finally { receiver.close(); raw.close() }
        }
        for(reverse in listOf(false,true)) test("multiple files including empty; sender role reverse=$reverse") {
            Pairing().use { pair ->
                val sender = if(reverse) pair.b else pair.a; val receiver = if(reverse) pair.a else pair.b
                val sendEvents = if(reverse) pair.bEvents else pair.aEvents; val receiveEvents = if(reverse) pair.aEvents else pair.bEvents
                val sources = sources(listOf(0,1,1048579)); val manifest = Manifest(Protocol.id(),sources.map { it.meta })
                sender.send(manifest,sources); val offer = await(receiveEvents,"waitingForApproval")
                check(offer["bytes"] == 0L); check((if(reverse) pair.storeA else pair.storeB).bytes == 0L)
                receiver.accept(); val sent = await(sendEvents,"completed"); val received = await(receiveEvents,"completed")
                check(sent["verified"] == true && received["verified"] == true && received["bytes"] == manifest.total)
                check((if(reverse) pair.storeA else pair.storeB).commits == 3)
                invalid { sender.send(manifest,sources) }
            }
        }
        test("twenty sequential approved batches reuse one session without stale progress") {
            Pairing().use { pair ->
                repeat(20) {
                    val sources = sources(listOf(0,65537)); val manifest = Manifest(Protocol.id(),sources.map { it.meta })
                    pair.a.send(manifest,sources)
                    check(await(pair.bEvents,"waitingForApproval")["id"] == manifest.id)
                    pair.b.accept()
                    check(await(pair.aEvents,"completed")["id"] == manifest.id)
                    val done = await(pair.bEvents,"completed")
                    check(done["bytes"] == 65537L && done["completedFiles"] == 2 && done["verified"] == true)
                }
                check(pair.storeB.bytes == 20*65537L && pair.storeB.commits == 40)
            }
        }
        test("completion persistence failure cannot send a successful receipt") {
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress())
            val client = Socket(InetAddress.getLoopbackAddress(),server.localPort); val accepted = server.accept(); server.close()
            val ready = CountDownLatch(2); val disconnected = CountDownLatch(1)
            val senderEvents = LinkedBlockingQueue<Map<String,Any?>>()
            val offer = CountDownLatch(1); val senderLost = CountDownLatch(1)
            val sender = TransferSession(client,"Sender",{Store()},{senderEvents.offer(it)},{ok,_ -> if(ok) ready.countDown() else senderLost.countDown()},stallTimeoutSeconds=2)
            val receiver = TransferSession(accepted,"Receiver",{Store()},{ event ->
                if(event["status"] == "waitingForApproval") offer.countDown()
                if(event["status"] == "completed") throw IOException("Injected durable receipt failure")
            },{ok,_ -> if(ok) ready.countDown() else disconnected.countDown()},stallTimeoutSeconds=2)
            try {
                sender.start(); receiver.start(); check(ready.await(5,TimeUnit.SECONDS))
                val sources = sources(listOf(65537)); sender.send(Manifest(Protocol.id(),sources.map { it.meta }),sources)
                check(offer.await(5,TimeUnit.SECONDS)); receiver.accept()
                check(disconnected.await(5,TimeUnit.SECONDS))
                check(senderLost.await(5,TimeUnit.SECONDS))
                check(senderEvents.none { it["status"] == "completed" })
            } finally { sender.close(); receiver.close() }
        }
        test("receiver rejection sends no bytes and cannot restart same job") {
            Pairing().use { pair ->
                val sources = sources(listOf(1000000)); pair.a.send(Manifest(Protocol.id(),sources.map { it.meta }),sources)
                await(pair.bEvents,"waitingForApproval"); pair.b.reject()
                var rejected: Map<String,Any?>? = null
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                while(System.nanoTime()<deadline && rejected == null) {
                    val event = pair.aEvents.poll(100,TimeUnit.MILLISECONDS)
                    if(event?.get("status") == "cancelled") rejected = event
                }
                check(rejected?.get("bytes") == 0L); check(pair.storeB.bytes == 0L)
            }
        }
        test("receiver cancellation while waiting terminates both ends") {
            Pairing().use { pair ->
                val sources = sources(listOf(100)); pair.a.send(Manifest(Protocol.id(),sources.map { it.meta }),sources)
                await(pair.bEvents,"waitingForApproval"); pair.b.cancel()
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                var terminal = false
                while(System.nanoTime()<deadline && !terminal) terminal = pair.aEvents.poll(100,TimeUnit.MILLISECONDS)?.get("status") in listOf("cancelled","failed")
                check(terminal)
            }
        }
        test("SHA-256 mismatch never finalizes received data") {
            val server = ServerSocket(0,1,InetAddress.getLoopbackAddress()); val raw = Socket(InetAddress.getLoopbackAddress(),server.localPort)
            val accepted = server.accept(); server.close(); val events = LinkedBlockingQueue<Map<String,Any?>>()
            val store = Store(); val ready = CountDownLatch(1)
            val receiver = TransferSession(accepted,"Receiver",{store},{events.offer(it)},{ok,_ -> if(ok) ready.countDown()})
            val wire = Wire(raw.getInputStream(),raw.getOutputStream())
            try {
                receiver.start()
                wire.write(Protocol.HELLO,Protocol.payload { writeInt(0x464c4e4b); writeInt(Protocol.VERSION); text(Protocol.id(),64); text("Sender"); text("sha256,sequential,approval,resume-v2",128); writeBoolean(true) })
                wire.control(wire.header()); check(ready.await(5,TimeUnit.SECONDS))
                val manifest = Manifest(Protocol.id(),listOf(FileMeta(Protocol.id(),"empty",0,"")))
                wire.write(Protocol.OFFER,manifest.encode()); await(events,"waitingForApproval"); receiver.accept(); wire.control(wire.header())
                wire.write(Protocol.FILE,Protocol.token(manifest.files.single().id))
                wire.write(Protocol.CHECKPOINT,Protocol.payload { text(manifest.files.single().id,64); writeLong(0); write(ResumePoint.emptyHash()) })
                wire.control(wire.header()); wire.write(Protocol.HASH,ByteArray(32))
                await(events,"failed"); check(!store.rolledBack && store.commits == 0)
            } finally { receiver.close(); raw.close() }
        }
        test("cancellation during streaming closes sockets and rolls back") {
            Pairing().use { pair ->
                val sources = sources(listOf(8L*1024*1024*1024))
                pair.a.send(Manifest(Protocol.id(),sources.map { it.meta }),sources)
                await(pair.bEvents,"waitingForApproval"); pair.b.accept()
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                var copied = false
                while(System.nanoTime()<deadline && !copied) {
                    val update = pair.bEvents.poll(100,TimeUnit.MILLISECONDS)
                    copied = (update?.get("bytes") as? Long ?: 0L) > 0
                }
                check(copied); pair.b.cancel()
                var terminal: Map<String,Any?>? = null
                while(System.nanoTime()<deadline && terminal == null) {
                    val update = pair.bEvents.poll(100,TimeUnit.MILLISECONDS)
                    if(update?.get("status") == "cancelled") terminal = update
                }
                repeat(100) { if(!pair.storeB.rolledBack) Thread.sleep(5) }; check(terminal != null && pair.storeB.rolledBack)
                check((terminal["bytes"] as Long) < sources.single().meta.size)
            }
        }
        for(buffer in Protocol.buffers) test("loopback throughput buffer=$buffer") {
            Pairing(buffer).use { pair ->
                val sources = sources(listOf(8L*1024*1024)); val manifest = Manifest(Protocol.id(),sources.map { it.meta })
                pair.a.send(manifest,sources); await(pair.bEvents,"waitingForApproval")
                val start = System.nanoTime(); pair.b.accept(); val done = await(pair.aEvents,"completed"); await(pair.bEvents,"completed")
                val seconds = (System.nanoTime()-start)/1e9
                println("HOST LOOPBACK ONLY buffer=$buffer verifiedBytes=${done["bytes"]} elapsedSeconds=$seconds decimalMBps=${manifest.total/seconds/1e6}")
            }
        }
        println("$tests tests passed; physical Android Wi-Fi Direct/SAF tests are separate.")
    }
}
