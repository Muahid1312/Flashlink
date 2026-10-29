package com.flashlink.app

/** Framework reason values are stable Android API constants: ERROR=0, UNSUPPORTED=1, BUSY=2. */
object WifiDirectPolicy {
    fun permissions(sdk: Int): Array<String> = when {
        sdk >= 33 -> arrayOf("android.permission.NEARBY_WIFI_DEVICES")
        sdk >= 31 -> arrayOf("android.permission.ACCESS_FINE_LOCATION","android.permission.ACCESS_COARSE_LOCATION")
        else -> arrayOf("android.permission.ACCESS_FINE_LOCATION")
    }
    fun reason(code: Int) = when(code) { 0 -> "ERROR"; 1 -> "P2P_UNSUPPORTED"; 2 -> "BUSY"; else -> "UNKNOWN" }
    fun retry(code: Int, attempt: Int) = attempt < 3 && (code == 2 || code == 0 && attempt < 2)
    fun detail(operation: String, code: Int, attempt: Int): String = "$operation: ${reason(code)} ($code), attempt $attempt/3. " + when(code) {
        1 -> "This device does not support Wi-Fi Direct. Use Local Wi-Fi / hotspot."
        2 -> "Android is busy with Wi-Fi pairing or discovery. Close another sharing app and retry, or use Local Wi-Fi / hotspot."
        else -> "Check Wi-Fi and permissions on both phones. On older Android enable Location services. If it persists, toggle Wi-Fi or use Local Wi-Fi / hotspot; some devices cannot run P2P and a hotspot together."
    }
}
