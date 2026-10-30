package com.flashlink.app.transfer

import java.net.*
import javax.net.ssl.SSLException

/** Socket stage errors retain a useful cause without leaking endpoints or secret metadata. */
object ConnectionErrors {
    fun describe(error: Throwable?, listening: Boolean = false): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            chain.any { it is SSLException || it is java.security.cert.CertificateException } -> "Authentication failed. Scan a fresh receiver QR and compare the pairing code on both phones. Never accept a mismatch."
            chain.any { it is BindException } -> "Receiver listener could not start: the address changed or the port is in use. Close the session, refresh Wi-Fi, then tap Start receiving."
            chain.any { it is SocketTimeoutException } -> if(listening) "No sender connected before the invitation expired. Tap Start receiving for a fresh QR." else "Connection timed out. Keep the receiver listening and both phones on the same hotspot/Wi-Fi. Guest or client isolation may block connections."
            chain.any { it is NoRouteToHostException } -> "Phones are not on the same reachable network. Join the receiver's hotspot; disable VPN routing and guest/client isolation if enabled."
            chain.any { it is ConnectException } -> "Connection refused or unreachable. The receiver may not be listening. Tap Start receiving and scan its new QR; check that both phones use the same network."
            chain.any { it is SecurityException } -> error?.message ?: "Connection rejected. Compare codes and approve on the receiver before the invitation expires."
            else -> "Connection failed: ${error?.message ?: "unknown socket error"}. Refresh Wi-Fi status and create a fresh receiver invitation."
        }
    }
}
