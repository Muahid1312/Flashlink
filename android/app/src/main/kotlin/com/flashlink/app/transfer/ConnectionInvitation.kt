package com.flashlink.app.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Public rendezvous data is never a substitute for comparing TLS fingerprints. */
data class ConnectionInvitation(val host: String, val port: Int, val id: String,
    val expires: Long, val fingerprint: String, val name: String) {
    fun validate(now: Long = System.currentTimeMillis()): ConnectionInvitation {
        require(localAddress(host)) { "Enter a private local IPv4 address, not an internet host" }
        require(port in 1024..65535) { "Port must be between 1024 and 65535" }
        Protocol.validId(id)
        require(expires > now && expires - now <= LIFETIME + 5000) { "Pairing invitation expired or phone clocks disagree; create a new invitation" }
        require(SecureTransport.normalize(fingerprint) == fingerprint)
        require(name.isNotBlank() && name.length <= 64 && name.none { it.isISOControl() })
        return this
    }
    fun encode(): String = "flashlink://pair?" + linkedMapOf("v" to "1", "host" to host,
        "port" to port.toString(), "id" to id, "expires" to expires.toString(),
        "pin" to fingerprint, "name" to name).entries.joinToString("&") { (k,v) -> "$k=${URLEncoder.encode(v,"UTF-8")}" }
    fun map(): Map<String,Any?> = mapOf("host" to host,"port" to port,"id" to id,
        "expires" to expires,"fingerprint" to fingerprint,"name" to name,"uri" to encode())
    companion object {
        const val LIFETIME = 120_000L
        fun create(host: String, fingerprint: String, name: String, port: Int = Protocol.PORT) =
            ConnectionInvitation(host,port,UUID.randomUUID().toString(),System.currentTimeMillis()+LIFETIME,fingerprint,name).validate()
        fun parse(text: String, now: Long = System.currentTimeMillis()): ConnectionInvitation {
            require(text.length <= 2048) { "Invitation is too long" }
            val uri = URI(text.trim())
            require(uri.scheme == "flashlink" && uri.host == "pair" && uri.rawFragment == null && uri.rawUserInfo == null && uri.path.isNullOrEmpty())
            val fields = linkedMapOf<String,String>()
            for(part in (uri.rawQuery ?: error("Invitation fields missing")).split('&')) {
                val pair = part.split('=',limit=2); require(pair.size == 2 && !fields.containsKey(pair[0]))
                fields[pair[0]] = URLDecoder.decode(pair[1],"UTF-8")
            }
            require(fields.keys == setOf("v","host","port","id","expires","pin","name") && fields["v"] == "1")
            return ConnectionInvitation(fields.getValue("host"),fields.getValue("port").toInt(),fields.getValue("id"),
                fields.getValue("expires").toLong(),fields.getValue("pin"),fields.getValue("name")).validate(now)
        }
        fun localAddress(value: String): Boolean {
            val parts = value.split('.'); if(parts.size != 4) return false
            val bytes = parts.map { if(it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || it.length > 1 && it[0] == '0') return false else it.toInt() }
            if(bytes.any { it !in 0..255 }) return false
            return bytes[0] == 10 || bytes[0] == 172 && bytes[1] in 16..31 || bytes[0] == 192 && bytes[1] == 168 || bytes[0] == 169 && bytes[1] == 254
        }
    }
}

/** Single-use admission, bounded by a monotonic clock even if wall time changes. */
class InvitationLease(val invitation: ConnectionInvitation, private val now: ()->Long = System::nanoTime) {
    private val deadline = now()+ConnectionInvitation.LIFETIME*1_000_000
    private val used = AtomicBoolean(false)
    fun claim(id: String): Boolean = id == invitation.id && now() < deadline && used.compareAndSet(false,true)
    fun remainingMillis(): Int = ((deadline-now())/1_000_000).coerceIn(0,ConnectionInvitation.LIFETIME).toInt()
}

/** Runs only INSIDE mutually pinned TLS, before TransferSession can read file metadata. */
object ConnectionHandshake {
    private const val MAGIC = 0x464c5031
    private fun text(input: DataInputStream, max: Int): String {
        val size = input.readUnsignedShort(); require(size in 1..max)
        val bytes = ByteArray(size); input.readFully(bytes)
        return DataInputStream(java.io.ByteArrayInputStream(byteArrayOf((size ushr 8).toByte(),size.toByte())+bytes)).readUTF()
    }
    fun client(socket: Socket, invitation: ConnectionInvitation?, name: String) {
        socket.soTimeout = 55_000
        val input = DataInputStream(socket.getInputStream())
        require(input.readInt() == MAGIC) { "Update FlashLink on both phones; pairing protocols differ" }
        val id = text(input,64); Protocol.validId(id)
        require(invitation == null || id == invitation.id) { "Pairing session differs from the invitation; create a new invitation" }
        val out = DataOutputStream(socket.getOutputStream())
        out.writeInt(MAGIC); out.writeUTF(id); out.writeUTF(name.take(32)); out.flush()
        require(input.readInt() == MAGIC && text(input,64) == id) { "Incompatible or mismatched pairing session" }
        if(!input.readBoolean()) throw SecurityException("The other phone rejected the connection or approval timed out")
    }
    fun host(socket: Socket, lease: InvitationLease, confirm: (String)->Boolean) {
        socket.soTimeout = minOf(15_000,lease.remainingMillis().coerceAtLeast(1))
        val out = DataOutputStream(socket.getOutputStream())
        out.writeInt(MAGIC); out.writeUTF(lease.invitation.id); out.flush()
        val input = DataInputStream(socket.getInputStream())
        require(input.readInt() == MAGIC) { "Incompatible FlashLink pairing protocol" }
        val id = text(input,64); val name = text(input,128)
        require(name.isNotBlank() && name.length <= 32 && name.none { it.isISOControl() })
        if(!lease.claim(id)) throw SecurityException("Pairing invitation expired, was rejected, or has already been used")
        val accepted = confirm(name)
        out.writeInt(MAGIC); out.writeUTF(id); out.writeBoolean(accepted); out.flush()
        if(!accepted) throw SecurityException("Connection rejected or confirmation timed out")
    }
}
