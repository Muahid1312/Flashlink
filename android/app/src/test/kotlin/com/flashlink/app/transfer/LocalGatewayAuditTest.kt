package com.flashlink.app.transfer

import android.app.Activity
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import com.flashlink.app.connection.LocalNetworkGateway
import io.flutter.plugin.common.BinaryMessenger
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowNsdManager
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE,shadows=[LocalGatewayAuditTest.RecordingNsd::class])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalGatewayAuditTest {
    @Implements(NsdManager::class)
    class RecordingNsd: ShadowNsdManager() {
        @Implementation override fun resolveService(info: NsdServiceInfo, listener: NsdManager.ResolveListener) { resolutions.add(info to listener) }
        @Implementation override fun registerService(info: NsdServiceInfo, protocol: Int, listener: NsdManager.RegistrationListener) { registrations++ }
        @Implementation override fun unregisterService(listener: NsdManager.RegistrationListener) { unregistrations++ }
        companion object {
            val resolutions = mutableListOf<Pair<NsdServiceInfo,NsdManager.ResolveListener>>()
            var registrations = 0
            var unregistrations = 0
        }
    }
    private val messenger = object: BinaryMessenger {
        override fun send(channel: String, message: ByteBuffer?) { }
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) { }
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) { }
    }
    private lateinit var activity: Activity
    private lateinit var gateway: LocalNetworkGateway
    private lateinit var runtime: TransferRuntime
    @Before fun setup() {
        RecordingNsd.resolutions.clear(); RecordingNsd.registrations = 0; RecordingNsd.unregistrations = 0
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        runtime = TransferRuntime.get(activity)
        runtime.work.submit { }.get(5,TimeUnit.SECONDS)
        gateway = LocalNetworkGateway(activity,messenger); gateway.resume()
    }
    @After fun teardown() {
        gateway.dispose(); runtime.work.shutdownNow(); runtime.metadata.shutdownNow(); runtime.ledger.close()
        TransferRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null,null)
        activity.finish()
    }
    private fun field(name: String) = LocalNetworkGateway::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(name: String) = LocalNetworkGateway::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(gateway)
    @Suppress("UNCHECKED_CAST") private fun resolve(name: String) {
        (field("queue").get(gateway) as ArrayDeque<NsdServiceInfo>).addLast(NsdServiceInfo().apply { serviceName = name; serviceType = LocalNetworkGateway.TYPE })
        LocalNetworkGateway::class.java.getDeclaredMethod("resolveNext",Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(gateway,field("generation").getInt(gateway))
    }
    @Test fun stalledResolutionTimesOutAndAllowsAnotherAttempt() {
        resolve("stalled")
        assertTrue(field("resolving").getBoolean(gateway))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(field("resolving").getBoolean(gateway))
        assertTrue(field("message").get(gateway).toString().contains("timed out"))
        resolve("retry"); assertEquals(2,RecordingNsd.resolutions.size)
    }
    @Test fun lateResolverCallbackCannotClearANewerResolution() {
        resolve("old"); val old = RecordingNsd.resolutions.single()
        invoke("stopDiscovery"); resolve("new")
        old.second.onResolveFailed(old.first,0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(field("resolving").getBoolean(gateway))
        val current = RecordingNsd.resolutions.last(); current.second.onResolveFailed(current.first,0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(field("resolving").getBoolean(gateway))
    }
    @Test fun returningToAnOpenListenerRestoresItsAnnouncement() {
        TransferRuntime::class.java.getDeclaredField("connectionMethod").apply { isAccessible = true }.set(runtime,"localWifi")
        runtime.update(mapOf("status" to "connecting","listenerReady" to true,"ready" to false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        field("hosting").set(gateway,ConnectionInvitation.create("192.168.43.1","a".repeat(64),"Phone"))
        gateway.resume(); assertEquals(1,RecordingNsd.registrations)
        gateway.suspend(); assertEquals(1,RecordingNsd.unregistrations)
        gateway.resume(); assertEquals(2,RecordingNsd.registrations)
    }
    private fun publish(snapshot: Map<String,Any?>) {
        TransferRuntime::class.java.getDeclaredMethod("publishSession",Int::class.javaPrimitiveType,Map::class.java).apply { isAccessible = true }.invoke(runtime,0,snapshot)
    }
    @Test fun completionThatWinsACancelCannotResurrectTheClosedConnection() {
        val id = Protocol.id()
        TransferRuntime::class.java.getDeclaredField("generation").apply { isAccessible = true }.setInt(runtime,1)
        runtime.update(mapOf("id" to id,"status" to "cancelled","ready" to false,"secure" to false))
        publish(mapOf("id" to id,"status" to "completed","verified" to true,"ready" to true,"secure" to true))
        assertEquals("completed",runtime.latest["status"]); assertEquals(true,runtime.latest["verified"])
        assertEquals(false,runtime.latest["ready"]); assertEquals(false,runtime.latest["secure"])
    }
    @Test fun oldCompletionCannotOverwriteANewerJob() {
        val id = Protocol.id()
        TransferRuntime::class.java.getDeclaredField("generation").apply { isAccessible = true }.setInt(runtime,1)
        runtime.update(mapOf("id" to id,"status" to "transferring","ready" to true,"secure" to true))
        publish(mapOf("id" to Protocol.id(),"status" to "completed","ready" to true,"secure" to true))
        assertEquals("transferring",runtime.latest["status"]); assertEquals(id,runtime.latest["id"])
    }
    @Test fun cancelThatWinsSessionStartupCannotLeaveAnOrphanBusySession() {
        val server = java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())
        val client = java.net.Socket(java.net.InetAddress.getLoopbackAddress(),server.localPort)
        val accepted = server.accept(); server.close()
        val closed = java.util.concurrent.CountDownLatch(1)
        val session = TransferSession(client,"Phone",{ error("No file access expected") },{},{ready,_ -> if(!ready) closed.countDown()})
        try {
            TransferRuntime::class.java.getDeclaredField("generation").apply { isAccessible = true }.setInt(runtime,1)
            TransferRuntime::class.java.getDeclaredMethod("startSession",Int::class.javaPrimitiveType,TransferSession::class.java).apply { isAccessible = true }.invoke(runtime,0,session)
            assertTrue(closed.await(5,TimeUnit.SECONDS))
            assertFalse(runtime.connectionBusy)
            assertTrue(session.awaitReleased())
        } finally { session.close(); accepted.close() }
    }
}
