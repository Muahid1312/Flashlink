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
    private fun tlsPair(test: (SSLContext, SSLContext, String)->Unit) {
        val root = Files.createTempDirectory("flashlink-admission-test").toFile()
        try {
            val host = keys(root,"host"); val peer = keys(root,"peer")
            val pin = SecureTransport.fingerprint(host.second)
            test(SecureTransport.context(host.first,SecureTransport.fingerprint(peer.second)),SecureTransport.context(peer.first,pin),pin)
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
    @Test(timeout=30_000) fun explicitRejectionPreventsApplicationProtocolFromStarting() = tlsPair { host,client,pin ->
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
}
