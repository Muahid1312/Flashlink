package com.flashlink.app.transfer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

class AndroidIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("flashlink-pairing",Context.MODE_PRIVATE)
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "flashlink-tls-identity-v1"
    init {
        if(!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=FlashLink ${Protocol.id()}"))
                    .setCertificateSerialNumber(BigInteger(128,SecureRandom()).abs().add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(System.currentTimeMillis()-86_400_000L))
                    .setCertificateNotAfter(Date(System.currentTimeMillis()+10L*365*86_400_000L)).build())
            }.generateKeyPair()
        }
    }
    val fingerprint: String get() = SecureTransport.fingerprint(store.getCertificate(alias) as X509Certificate)
    val peer: String? get() = preferences.getString("approved-peer",null)
    fun approve(value: String) {
        val pin = SecureTransport.normalize(value)
        require(pin != fingerprint) { "Enter the OTHER phone's fingerprint" }
        check(preferences.edit().putString("approved-peer",pin).commit()) { "Could not save pairing approval" }
    }
    fun forget() { check(preferences.edit().remove("approved-peer").commit()) }
    fun tls(confirmedPeer: String? = peer, pairing: Boolean = false): SSLContext {
        check(pairing || confirmedPeer != null) { "Compare and approve the receiver fingerprint first" }
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(type: String?, issuers: Array<java.security.Principal>?): Array<String> = arrayOf(alias)
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<java.security.Principal>?, socket: java.net.Socket?) = alias
            override fun getServerAliases(type: String?, issuers: Array<java.security.Principal>?) = arrayOf(alias)
            override fun chooseServerAlias(type: String?, issuers: Array<java.security.Principal>?, socket: java.net.Socket?) = if(type == "EC") alias else null
            override fun getCertificateChain(selected: String?): Array<X509Certificate> = arrayOf(store.getCertificate(alias) as X509Certificate)
            override fun getPrivateKey(selected: String?): PrivateKey = store.getKey(alias,null) as PrivateKey
        }
        return if(pairing) SecureTransport.pairingContext(arrayOf(manager)) else SecureTransport.context(arrayOf(manager),confirmedPeer!!)
    }
}
