package com.flashlink.app.transfer

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.Network
import com.flashlink.app.connection.LocalRoute
import android.net.wifi.p2p.WifiP2pManager
import android.os.*
import android.provider.OpenableColumns
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

/** Application-context ownership: Activity/Flutter listeners never own file IO. */
class TransferRuntime private constructor(private val context: Context) {
    val work = Executors.newSingleThreadExecutor()
    val metadata = Executors.newSingleThreadExecutor()
    val ledger = TransferLedger(context)
    val identity by lazy { AndroidIdentity(context) }
    val listeners = CopyOnWriteArraySet<(Map<String,Any?>)->Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val retries = RetryBudget()
    private val preparing = AtomicBoolean(false)
    @Volatile var foreground = false
    @Volatile var latest: Map<String,Any?> = mapOf("status" to "idle","ready" to false,"secure" to false)
        private set
    @Volatile private var session: TransferSession? = null
    @Volatile private var retired: TransferSession? = null
    private val deciding = AtomicBoolean(false)
    @Volatile private var server: SSLServerSocket? = null
    @Volatile private var connecting: SSLSocket? = null
    @Volatile private var generation = 0
    @Volatile var service: TransferService? = null
    private var destination: String? = context.getSharedPreferences("flashlink-destination",0).getString("tree",null)
    private var peerName = "My FlashLink"
    @Volatile private var authenticatedPin: String? = null
    private var host: String? = null
    private var owner = false
    private var bufferSize = 262144
    private var offerTicks = ArrayDeque<Long>()
    @Volatile var connectionMethod = "wifiDirect"
        private set
    @Volatile var connectionNetwork: Network? = null
        private set
    @Volatile var invitation: ConnectionInvitation? = null
        private set
    @Volatile private var lease: InvitationLease? = null
    @Volatile private var decision: CompletableFuture<Boolean>? = null
    @Volatile private var rawConnecting: Socket? = null
    private var port = Protocol.PORT
    private var route: LocalRoute? = null
    val connectionBusy: Boolean get() = session != null || latest["status"] in ACTIVE

    init { work.execute { ledger.writableDatabase; ledger.releaseUnused(context.contentResolver.persistedUriPermissions.map { it.uri.toString() }) } }
    fun update(value: Map<String,Any?>) {
        latest = value + mapOf("connectionMethod" to connectionMethod,"connectionHost" to host,"connectionPort" to port,"connectionSessionId" to lease?.invitation?.id,"destinationChosen" to (destination != null),
            "destinationLabel" to destination?.let { runCatching { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(it)) }.getOrDefault("Selected SAF folder") })
        val snapshot = latest
        main.post {
            listeners.forEach { runCatching { it(snapshot) } }
            service?.refresh(snapshot)
        }
    }
    fun pairing(): Map<String,Any?> = mapOf("fingerprint" to identity.fingerprint,"approvedPeer" to identity.peer)
    fun destination(uri: Uri) {
        check(latest["status"] !in ACTIVE) { "An incoming request arrived; reject it before changing folders" }
        check(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) { "A persistent writable folder grant is required" }
        val previous = destination
        destination = uri.toString()
        check(context.getSharedPreferences("flashlink-destination",0).edit().putString("tree",destination).commit())
        update(latest)
        if(previous != null) metadata.execute { ledger.releaseUnused(listOf(previous)) }
    }
    @Synchronized fun open(state: Map<String,Any?>, name: String, size: Int = 262144, reconnect: Boolean = false) {
        check(foreground) { "Open FlashLink to establish an authenticated session" }
        check(session == null && latest["status"] !in ACTIVE) { "Session/transfer is already active" }
        val group = state["group"] as? Map<*,*> ?: error("Reconnect Wi-Fi Direct in Nearby first")
        check(state["phase"] == "connected")
        host = group["ownerAddress"] as? String ?: error("Group owner address unavailable")
        owner = group["isGroupOwner"] == true; peerName = name.take(32); bufferSize = size
        connectionMethod = "wifiDirect"; connectionNetwork = null; route = null; port = Protocol.PORT
        invitation = null
        lease = if(owner) InvitationLease(ConnectionInvitation(host!!,Protocol.PORT,Protocol.id(),System.currentTimeMillis()+ConnectionInvitation.LIFETIME,identity.fingerprint,peerName)) else null
        beginOpen(name,size,reconnect)
    }
    @Synchronized fun openLocal(ticket: ConnectionInvitation, local: LocalRoute, hosting: Boolean, name: String, size: Int = 262144) {
        check(foreground && !connectionBusy) { "Close the existing connection before changing connection method" }
        ticket.validate()
        check(identity.peer != null && (hosting || identity.peer == ticket.fingerprint)) { "Compare and approve the peer fingerprint before connecting" }
        host = ticket.host; port = ticket.port; owner = hosting; route = local
        connectionMethod = "localWifi"; connectionNetwork = local.network
        invitation = ticket; lease = if(hosting) InvitationLease(ticket) else null
        beginOpen(name,size,false)
    }
    private fun beginOpen(name: String, size: Int, reconnect: Boolean) {
        require(size in Protocol.buffers); check(identity.peer != null) { "Compare and approve BOTH certificate fingerprints first" }
        peerName = name.take(32); bufferSize = size
        check(retries.take()) { "Too many connection attempts. Wait one minute before trying again." }
        val approvedPeer = identity.peer ?: error("Approve the peer fingerprint first")
        val token = ++generation
        val intent = Intent(context,TransferService::class.java)
        if(Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        update(mapOf("status" to if(reconnect) "reconnecting" else "connecting","ready" to false,"secure" to false,"message" to "Authenticating the approved peer with mutual TLS…"))
        work.execute { connect(token,if(reconnect) 3 else 1,approvedPeer) }
    }
    private fun connect(token: Int, attempts: Int, approvedPeer: String) {
        var last: Exception? = null
        for(attempt in 0 until attempts) {
            if(token != generation) return
            if(attempt > 0) {
                if(!retries.take()) break
                // Interrupted by cancel; bounded TLS retries never restart Wi-Fi pairing.
                val delay = retries.backoffMillis[attempt-1]
                val until = SystemClock.elapsedRealtime()+delay
                while(SystemClock.elapsedRealtime()<until) { if(token != generation) return; Thread.sleep(100) }
            }
            try {
                check(retired?.awaitReleased() != false) { "Previous partial file is still closing; retry shortly" }
                val tls = identity.tls(approvedPeer)
                val socket = if(owner) {
                    val activeLease = lease ?: error("Pairing session missing")
                    val listener = tls.serverSocketFactory.createServerSocket() as SSLServerSocket
                    server = listener; listener.reuseAddress = true
                    listener.enabledProtocols = listener.supportedProtocols.filter { it in listOf("TLSv1.2","TLSv1.3") }.toTypedArray()
                    listener.needClientAuth = true
                    listener.bind(InetSocketAddress(InetAddress.getByName(host),port),1)
                    if(token != generation) { listener.close(); return }
                    update(latest + mapOf("listenerReady" to true,"message" to "Listening for the approved peer. Pairing session expires in two minutes."))
                    var accepted: SSLSocket? = null
                    var rejects = 0
                    while(accepted == null && activeLease.remainingMillis() > 0 && token == generation) {
                        listener.soTimeout = activeLease.remainingMillis().coerceAtLeast(1)
                        val candidate = listener.accept() as SSLSocket; connecting = candidate
                        candidate.tcpNoDelay = true; candidate.keepAlive = true
                        try {
                            SecureTransport.authenticate(candidate)
                            ConnectionHandshake.host(candidate,activeLease) { remoteName -> confirmConnection(token,remoteName,approvedPeer,activeLease) }
                            accepted = candidate
                        } catch(e:Exception) {
                            runCatching { candidate.close() }; connecting = null
                            if(activeLease.remainingMillis() <= 0 || decision != null || ++rejects >= 5 || e !is SSLException) throw e
                            android.util.Log.w("FlashLinkConnection","Rejected a TLS peer (${e.javaClass.simpleName}); waiting for the approved phone")
                        }
                    }
                    listener.close(); server = null
                    accepted ?: error("Pairing invitation expired; create a new invitation")
                } else {
                    val raw = Socket(); rawConnecting = raw
                    raw.tcpNoDelay = true; raw.keepAlive = true
                    if(token != generation) { raw.close(); return }
                    route?.let { local ->
                        if(local.network != null) local.network.bindSocket(raw)
                        raw.bind(InetSocketAddress(local.address,0))
                    }
                    raw.connect(InetSocketAddress(host,port),15000)
                    (tls.socketFactory.createSocket(raw,host,port,true) as SSLSocket).also {
                        connecting = it; rawConnecting = null
                        SecureTransport.authenticate(it)
                        ConnectionHandshake.client(it,invitation,peerName)
                    }
                }
                connecting = socket
                if(token != generation) { socket.close(); return }
                val pin = approvedPeer
                authenticatedPin = pin
                val created = TransferSession(socket,peerName,{ manifest ->
                    check(foreground) { "Return to FlashLink before approving incoming files" }
                    val saved = ledger.load(manifest.id) ?: error("Manifest missing")
                    val available = StatFs(context.filesDir.absolutePath).availableBytes
                    check(available >= manifest.total) { "Insufficient private staging space" }
                    check(saved.destination != null) { "Choose a destination folder before opening a session" }
                    PrivateReceiveStore(ledger,saved)
                },{ event ->
                    val retained = event["status"] in RETAINED
                    val acceptsEvent = token == generation || (event["id"] == latest["id"] && latest["status"] == "cancelled" && event["status"] == "cancelled")
                    val snapshot = event + mapOf("ready" to (latest["ready"] == true && event["status"] !in setOf("paused","interrupted","failed","cancelled")),"secure" to (event["status"] !in setOf("paused","interrupted","failed","cancelled")))
                    // UI terminal states must not wait for a failing storage provider/database.
                    if(acceptsEvent && retained) update(snapshot)
                    val saved = runCatching {
                        ledger.state(event)
                        if(event["status"] == "failed" && (event["message"] as? String)?.let { it.contains("Source content changed") || it.contains("checkpoint does not match") || it.contains("SHA-256 mismatch") } == true)
                            ledger.writableDatabase.execSQL("UPDATE resume_jobs SET approved=0 WHERE id=?",arrayOf(event["id"]))
                    }
                    if(acceptsEvent) update(if(saved.isSuccess) snapshot else snapshot + mapOf("message" to "${event["message"] ?: ""} State/history could not be saved; check available storage before resuming."))
                },{ ready, message ->
                    if(token == generation) {
                        if(!ready) { retired = session; session = null }
                        update(latest + mapOf("ready" to ready,"secure" to ready,"peer" to if(ready) message else latest["peer"],
                            "authenticatedPeerFingerprint" to if(ready) pin else null,
                            "status" to if(ready) "idle" else if(latest["status"] in RETAINED) latest["status"] else "connectionLost",
                            "message" to if(ready) "Authenticated and encrypted. Each new manifest still needs approval." else message))
                        if(!ready) service?.stopIfIdle()
                    }
                },bufferSize,{ !preparing.get() },{ manifest ->
                    check(permitRequest()) { "Too many transfer requests; session closed" }
                    !ledger.seen(manifest.id)
                },{ manifest, outgoing ->
                    if(!outgoing) ledger.create(manifest,pin,false,destination=destination)
                },{ manifest -> ledger.approve(manifest.id) },{ manifest ->
                    check(permitRequest()) { "Too many resume requests; session closed" }
                    val saved = ledger.matches(manifest,pin)
                    if(saved == null || saved.outgoing) null
                    else if(ledger.receipt(manifest.id)) CompletedReceiptStore(manifest)
                    else if(!ledger.available(saved)) null else PrivateReceiveStore(ledger,saved)
                },{ manifest,file,offset ->
                    // Sender confirmation is informational. Receiver's validated ledger remains authoritative on resume.
                    ledger.writableDatabase.execSQL("INSERT OR REPLACE INTO resume_files(job,id,offset,verified) VALUES(?,?,?,0)",arrayOf<Any?>(manifest.id,file.id,offset))
                })
                connecting = null; session = created; created.start(); return
            } catch(e: Exception) {
                last = e
                runCatching { connecting?.close() }; connecting = null; runCatching { rawConnecting?.close() }; rawConnecting = null
                runCatching { server?.close() }; server = null
                if(e is SSLException || e is SecurityException || e is IllegalArgumentException) break // Authentication failures are never auto-retried.
            }
        }
        if(token == generation) {
            update(latest + mapOf("status" to "connectionLost","ready" to false,"secure" to false,"message" to "Secure connection failed: ${last?.message}. Verify both fingerprints and create a fresh listener/invitation. Wi-Fi guest isolation can block local peers."))
            service?.stopIfIdle()
        }
    }
    private fun confirmConnection(token: Int, name: String, pin: String, currentLease: InvitationLease): Boolean {
        if(token != generation || !foreground) return false
        val pending = CompletableFuture<Boolean>(); decision = pending
        update(mapOf("status" to "connectionApproval","ready" to false,"secure" to false,
            "peer" to name,"candidateFingerprint" to pin,"connectionRequestId" to currentLease.invitation.id,
            "message" to "A mutually authenticated peer requests a connection. Compare the fingerprint and accept within 40 seconds. No files have been accepted."))
        return try {
            val approved = pending.get(minOf(40_000,currentLease.remainingMillis().coerceAtLeast(1)).toLong(),TimeUnit.MILLISECONDS)
            approved && foreground && token == generation
        } catch(_:TimeoutException) { false }
        finally { if(decision === pending) decision = null }
    }
    fun confirmConnection(id: String, accept: Boolean) {
        check(foreground && latest["status"] == "connectionApproval" && latest["connectionRequestId"] == id) { "Connection confirmation expired or does not match" }
        check(decision?.complete(accept) == true) { "Connection was already answered" }
    }
    private fun permitRequest(): Boolean = synchronized(offerTicks) {
        val now = SystemClock.elapsedRealtime()
        while(offerTicks.isNotEmpty() && now-offerTicks.first() >= 60000) offerTicks.removeFirst()
        if(offerTicks.size >= 10) false else { offerTicks.addLast(now); true }
    }
    fun send(uris: List<String>) {
        require(uris.size in 1..128) { "Select between 1 and 128 files" }
        check(latest["ready"] == true && latest["secure"] == true && latest["status"] !in ACTIVE && preparing.compareAndSet(false,true)) { "Open an idle authenticated session first" }
        val token = generation; val current = session ?: error("Session closed")
        update(mapOf("status" to "preparing","ready" to true,"secure" to true,"peer" to latest["peer"],"authenticatedPeerFingerprint" to authenticatedPin,"message" to "Calculating immutable source identities…"))
        work.execute {
            try {
                val sources = uris.map { source(it,token) }
                check(token == generation)
                val manifest = Manifest(Protocol.id(),sources.map { it.meta })
                ledger.create(manifest,authenticatedPin ?: error("No authenticated peer"),true,uris)
                if(token != generation) { ledger.cancelSaved(manifest.id,false); error("Preparation cancelled") }
                current.send(manifest,sources)
            } catch(e:Exception) { if(token == generation) update(latest + mapOf("status" to "failed","message" to e.message)) }
            finally { preparing.set(false) }
        }
    }
    private fun source(value: String, token: Int): Source {
        val uri = Uri.parse(value); require(uri.scheme == "content")
        val resolver = context.contentResolver
        var name = "Document"; var size = -1L
        resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
            if(c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val s = c.getColumnIndex(OpenableColumns.SIZE)
                if(n >= 0 && !c.isNull(n)) name = c.getString(n)
                if(s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        Protocol.validName(name)
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(262144)
            while(true) {
                check(token == generation) { "Preparation cancelled" }
                val n = input.read(buffer); if(n < 0) break
                digest.update(buffer,0,n); count = Math.addExact(count,n.toLong())
            }
        } ?: error("Selected source is missing or its permission was revoked")
        require(size < 0 || size == count) { "Source changed while preparing" }
        val meta = FileMeta(Protocol.id(),name,count,resolver.getType(uri) ?: "application/octet-stream",digest.digest())
        return Source(meta) { resolver.openInputStream(uri) ?: error("Source is no longer readable") }
    }
    fun resumeSaved(id: String) {
        check(latest["ready"] == true && latest["secure"] == true && latest["status"] !in ACTIVE) { "Reconnect and authenticate the same peer first" }
        val saved = ledger.load(id) ?: error("Transfer metadata is missing")
        check(saved.peer == authenticatedPin && ledger.available(saved)) { "Source, destination or checkpoint data is unavailable" }
        check(saved.outgoing) { "Resume must be requested from the original sending phone" }
        val current = session ?: error("Session closed")
        val sources = saved.manifest.files.mapIndexed { index, meta -> Source(meta) {
            context.contentResolver.openInputStream(Uri.parse(saved.sources[index])) ?: error("Source is missing")
        } }
        val token = generation
        work.execute { try { check(token == generation); current.send(saved.manifest,sources,true) } catch(e:Exception) { if(token == generation) update(latest + mapOf("message" to e.message)) } }
    }
    fun approve(id: String, accept: Boolean) {
        check(foreground && latest["secure"] == true && latest["id"] == id && latest["status"] == "waitingForApproval" && latest["direction"] == "received") { "Approval does not match the current manifest" }
        val current = session ?: error("Session closed")
        check(deciding.compareAndSet(false,true)) { "Approval is already being processed" }
        work.execute { try { if(accept) current.accept() else current.reject() } catch(e:Exception) { current.fail(e.message ?: "Approval failed") } finally { deciding.set(false) } }
    }
    fun pause() {
        check(latest["status"] in setOf("transferring","verifying")) { "Pause is only available while streaming" }
        val current = session ?: error("No active transfer")
        val token = generation
        work.execute { runCatching { check(token == generation); current.pause() }.onFailure { if(token == generation) update(latest + mapOf("message" to it.message)) } }
    }
    fun cancel(id: String? = null, delete: Boolean = false) {
        if(id != null && latest["id"] != id) { metadata.execute { ledger.cancelSaved(id,delete) }; return }
        generation++; decision?.complete(false); runCatching { server?.close() }; runCatching { connecting?.close() }; runCatching { rawConnecting?.close() }
        val previous = session; retired = previous; session = null
        val shouldCancelJob = latest["id"] is String && latest["status"] != "completed" || preparing.get() && latest["status"] == "preparing"
        if(previous != null) Thread {
            if(shouldCancelJob) previous.cancel("Cancelled by you")
            else previous.interrupt("Connection closed; any verified checkpoint is retained")
        }.start()
        val closedState = latest + mapOf("ready" to false,"secure" to false,
            "status" to if(shouldCancelJob) "cancelled" else if(latest["status"] == "completed") "completed" else "idle",
            "message" to if(shouldCancelJob) "Cancelled by you" else "Connection closed. Completed files are kept.")
        update(closedState)
        val cancelledId = id ?: closedState["id"] as? String
        metadata.execute {
            if(shouldCancelJob) ledger.state(closedState)
            if(shouldCancelJob || delete) cancelledId?.let { ledger.cancelSaved(it,delete) }
        }
        service?.stopIfIdle()
    }
    fun interrupt(reason: String) {
        decision?.complete(false); runCatching { rawConnecting?.close() }
        runCatching { server?.close() }; runCatching { connecting?.close() }
        val previous = session
        if(previous != null) Thread { previous.interrupt(reason) }.start()
        else { generation++; update(latest + mapOf("status" to "connectionLost","ready" to false,"secure" to false,"message" to reason)); service?.stopIfIdle() }
    }
    companion object {
        @Volatile private var instance: TransferRuntime? = null
        fun get(context: Context): TransferRuntime = instance ?: synchronized(this) { instance ?: TransferRuntime(context.applicationContext).also { instance = it } }
        val ACTIVE = setOf("preparing","waitingForApproval","transferring","verifying","finalizing","connecting","reconnecting","connectionApproval")
        val RETAINED = setOf("paused","interrupted","failed","completed","cancelled")
    }
}
