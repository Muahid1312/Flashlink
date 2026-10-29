package com.flashlink.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/** Framework results are the only source of peers and connection metadata. */
class WifiDirectGateway(private val activity: Activity, messenger: BinaryMessenger) : EventChannel.StreamHandler {
    private val methods = MethodChannel(messenger, "app.flashlink/wifi.v1")
    private val events = EventChannel(messenger, "app.flashlink/wifi.events.v1")
    private val handler = Handler(Looper.getMainLooper())
    private val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    private val manager = activity.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var sink: EventChannel.EventSink? = null
    var observer: ((Map<String, Any?>) -> Unit)? = null
    fun currentSnapshot(): Map<String, Any?> = snapshot()
    private var registered = false
    private var foreground = false
    private var disposed = false
    private var phase = if (supported && manager != null) "permissionRequired" else "unsupported"
    private var message: String? = null
    private var peers: List<Map<String, Any?>> = emptyList()
    private var group: Map<String, Any?>? = null
    private var target: String? = null
    private var permissionResult: MethodChannel.Result? = null
    private var epoch = 0
    private var revision = 0
    private var timeout: Runnable? = null
    private var wifiEnabled = false
    private var p2pEnabled: Boolean? = null
    private var observation = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    if (Build.VERSION.SDK_INT >= 29) {
                        manager?.requestP2pState(channel ?: return) { state -> updateEnabled(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) }
                    } else updateEnabled(intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> readPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> reconcile()
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    if (intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED && phase == "discovering") {
                        phase = "idle"; emit()
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> reconcile()
            }
        }
    }

    init {
        methods.setMethodCallHandler { call, result ->
            if (disposed) result.error("CLOSED", "Wi-Fi Direct service closed", null)
            else when (call.method) {
                "snapshot" -> {
                    if (foreground && Build.VERSION.SDK_INT >= 29 && channel != null) {
                        manager?.requestP2pState(channel!!) { enabled -> updateEnabled(enabled == WifiP2pManager.WIFI_P2P_STATE_ENABLED) }
                    }
                    reconcile(); result.success(snapshot())
                }
                "requestPermission" -> requestPermission(result)
                "startDiscovery" -> guarded(result) { startDiscovery() }
                "stopDiscovery" -> guarded(result) { stopDiscovery() }
                "connect" -> {
                    val address = call.argument<String>("address")
                    if (address == null) result.error("INVALID_PEER", "Select a discovered peer", null)
                    else guarded(result) { connect(address) }
                }
                "cancel" -> guarded(result) { disconnect(true) }
                "disconnect" -> guarded(result) { disconnect(false) }
                else -> result.notImplemented()
            }
        }
        events.setStreamHandler(this)
    }

    private fun snapshot(): Map<String, Any?> = mapOf(
        "revision" to revision, "phase" to phase, "message" to message,
        "permissionGranted" to hasPermission(), "wifiEnabled" to wifiEnabled,
        "locationRequired" to (Build.VERSION.SDK_INT < 33), "locationEnabled" to locationEnabled(),
        "peers" to peers, "group" to group, "target" to target,
    )
    private fun emit() { if (!disposed) { revision++; val state = snapshot(); sink?.success(state); observer?.invoke(state) } }
    private fun fail(text: String) { clearTimeout(); phase = if (group != null) "connected" else "failed"; message = text; emit() }
    private fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    private fun hasPermission() = activity.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    private fun locationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return true
        val location = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else
            location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    private fun requestPermission(result: MethodChannel.Result) {
        if (!supported) { result.error("UNSUPPORTED", "Wi-Fi Direct is not supported", null); return }
        if (hasPermission()) { reconcile(); result.success(null); return }
        if (permissionResult != null) { result.error("BUSY", "A permission request is already open", null); return }
        permissionResult = result
        try { activity.requestPermissions(
            if (Build.VERSION.SDK_INT in 31..32) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            else arrayOf(permission()), PERMISSION_REQUEST) } catch (_: Exception) { permissionResult = null; result.error("PERMISSION_REQUEST_FAILED", "Return to FlashLink and retry permission access", null) }
    }
    fun onPermissionResult(requestCode: Int): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        permissionResult?.success(null); permissionResult = null
        reconcile()
        return true
    }

    private fun ready(): Boolean {
        if (!supported || manager == null) { phase = "unsupported"; emit(); return false }
        if (!foreground) { message = "Return to FlashLink to manage Wi-Fi Direct."; emit(); return false }
        if (channel == null) {
            channel = manager.initialize(activity.applicationContext, Looper.getMainLooper()) {
                channel = null; epoch++; group = null; peers = emptyList(); fail("Android closed the Wi-Fi Direct channel. Tap Refresh to retry.")
            }
            if (channel == null) { fail("Could not initialize Wi-Fi Direct."); return false }
        }
        if (!hasPermission()) {
            epoch++; clearTimeout(); group = null; peers = emptyList(); target = null
            phase = "permissionRequired"; message = "Allow nearby devices (Android 13+) or precise location (older Android) to discover and connect."; emit(); return false
        }
        val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiEnabled = wifi.isWifiEnabled
        if (!wifiEnabled || p2pEnabled == false) { updateEnabled(false); return false }
        return true
    }
    private fun updateEnabled(enabled: Boolean) {
        if (disposed) return
        wifiEnabled = enabled
        p2pEnabled = enabled
        if (!enabled) {
            epoch++; clearTimeout(); group = null; peers = emptyList(); target = null
            phase = "disabled"; message = "Enable Wi-Fi in Android settings, then refresh."; emit()
        } else reconcile()
    }
    private fun guarded(result: MethodChannel.Result, action: () -> Unit) {
        try {
            if (!ready()) { result.error("NOT_READY", message ?: "Wi-Fi Direct is not available", null); return }
            action(); result.success(null)
        } catch (_: SecurityException) {
            reconcilePermissionLoss()
            result.error("PERMISSION_REQUIRED", message, null)
        } catch (_: IllegalArgumentException) { result.error("INVALID_STATE", "Refresh and select an available device", null) } catch (_: Exception) { fail("Android could not complete this operation. Refresh and retry."); result.error("FRAMEWORK_ERROR", message, null) }
    }
    private fun busy() = phase == "connecting" || phase == "disconnecting" || phase == "connected"
    private fun listener(token: Int, success: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (!disposed && token == epoch) {
                try { success() } catch (_: SecurityException) { reconcilePermissionLoss() } catch (_: IllegalStateException) { fail("Wi-Fi Direct channel closed. Refresh and retry.") }
            }
        }
        override fun onFailure(reason: Int) {
            if (!disposed && token == epoch) {
                if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                    clearTimeout(); phase = "unsupported"; message = "Android reports Wi-Fi Direct is unsupported."; emit(); return
                }
                fail(when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is unsupported on this device."
                    WifiP2pManager.BUSY -> "Android Wi-Fi Direct is busy. Wait briefly, refresh and retry."
                    else -> "Wi-Fi Direct operation failed. Check Wi-Fi and the other phone, then retry."
                })
                reconcile()
            }
        }
    }
    private fun startDiscovery() {
        if (busy() || phase == "discovering") return
        if (!locationEnabled()) { fail("Enable location services in Android settings for discovery on this Android version."); return }
        epoch++; val token = epoch; message = null
        phase = "discovering"; emit()
        manager!!.discoverPeers(channel!!, listener(token) { readPeers() })
    }
    private fun stopDiscovery() {
        if (phase != "discovering") return
        epoch++; val token = epoch
        manager!!.stopPeerDiscovery(channel!!, listener(token) { phase = "idle"; message = null; emit() })
    }
    private fun connect(address: String) {
        if (busy()) return
        require(peers.any { it["address"] == address && it["status"] != WifiP2pDevice.UNAVAILABLE })
        epoch++; val token = epoch
        target = address; message = null; phase = "connecting"; emit()
        manager!!.stopPeerDiscovery(channel!!, null)
        val config = WifiP2pConfig().apply { deviceAddress = address; wps.setup = WpsInfo.PBC }
        manager.connect(channel!!, config, listener(token) {
            // Action success acknowledges negotiation only; it is NOT a connection.
            reconcile()
        })
        if (phase == "connecting") scheduleTimeout(token, "Connection timed out. Check the other phone and retry.")
    }
    private fun disconnect(cancel: Boolean) {
        if (phase == "disconnecting") return
        epoch++; val token = epoch; clearTimeout(); message = null
        phase = "disconnecting"; emit()
        if (cancel) manager!!.cancelConnect(channel!!, listener(token) { removeGroup(token) })
        else removeGroup(token)
        if (phase == "disconnecting") scheduleTimeout(token, "Disconnection timed out. Refresh to check the current group.")
    }
    private fun removeGroup(token: Int) {
        manager!!.requestConnectionInfo(channel!!) { info ->
            if (disposed || token != epoch) return@requestConnectionInfo
            if (!info.groupFormed) { disconnected(); return@requestConnectionInfo }
            try { manager.removeGroup(channel!!, listener(token) { disconnected(); reconcile() }) } catch (_: SecurityException) { reconcilePermissionLoss() } catch (_: IllegalStateException) { fail("Wi-Fi Direct channel closed. Refresh and retry.") }
        }
    }
    private fun disconnected() {
        epoch++; observation++
        clearTimeout(); group = null; target = null; phase = "idle"; message = null; emit(); readPeers()
    }
    private fun scheduleTimeout(token: Int, text: String) {
        clearTimeout()
        timeout = Runnable {
            if (disposed || token != epoch) return@Runnable
            epoch++
            try { manager?.cancelConnect(channel ?: return@Runnable, null) } catch (_: SecurityException) { /* Reconciled below. */ }
            fail(text); reconcile()
        }.also { handler.postDelayed(it, 35_000) }
    }
    private fun clearTimeout() { timeout?.let { handler.removeCallbacks(it) }; timeout = null }
    private fun readPeers() {
        if (!foreground || !hasPermission() || channel == null) return
        val token = epoch
        try {
            manager?.requestPeers(channel!!) { list ->
                if (!disposed && foreground && token == epoch) {
                    peers = list.deviceList.map { device -> mapOf("name" to device.deviceName,
                        "address" to device.deviceAddress, "status" to device.status) }
                    emit()
                }
            }
        } catch (_: SecurityException) { reconcile() }
    }
    fun reconcile() {
        if (disposed || !foreground || !ready()) return
        val token = epoch
        val query = ++observation
        try {
            manager!!.requestConnectionInfo(channel!!) { info ->
                if (disposed || !foreground || token != epoch || query != observation) return@requestConnectionInfo
                if (info.groupFormed) {
                    val base = mapOf<String, Any?>("isGroupOwner" to info.isGroupOwner,
                        "ownerAddress" to info.groupOwnerAddress?.hostAddress)
                    group = base; clearTimeout(); phase = "connected"; message = null; emit()
                    try {
                        manager.requestGroupInfo(channel!!) { actual ->
                            if (!disposed && foreground && token == epoch && query == observation && group != null) {
                                group = base + mapOf("ownerName" to actual?.owner?.deviceName,
                                    "ownerDeviceAddress" to actual?.owner?.deviceAddress,
                                    "interfaceName" to actual?.`interface`,
                                    "clients" to actual?.clientList?.map { mapOf("name" to it.deviceName, "address" to it.deviceAddress) })
                                emit()
                            }
                        }
                    } catch (_: SecurityException) { reconcilePermissionLoss() }
                } else if (group != null || phase == "connected" || phase == "disconnecting") {
                    disconnected()
                } else if (phase in listOf("permissionRequired", "disabled", "unsupported")) {
                    phase = "idle"; message = null; emit()
                }
            }
            readPeers()
        } catch (_: SecurityException) { reconcilePermissionLoss() }
    }
    private fun reconcilePermissionLoss() {
        epoch++; clearTimeout(); group = null; peers = emptyList(); target = null
        phase = "permissionRequired"; message = "Android denied access. Review FlashLink permissions in system settings."; emit()
    }
    fun resume() {
        foreground = true
        if (!supported || manager == null) { phase = "unsupported"; emit(); return }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            // Wi-Fi framework broadcasts can originate from a privileged system UID.
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else activity.registerReceiver(receiver, filter)
            registered = true
        }
        reconcile()
        if (Build.VERSION.SDK_INT >= 29 && channel != null) {
            manager.requestP2pState(channel!!) { enabled -> updateEnabled(enabled == WifiP2pManager.WIFI_P2P_STATE_ENABLED) }
        } else {
            p2pEnabled = null
            reconcile()
        }
    }
    fun suspend() {
        foreground = false
        if (registered) { activity.unregisterReceiver(receiver); registered = false }
        if (phase == "discovering") {
            try { channel?.let { manager?.stopPeerDiscovery(it, null) } } catch (_: SecurityException) { }
            epoch++; phase = "idle"; peers = emptyList(); emit()
        }
        // Do not remove an established group or cancel Android's pairing dialog.
    }
    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink) { sink = eventSink; emit(); reconcile() }
    override fun onCancel(arguments: Any?) { sink = null }
    fun dispose() {
        suspend(); disposed = true; epoch++; clearTimeout()
        permissionResult?.error("CLOSED", "Activity was recreated; retry permission access", null); permissionResult = null
        methods.setMethodCallHandler(null); events.setStreamHandler(null); sink = null; observer = null
        if (Build.VERSION.SDK_INT >= 27) channel?.close()
        channel = null
    }
    companion object {
        const val PERMISSION_REQUEST = 4102
    }
}
