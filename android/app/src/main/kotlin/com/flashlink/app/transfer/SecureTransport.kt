package com.flashlink.app.transfer

import java.security.*
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Standard TLS with exact, user-confirmed certificate pins on BOTH endpoints. */
object SecureTransport {
    fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun normalize(value: String): String {
        val pin = value.removePrefix("flashlink:cert:").replace(":", "").replace(" ", "").trim().lowercase()
        require(pin.matches(Regex("[0-9a-f]{64}"))) { "Compare and enter the other phone's complete SHA-256 fingerprint" }
        return pin
    }
    fun context(keys: Array<KeyManager>, confirmedPin: String): SSLContext = buildContext(keys,normalize(confirmedPin))
    /** Quarantine only: TLS proves key possession, but TransferSession MUST wait for user approval.
     * The sender pins the receiver from its invitation. The receiver compares a channel-bound
     * code on both screens before admitting this candidate. Never use for ordinary sessions. */
    fun pairingContext(keys: Array<KeyManager>): SSLContext = buildContext(keys,null)
    private fun buildContext(keys: Array<KeyManager>, pin: String?): SSLContext {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            private fun verify(chain: Array<X509Certificate>?) {
                if(chain == null || chain.size != 1) throw java.security.cert.CertificateException("Unexpected peer certificate chain")
                chain[0].checkValidity()
                if(pin != null && !MessageDigest.isEqual(fingerprint(chain[0]).toByteArray(),pin.toByteArray()))
                    throw java.security.cert.CertificateException("Peer fingerprint does not match your approval")
            }
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = verify(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = verify(chain)
        }
        return SSLContext.getInstance("TLS").apply { init(keys,arrayOf(trust),SecureRandom()) }
    }
    fun pairingCode(session: String, firstPin: String, secondPin: String, hostNonce: ByteArray, clientNonce: ByteArray): String {
        Protocol.validId(session)
        require(hostNonce.size == 32 && clientNonce.size == 32)
        val pins = listOf(normalize(firstPin),normalize(secondPin)).sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(("FlashLink pairing v2|$session|${pins[0]}|${pins[1]}").toByteArray(Charsets.UTF_8)+hostNonce+clientNonce)
        val number = java.nio.ByteBuffer.wrap(digest).int.toLong() and 0xffffffffL
        return (number % 1_000_000).toString().padStart(6,'0')
    }
    fun peerFingerprint(socket: SSLSocket): String = fingerprint(socket.session.peerCertificates.single() as X509Certificate)
    fun authenticate(socket: SSLSocket) {
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        require(socket.enabledProtocols.isNotEmpty()) { "TLS 1.2 or newer is unavailable" }
        socket.enabledCipherSuites = socket.supportedCipherSuites.filter { suite ->
            (suite.contains("_GCM_") || suite.contains("_CHACHA20_")) &&
                (suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_") || suite.contains("_ECDHE_"))
        }.toTypedArray()
        require(socket.enabledCipherSuites.isNotEmpty()) { "No vetted AEAD cipher suite is available" }
        socket.soTimeout = 15000
        socket.startHandshake() // The pinned trust manager must succeed; no insecure fallback.
        require(socket.session.peerCertificates.isNotEmpty())
    }
}

/** Bounded monotonic retry budget; it never opens Wi-Fi pairing dialogs. */
class RetryBudget(private val now: () -> Long = System::nanoTime) {
    private val attempts = ArrayDeque<Long>()
    @Synchronized fun take(): Boolean {
        val tick = now()
        while(attempts.isNotEmpty() && tick - attempts.first() >= 60_000_000_000L) attempts.removeFirst()
        if(attempts.size >= 5) return false
        attempts.addLast(tick); return true
    }
    val backoffMillis = listOf(1000L,2000L,4000L)
}
