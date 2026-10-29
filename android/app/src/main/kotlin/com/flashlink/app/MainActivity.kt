package com.flashlink.app

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var transfer: com.flashlink.app.transfer.TransferBridge? = null
    private var wifi: WifiDirectGateway? = null
    private var local: com.flashlink.app.connection.LocalNetworkGateway? = null
    private var bridge: FlashLinkBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        wifi = WifiDirectGateway(this, flutterEngine.dartExecutor.binaryMessenger)
        transfer = com.flashlink.app.transfer.TransferBridge(this, flutterEngine.dartExecutor.binaryMessenger) { wifi?.currentSnapshot() ?: emptyMap() }
        wifi?.observer = { transfer?.connectionChanged(it) }
        local = com.flashlink.app.connection.LocalNetworkGateway(this, flutterEngine.dartExecutor.binaryMessenger)
        bridge = FlashLinkBridge(this, flutterEngine.dartExecutor.binaryMessenger)
    }

    @Deprecated("Used by FlutterActivity's legacy activity-result integration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (transfer?.onActivityResult(requestCode, resultCode, data) == true) return
        if (bridge?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStart() {
        super.onStart()
        transfer?.resume()
        wifi?.resume()
        local?.resume()
    }

    override fun onStop() {
        transfer?.suspend()
        wifi?.suspend()
        local?.suspend()
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        wifi?.onPermissionResult(requestCode)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        transfer?.dispose()
        transfer = null
        wifi?.dispose()
        wifi = null
        local?.dispose()
        local = null
        bridge?.dispose()
        bridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
