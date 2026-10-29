package com.flashlink.app.transfer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.Looper
import com.flashlink.app.WifiDirectGateway
import io.flutter.plugin.common.BinaryMessenger
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowWifiP2pManager
import java.nio.ByteBuffer
import java.time.Duration

/** Framework callbacks are controlled here; these are lifecycle/regression tests, not radio tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE,shadows=[WifiDirectGatewayTest.RecordingP2p::class,WifiDirectGatewayTest.RecordingChannel::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WifiDirectGatewayTest {
    @Implements(WifiP2pManager.Channel::class)
    class RecordingChannel { @Implementation fun close() { } }
    @Implements(WifiP2pManager::class)
    class RecordingP2p: ShadowWifiP2pManager() {
        @Implementation fun requestPeers(channel: WifiP2pManager.Channel, listener: WifiP2pManager.PeerListListener) { }
        @Implementation override fun requestConnectionInfo(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ConnectionInfoListener) {
            listener.onConnectionInfoAvailable(WifiP2pInfo())
        }
        @Implementation fun stopPeerDiscovery(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener?) {
            calls.add("stop"); stop = listener
        }
        @Implementation override fun connect(channel: WifiP2pManager.Channel, config: WifiP2pConfig, listener: WifiP2pManager.ActionListener) {
            calls.add("connect"); listener.onSuccess()
        }
        companion object {
            val calls = mutableListOf<String>()
            var stop: WifiP2pManager.ActionListener? = null
        }
    }
    private val messenger = object: BinaryMessenger {
        override fun send(channel: String, message: ByteBuffer?) { }
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) { }
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) { }
    }
    private lateinit var activity: Activity
    private lateinit var gateway: WifiDirectGateway
    @Before fun setup() {
        RecordingP2p.calls.clear(); RecordingP2p.stop = null
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Shadows.shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_DIRECT,true)
        Shadows.shadowOf(activity.application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        (activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = true
        Shadows.shadowOf(activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager).setLocationEnabled(true)
        gateway = WifiDirectGateway(activity,messenger); gateway.resume()
        WifiDirectGateway::class.java.getDeclaredField("peers").apply { isAccessible = true }
            .set(gateway,listOf(mapOf("address" to "02:00:00:00:00:01","status" to WifiP2pDevice.AVAILABLE)))
    }
    @After fun teardown() { gateway.dispose(); activity.finish() }
    private fun connect() {
        WifiDirectGateway::class.java.getDeclaredMethod("connect",String::class.java).apply { isAccessible = true }.invoke(gateway,"02:00:00:00:00:01")
    }
    @Test fun negotiationWaitsForDiscoveryStopAcknowledgement() {
        connect(); assertEquals(listOf("stop"),RecordingP2p.calls)
        RecordingP2p.stop!!.onSuccess()
        assertEquals(listOf("stop","connect"),RecordingP2p.calls)
        assertFalse(gateway.currentSnapshot()["phase"] == "connected")
    }
    @Test fun busyRetriesStopAtThreeAndPreserveFrameworkFailureDetails() {
        connect(); RecordingP2p.stop!!.onFailure(WifiP2pManager.BUSY)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        RecordingP2p.stop!!.onFailure(WifiP2pManager.BUSY)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        RecordingP2p.stop!!.onFailure(WifiP2pManager.BUSY)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(listOf("stop","stop","stop"),RecordingP2p.calls)
        val state = gateway.currentSnapshot()
        assertEquals("failed",state["phase"])
        assertTrue(state["message"].toString().contains("BUSY (2)"))
        assertEquals(3,(state["failure"] as Map<*,*>)["attempt"])
    }
    @Test fun permissionRevocationClearsPeerData() {
        Shadows.shadowOf(activity.application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gateway.onPermissionResult(WifiDirectGateway.PERMISSION_REQUEST)
        assertEquals("permissionRequired",gateway.currentSnapshot()["phase"])
        assertEquals(emptyList<Any>(),gateway.currentSnapshot()["peers"])
    }
}
