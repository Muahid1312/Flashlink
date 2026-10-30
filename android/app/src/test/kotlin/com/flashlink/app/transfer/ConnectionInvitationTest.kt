package com.flashlink.app.transfer

import com.flashlink.app.WifiDirectPolicy
import com.flashlink.app.connection.LocalRoute
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.*
import javax.net.ssl.*

class ConnectionInvitationTest {
    private fun ticket() = ConnectionInvitation.create("192.168.4.1","a".repeat(64),"Phone & One")
    @Test fun invitationRoundTripsAndExpiresWithoutTrustingDiscoveredIdentity() {
        val invite = ticket()
        assertEquals(invite,ConnectionInvitation.parse(invite.encode()))
        assertTrue(runCatching { ConnectionInvitation.parse(invite.encode(),invite.expires) }.isFailure)
        assertTrue(runCatching { ConnectionInvitation.parse(invite.encode()+"&pin="+"b".repeat(64)) }.isFailure)
        assertTrue(runCatching { ConnectionInvitation.parse(invite.encode().replace("flashlink://pair","https://pair")) }.isFailure)
        assertTrue(runCatching { invite.copy(expires=System.currentTimeMillis()+600_000).validate() }.isFailure)
    }
    @Test fun localEndpointsRejectInternetDnsLoopbackAmbiguityAndInvalidPorts() {
        for(ip in listOf("8.8.8.8","127.0.0.1","0.0.0.0","224.0.0.1","example.com","192.168.004.1","192.168.4.999","::1"))
            assertTrue(ip,runCatching { ticket().copy(host=ip).validate() }.isFailure)
        assertTrue(runCatching { ticket().copy(port=0).validate() }.isFailure)
        assertTrue(LocalRoute("192.168.4.7",24,"wlan0",null).contains("192.168.4.1"))
        assertFalse(LocalRoute("192.168.4.7",24,"wlan0",null).contains("192.168.5.1"))
    }
    @Test fun leaseCannotBeReplayedOrExtendedByWallClockChanges() {
        var tick = 0L; val invite = ticket(); val lease = InvitationLease(invite) { tick }
        assertFalse(lease.claim(Protocol.id())); assertTrue(lease.claim(invite.id)); assertFalse(lease.claim(invite.id))
        val second = InvitationLease(invite) { tick }; tick = ConnectionInvitation.LIFETIME*1_000_000+1
        assertFalse(second.claim(invite.id)); assertEquals(0,second.remainingMillis())
    }
    @Test fun androidPermissionsAndRecoveryAreVersionSpecificAndBounded() {
        assertArrayEquals(arrayOf("android.permission.ACCESS_FINE_LOCATION"),WifiDirectPolicy.permissions(30))
        assertArrayEquals(arrayOf("android.permission.ACCESS_FINE_LOCATION","android.permission.ACCESS_COARSE_LOCATION"),WifiDirectPolicy.permissions(31))
        assertArrayEquals(arrayOf("android.permission.NEARBY_WIFI_DEVICES"),WifiDirectPolicy.permissions(33))
        assertEquals("ERROR",WifiDirectPolicy.reason(0)); assertEquals("BUSY",WifiDirectPolicy.reason(2))
        assertTrue(WifiDirectPolicy.retry(2,1)); assertTrue(WifiDirectPolicy.retry(2,2)); assertFalse(WifiDirectPolicy.retry(2,3))
        assertTrue(WifiDirectPolicy.retry(0,1)); assertFalse(WifiDirectPolicy.retry(0,2)); assertFalse(WifiDirectPolicy.retry(1,1))
        assertFalse(WifiDirectPolicy.retry(99,1)); assertTrue(WifiDirectPolicy.detail("discoverPeers",0,1).contains("ERROR (0)"))
    }
    private fun keys(root: File, name: String): Pair<Array<KeyManager>,X509Certificate> {
        val file = File(root,"$name.p12"); val password = Protocol.id()
        val process = ProcessBuilder(File(System.getProperty("java.home"),"bin/keytool").absolutePath,"-genkeypair","-alias","node","-keyalg","EC","-groupname","secp256r1","-validity","2","-dname","CN=$name","-storetype","PKCS12","-keystore",file.path,"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
        val store = KeyStore.getInstance("PKCS12"); file.inputStream().use { store.load(it,password.toCharArray()) }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); factory.init(store,password.toCharArray())
        return factory.keyManagers to (store.getCertificate("node") as X509Certificate)
    }
    private fun tlsPair(onboarding: Boolean = false, test: (SSLContext, SSLContext, String)->Unit) {
        val root = Files.createTempDirectory("flashlink-admission-test").toFile()
        try {
            val host = keys(root,"host"); val peer = keys(root,"peer")
            val pin = SecureTransport.fingerprint(host.second)
            test(if(onboarding) SecureTransport.pairingContext(host.first) else SecureTransport.context(host.first,SecureTransport.fingerprint(peer.second)),SecureTransport.context(peer.first,pin),pin)
        } finally { root.deleteRecursively() }
    }
    @Test(timeout=30_000) fun authenticatedInstancesConfirmThenExchangeRealBytesAndRejectReplay() = tlsPair { host,client,pin ->
        val invite = ticket().copy(fingerprint=pin); val lease = InvitationLease(invite)
        for(replay in listOf(false,true)) {
            val listener = host.serverSocketFactory.createServerSocket(0,1,InetAddress.getLoopbackAddress()) as SSLServerSocket
            listener.needClientAuth = true; listener.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Boolean> {
                    (listener.accept() as SSLSocket).use { socket ->
                        SecureTransport.authenticate(socket)
                        val admission = runCatching { ConnectionHandshake.host(socket,lease) { name -> assertEquals("Client",name); true } }
                        if(replay) admission.isFailure else {
                            admission.getOrThrow(); val data = DataInputStream(socket.inputStream); assertEquals(0x12345678,data.readInt())
                            DataOutputStream(socket.outputStream).apply { writeInt(0x76543210); flush() }; true
                        }
                    }
                }
                (client.socketFactory.createSocket(InetAddress.getLoopbackAddress(),listener.localPort) as SSLSocket).use { socket ->
                    SecureTransport.authenticate(socket)
                    if(replay) assertTrue(runCatching { ConnectionHandshake.client(socket,invite,"Client") }.isFailure)
                    else {
                        ConnectionHandshake.client(socket,invite,"Client")
                        DataOutputStream(socket.outputStream).apply { writeInt(0x12345678); flush() }
                        assertEquals(0x76543210,DataInputStream(socket.inputStream).readInt())
                    }
                }
                assertTrue(result.get(10,TimeUnit.SECONDS))
            } finally { listener.close(); executor.shutdownNow() }
        }
    }
    @Test(timeout=30_000) fun explicitRejectionPreventsApplicationProtocolFromStarting() = tlsPair(onboarding=true) { host,client,pin ->
        val invite = ticket().copy(fingerprint=pin)
        val listener = host.serverSocketFactory.createServerSocket(0,1,InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true; val executor = Executors.newSingleThreadExecutor()
        try {
            val rejected = executor.submit<Boolean> {
                (listener.accept() as SSLSocket).use { socket ->
                    SecureTransport.authenticate(socket)
                    runCatching { ConnectionHandshake.host(socket,InvitationLease(invite)) { false } }.isFailure
                }
            }
            (client.socketFactory.createSocket(InetAddress.getLoopbackAddress(),listener.localPort) as SSLSocket).use { socket ->
                SecureTransport.authenticate(socket)
                assertTrue(runCatching { ConnectionHandshake.client(socket,invite,"Client") }.isFailure)
            }
            assertTrue(rejected.get(10,TimeUnit.SECONDS))
        } finally { listener.close(); executor.shutdownNow() }
    }
    @Test(timeout=30_000) fun firstTimePairingComparesCommittedCodesThenStreamsAndSavesARealFile() = tlsPair(onboarding=true) { host,client,pin ->
        val root = Files.createTempDirectory("flashlink-first-pair").toFile()
        val destination = File(root,"received.bin")
        val source = File(root,"source.bin").apply { writeBytes(ByteArray(65_539) { (it % 251).toByte() }) }
        val digest = FileValidation.hash(source.inputStream(),source.length()).first
        val invite = ticket().copy(fingerprint=pin)
        val listener = host.serverSocketFactory.createServerSocket(0,1,InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true; listener.soTimeout = 5000
        val executor = Executors.newSingleThreadExecutor()
        val admit = CountDownLatch(1); val codePublished = CountDownLatch(1); val ready = CountDownLatch(2)
        val offered = CountDownLatch(1); val completed = CountDownLatch(2)
        val hostCode = java.util.concurrent.atomic.AtomicReference<String>()
        val clientCode = java.util.concurrent.atomic.AtomicReference<String>()
        val receiver = java.util.concurrent.atomic.AtomicReference<TransferSession>()
        val store = object: ReceiveStore {
            override fun open(file: FileMeta): OutputStream = destination.outputStream()
            override fun commit(file: FileMeta) { assertEquals(file.size,destination.length()) }
            override fun rollback(): Boolean = destination.delete() || !destination.exists()
        }
        try {
            val accepted = executor.submit<TransferSession> {
                val socket = listener.accept() as SSLSocket
                try {
                    SecureTransport.authenticate(socket)
                    ConnectionHandshake.host(socket,InvitationLease(invite),pin,SecureTransport.peerFingerprint(socket),{ hostCode.set(it); codePublished.countDown() }) {
                        assertTrue(admit.await(5,TimeUnit.SECONDS)); hostCode.get() == clientCode.get()
                    }
                    TransferSession(socket,"Receiver",{store},{ event ->
                        if(event["status"] == "waitingForApproval") offered.countDown()
                        if(event["status"] == "completed") { assertEquals(true,event["verified"]); completed.countDown() }
                    },{ ok,_ -> if(ok) ready.countDown() }).also { receiver.set(it); it.start() }
                } catch(e:Throwable) { socket.close(); throw e }
            }
            val socket = client.socketFactory.createSocket(InetAddress.getLoopbackAddress(),listener.localPort) as SSLSocket
            SecureTransport.authenticate(socket)
            ConnectionHandshake.client(socket,invite,"Sender",SecureTransport.fingerprint(socket.session.localCertificates.single() as X509Certificate),pin) {
                clientCode.set(it); assertTrue(codePublished.await(5,TimeUnit.SECONDS))
                assertEquals(hostCode.get(),it); assertTrue(it.matches(Regex("[0-9]{6}")))
                assertNull(receiver.get()); assertFalse(destination.exists()); admit.countDown()
            }
            val sender = TransferSession(socket,"Sender",{error("Sender must not write")},{ event ->
                if(event["status"] == "completed") { assertEquals(true,event["verified"]); completed.countDown() }
            },{ ok,_ -> if(ok) ready.countDown() })
            try {
                sender.start(); assertTrue(ready.await(5,TimeUnit.SECONDS))
                val meta = FileMeta(Protocol.id(),"received.bin",source.length(),"application/octet-stream",digest)
                sender.send(Manifest(Protocol.id(),listOf(meta)),listOf(Source(meta) { source.inputStream() }))
                assertTrue(offered.await(5,TimeUnit.SECONDS)); assertFalse(destination.exists())
                accepted.get(5,TimeUnit.SECONDS).accept()
                assertTrue(completed.await(10,TimeUnit.SECONDS))
                assertArrayEquals(source.readBytes(),destination.readBytes())
                assertArrayEquals(digest,FileValidation.hash(destination.inputStream(),destination.length()).first)
            } finally { sender.close() }
        } finally { admit.countDown(); receiver.get()?.close(); listener.close(); executor.shutdownNow(); root.deleteRecursively() }
    }
    @Test(timeout=10_000) fun alteredCommittedNonceIsRejectedBeforeUserApproval() {
        val invite = ticket()
        val listener = java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor(); var confirmed = false
        try {
            val rejected = executor.submit<Boolean> { listener.accept().use { socket ->
                runCatching { ConnectionHandshake.host(socket,InvitationLease(invite)) { confirmed = true; true } }.isFailure
            } }
            java.net.Socket(InetAddress.getLoopbackAddress(),listener.localPort).use { socket ->
                socket.soTimeout = 3000
                val input = DataInputStream(socket.inputStream); val out = DataOutputStream(socket.outputStream)
                val magic = input.readInt(); val id = input.readUTF(); input.readFully(ByteArray(32))
                out.writeInt(magic); out.writeUTF(id); out.writeUTF("Tampered")
                out.write(java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(32))); out.flush()
                input.readFully(ByteArray(32)); out.write(ByteArray(32) { 1 }); out.flush()
                assertTrue(rejected.get(5,TimeUnit.SECONDS)); assertFalse(confirmed)
            }
        } finally { listener.close(); executor.shutdownNow() }
    }
    @Test fun comparisonCodeBindsBothCertificatesSessionAndBothNonces() {
        val id = "00000000-0000-4000-8000-000000000001"; val a = "a".repeat(64); val b = "b".repeat(64)
        val host = ByteArray(32); val client = ByteArray(32) { 1 }
        val code = SecureTransport.pairingCode(id,a,b,host,client)
        assertEquals(code,SecureTransport.pairingCode(id,b,a,host,client))
        assertNotEquals(code,SecureTransport.pairingCode(id,a,b,client,host))
        assertNotEquals(code,SecureTransport.pairingCode(id,a,"c".repeat(64),host,client))
        assertTrue(runCatching { SecureTransport.pairingCode(id,a,b,ByteArray(1),client) }.isFailure)
    }
    @Test fun socketFailuresGiveActionableStageSpecificReasons() {
        assertTrue(ConnectionErrors.describe(java.net.ConnectException("ECONNREFUSED")).contains("Connection refused"))
        assertTrue(ConnectionErrors.describe(java.net.SocketTimeoutException()).contains("Connection timed out"))
        assertTrue(ConnectionErrors.describe(java.net.SocketTimeoutException(),true).contains("invitation expired"))
        assertTrue(ConnectionErrors.describe(java.net.NoRouteToHostException()).contains("same reachable network"))
        assertTrue(ConnectionErrors.describe(SSLHandshakeException("pin mismatch")).contains("Authentication failed"))
        assertTrue(ConnectionErrors.describe(java.net.BindException()).contains("listener could not start"))
    }

    @Test fun localRoutesUseActualAddressesAndTheMostSpecificSubnet() {
        val upstream = LocalRoute("192.168.1.3",16,"wlan0",null)
        val hotspot = LocalRoute("192.168.43.1",24,"br0",null)
        assertEquals(hotspot,com.flashlink.app.connection.LocalNetworks.peerRoute(listOf(upstream,hotspot),"192.168.43.12"))
        assertEquals(hotspot,com.flashlink.app.connection.LocalNetworks.hostRoute(listOf(upstream,hotspot),hotspot.address))
        assertTrue(runCatching { com.flashlink.app.connection.LocalNetworks.peerRoute(listOf(hotspot),hotspot.address) }.isFailure)
        assertTrue(runCatching { com.flashlink.app.connection.LocalNetworks.peerRoute(listOf(hotspot),"10.0.0.4") }.isFailure)
        assertTrue(runCatching { com.flashlink.app.connection.LocalNetworks.hostRoute(listOf(hotspot),"192.168.99.1") }.isFailure)
    }

}
