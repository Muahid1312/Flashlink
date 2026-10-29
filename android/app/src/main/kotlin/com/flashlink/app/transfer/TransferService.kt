package com.flashlink.app.transfer

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.*

/** Connected-device foreground service owns the live TLS session, not an Activity. */
class TransferService : Service() {
    private lateinit var runtime: TransferRuntime
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var notificationStatus: Any? = null
    private var notificationSecure: Any? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private val connection = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                manager?.requestConnectionInfo(channel ?: return) { info ->
                    if(!info.groupFormed) runtime.interrupt("Wi-Fi Direct connection lost. Reconnect and resume the saved transfer.")
                }
            } catch (_:SecurityException) { runtime.interrupt("Nearby permission changed. Regrant access before resuming.") }
        }
    }
    override fun onCreate() {
        super.onCreate()
        runtime = TransferRuntime.get(this); runtime.service = this
        val notifications = getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel(CHANNEL,"FlashLink connection and transfers",NotificationManager.IMPORTANCE_LOW))
        val initial = notification(runtime.latest)
        if(Build.VERSION.SDK_INT >= 29) startForeground(ID,initial,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID,initial)
        manager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(applicationContext,main.looper) { runtime.interrupt("Wi-Fi Direct framework channel lost") }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if(Build.VERSION.SDK_INT >= 33) registerReceiver(connection,filter,RECEIVER_EXPORTED) else registerReceiver(connection,filter)
        registered = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            "pause" -> runCatching { runtime.pause() }
            "cancel" -> runtime.cancel()
        }
        return START_NOT_STICKY // A killed service requires explicit user-led reconnect; no pairing loops.
    }
    @Suppress("DEPRECATION") // Android 7/8 compatibility path predates notification channels.
    private fun notification(state: Map<String,Any?>): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)!!
        val pending = PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if(Build.VERSION.SDK_INT >= 26) Notification.Builder(this,CHANNEL) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("FlashLink · ${state["status"] ?: "connecting"}")
            .setContentText(if(state["secure"] == true) "Authenticated device connection" else "Establishing the approved device connection")
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
        if(state["status"] in setOf("transferring","verifying")) {
            builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_pause),"Pause",PendingIntent.getService(this,1,Intent(this,TransferService::class.java).setAction("pause"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_menu_close_clear_cancel),"Cancel",PendingIntent.getService(this,2,Intent(this,TransferService::class.java).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        return builder.build()
    }
    fun refresh(state: Map<String,Any?>) {
        main.post {
            // Progress arrives up to five times/second, but notification contents change only on state transitions.
            if(notificationStatus != state["status"] || notificationSecure != state["secure"]) {
                getSystemService(NotificationManager::class.java).notify(ID,notification(state))
                notificationStatus = state["status"]; notificationSecure = state["secure"]
            }
            stopIfIdle()
        }
    }
    fun stopIfIdle() {
        main.post {
            if(runtime.latest["ready"] != true && runtime.latest["status"] !in TransferRuntime.ACTIVE) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        if(registered) unregisterReceiver(connection)
        if(Build.VERSION.SDK_INT >= 27) channel?.close()
        if(runtime.service === this) {
            runtime.service = null
            if(runtime.latest["ready"] == true || runtime.latest["status"] in TransferRuntime.ACTIVE) runtime.interrupt("Transfer service stopped; verified checkpoints retained")
        }
        super.onDestroy()
    }
    companion object { const val CHANNEL = "flashlink-device-transfer"; const val ID = 8123 }
}
