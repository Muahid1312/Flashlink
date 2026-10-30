package com.flashlink.app.transfer

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import io.flutter.plugin.common.*

/** Thin Activity bridge; the application runtime/service own IO and durable jobs. */
class TransferBridge(private val activity: Activity, messenger: BinaryMessenger,
    private val wifi: () -> Map<String,Any?>) : EventChannel.StreamHandler {
    private val runtime = TransferRuntime.get(activity)
    private val methods = MethodChannel(messenger,"app.flashlink/transfer.v1")
    private val events = EventChannel(messenger,"app.flashlink/transfer.events.v1")
    private val main = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private var folder: MethodChannel.Result? = null
    private var folderRequest: TransferRuntime.DestinationRequest? = null
    private var disposed = false
    private val listener: (Map<String,Any?>)->Unit = { state -> if(!disposed) sink?.success(state) }
    init {
        methods.setMethodCallHandler { call,result ->
            try {
                when(call.method) {
                    "snapshot" -> result.success(runtime.latest)
                    "pairing" -> async(result) { runtime.pairing() }
                    "approvePairing" -> async(result) { check(runtime.latest["ready"] != true && runtime.latest["status"] !in TransferRuntime.ACTIVE); runtime.identity.approve(call.argument<String>("fingerprint") ?: ""); runtime.pairing() }
                    "forgetPairing" -> async(result) { check(runtime.latest["ready"] != true && runtime.latest["status"] !in TransferRuntime.ACTIVE); runtime.identity.forget(); runtime.pairing() }
                    "chooseDestination" -> {
                        check(folder == null) { "A folder picker is already open" }
                        val request = runtime.beginDestinationSelection()
                        folder = result; folderRequest = request
                        try {
                            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            },FOLDER)
                        } catch(error:Exception) {
                            folder = null; folderRequest = null; runtime.finishDestinationSelection(request); throw error
                        }
                    }
                    "notificationPermission" -> {
                        if(Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),4104)
                        result.success(null)
                    }
                    "openSession", "reconnect" -> {
                        runtime.open(wifi(),call.argument<String>("name") ?: "My FlashLink",call.argument<Int>("bufferSize") ?: 262144,call.method == "reconnect"); result.success(null)
                    }
                    "confirmConnection" -> { runtime.confirmConnection(call.argument<String>("id") ?: "",call.argument<Boolean>("accept") == true); result.success(null) }
                    "send" -> { runtime.send((call.argument<List<Map<String,Any?>>>("files") ?: error("Select files")).map { it["uri"] as String }); result.success(null) }
                    "accept", "reject" -> { runtime.approve(call.argument<String>("id") ?: "",call.method == "accept"); result.success(null) }
                    "pause" -> { runtime.pause(); result.success(null) }
                    "resume", "retry" -> { runtime.resumeSaved(call.argument<String>("id") ?: ""); result.success(null) }
                    "cancel", "deleteHistory" -> { runtime.cancel(call.argument<String>("id"),call.method == "deleteHistory"); result.success(null) }
                    "history" -> async(result) { runtime.ledger.histories().map { row ->
                        row + mapOf("resumable" to (row["resumable"] == true && runtime.ledger.load(row["id"] as String)?.peer == runtime.identity.peer))
                    } }
                    else -> result.notImplemented()
                }
            } catch(e:Exception) { result.error("TRANSFER_STATE",e.message ?: "Operation unavailable",null) }
        }
        events.setStreamHandler(this)
    }
    private fun async(result: MethodChannel.Result, operation: ()->Any?) {
        runtime.metadata.execute { try {
            val value = operation(); main.post { if(!disposed) result.success(value) }
        } catch(e:Exception) { main.post { if(!disposed) result.error("TRANSFER_STATE",e.message,null) } } }
    }
    fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?): Boolean {
        if(requestCode != FOLDER) return false
        val result = folder ?: return true; folder = null
        val request = folderRequest ?: return true; folderRequest = null
        try {
            if(resultCode == Activity.RESULT_OK) {
                val uri = data?.data ?: error("No folder selected")
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                activity.contentResolver.takePersistableUriPermission(uri,flags)
                async(result) {
                    try { runtime.destination(uri,request); null }
                    catch(error:Exception) { runtime.ledger.releaseUnused(listOf(uri.toString())); throw error }
                    finally { runtime.finishDestinationSelection(request) }
                }
            } else { runtime.finishDestinationSelection(request); result.success(null) }
        } catch(e:Exception) { runtime.finishDestinationSelection(request); result.error("FOLDER",e.message,null) }
        return true
    }
    fun connectionChanged(state: Map<String,Any?>) {
        if(runtime.connectionMethod == "wifiDirect" && state["connectionObserved"] == true && state["phase"] != "connected" && runtime.connectionBusy) runtime.interrupt("Wi-Fi Direct changed. Reconnect and resume from the verified checkpoint.")
    }
    fun resume() { runtime.foreground = true }
    fun suspend() { runtime.foreground = false } // Foreground service retains ownership; no Activity reference is retained.
    override fun onListen(arguments: Any?,eventSink: EventChannel.EventSink) { sink = eventSink; runtime.listeners.add(listener); eventSink.success(runtime.latest) }
    override fun onCancel(arguments: Any?) { runtime.listeners.remove(listener); sink = null }
    fun dispose() {
        disposed = true; runtime.listeners.remove(listener); sink = null
        folderRequest?.let { runtime.finishDestinationSelection(it) }; folderRequest = null
        folder?.error("CLOSED","Activity recreated; retry choosing a folder",null); folder = null
        methods.setMethodCallHandler(null); events.setStreamHandler(null)
    }
    companion object { const val FOLDER = 4103 }
}
