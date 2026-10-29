package com.flashlink.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/** v1 bridge. All operations are user-initiated; no Wi-Fi or permission prompts. */
class FlashLinkBridge(private val activity: Activity, messenger: BinaryMessenger) :
    MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "app.flashlink/native.v1")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingPicker: MethodChannel.Result? = null
    private var disposed = false

    init { channel.setMethodCallHandler(this) }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "pickFiles" -> pickFiles(result)
            "storageInfo" -> {
                try {
                    val stat = StatFs(activity.filesDir.absolutePath)
                    result.success(mapOf("available" to stat.availableBytes, "total" to stat.totalBytes))
                } catch (_: Exception) {
                    result.error("STORAGE_UNAVAILABLE", "Could not inspect app storage volume", null)
                }
            }
            "networkCapabilities" -> result.success(mapOf(
                "wifiDirectHardware" to activity.packageManager.hasSystemFeature("android.hardware.wifi.direct"),
                "discoveryImplemented" to true,
                "transferImplemented" to true,
                "sdkInt" to android.os.Build.VERSION.SDK_INT,
            ))
            "sendFiles", "pauseTransfer", "cancelTransfer" ->
                result.error("UNAVAILABLE", "Use the versioned app.flashlink/transfer.v1 transfer channel", null)
            else -> result.notImplemented()
        }
    }

    private fun pickFiles(result: MethodChannel.Result) {
        if (pendingPicker != null) {
            result.error("PICKER_BUSY", "A file picker is already open", null)
            return
        }
        pendingPicker = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, PICK_FILES)
        } catch (_: ActivityNotFoundException) {
            pendingPicker = null
            result.error("NO_PICKER", "No document picker is installed", null)
        } catch (_: Exception) {
            pendingPicker = null
            result.error("PICKER_FAILED", "Could not open document picker", null)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK_FILES) return false
        val result = pendingPicker ?: return true
        if (resultCode != Activity.RESULT_OK) {
            pendingPicker = null
            result.success(null)
            return true
        }
        val uris = linkedSetOf<Uri>()
        data?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) uris.add(clip.getItemAt(index).uri)
        }
        data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) {
            pendingPicker = null
            result.error("EMPTY_RESULT", "The file picker returned no documents", null)
            return true
        }
        val flags = data?.flags ?: 0
        // Providers may perform I/O during metadata queries. Never block Flutter's UI thread.
        worker.execute {
            try {
                require(uris.size <= 128) { "Select at most 128 files per batch" }
                val files = uris.map { uri -> metadata(uri, flags) }
                main.post {
                    if (!disposed) {
                        pendingPicker = null
                        result.success(files)
                    }
                }
            } catch (_: Exception) {
                main.post {
                    if (!disposed) {
                        pendingPicker = null
                        result.error("DOCUMENT_UNAVAILABLE", "Could not read selected documents", null)
                    }
                }
            }
        }
        return true
    }

    private fun metadata(uri: Uri, flags: Int): Map<String, Any?> {
        require(uri.scheme == "content") { "Only document content URIs are accepted" }
        val resolver = activity.contentResolver
        // Persist only OS-granted read access, needed for explicit resume after process death.
        if(flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Read access not granted" }
        var name = "Document"
        var bytes: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) bytes = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        return mapOf("uri" to uri.toString(), "name" to name,
            "bytes" to bytes, "mimeType" to resolver.getType(uri))
    }

    fun dispose() {
        disposed = true
        channel.setMethodCallHandler(null)
        pendingPicker?.error("ACTIVITY_CLOSED", "The activity closed during selection", null)
        pendingPicker = null
        worker.shutdownNow()
    }

    companion object {
        private const val PICK_FILES = 4101
    }
}
