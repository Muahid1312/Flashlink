package com.flashlink.app.connection

import android.app.Activity
import android.content.Context
import android.net.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.flashlink.app.transfer.*
import io.flutter.plugin.common.*

/** Activity owns discovery only. The existing application runtime/service owns authenticated IO. */
class LocalNetworkGateway(private val activity: Activity, messenger: BinaryMessenger): EventChannel.StreamHandler {
    private val main = Handler(Looper.getMainLooper())
    private val runtime = TransferRuntime.get(activity)
    private val nsd = activity.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity = activity.getSystemService(ConnectivityManager::class.java)
    private val methods = MethodChannel(messenger,"app.flashlink/local.v1")
    private val events = EventChannel(messenger,"app.flashlink/local.events.v1")
    private var sink: EventChannel.EventSink? = null
    private var foreground = false
    private var disposed = false
    private var revision = 0
    private var generation = 0
    private var message: String? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var hosting: ConnectionInvitation? = null
    private var resolving = false
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val presentServices = mutableSetOf<String>()
    private val peers = linkedMapOf<String,ConnectionInvitation>()
    private var observing = false
    private val network = object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { changed() } }
        override fun onLost(network: Network) { main.post { changed() } }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { main.post { changed() } }
    }
    private val runtimeListener: (Map<String,Any?>)->Unit = {
        if(!disposed) {
            val listening = runtime.connectionMethod == "localWifi" && it["listenerReady"] == true && it["status"] == "connecting"
            if(listening && hosting != null && registration == null && foreground) advertise(hosting!!)
            if(!listening && registration != null) unadvertise()
            if(it["status"] == "connectionApproval" || it["ready"] == true || it["status"] !in TransferRuntime.ACTIVE) hosting = null
            emit()
        }
    }
    init {
        methods.setMethodCallHandler { call,result ->
            try {
                check(!disposed && foreground) { "Return to FlashLink to manage local connections" }
                when(call.method) {
                    "snapshot" -> result.success(snapshot())
                    "discover" -> { discover(); result.success(null) }
                    "stopDiscovery" -> { stopDiscovery(); emit(); result.success(null) }
                    "startHost" -> async(result) {
                        check(!runtime.connectionBusy) { "Close the active session before opening another listener" }
                        val routes = LocalNetworks.routes(activity)
                        val address = call.argument<String>("address")
                        val route = routes.firstOrNull { address == null || it.address == address } ?: error("Connect to Wi-Fi or enable your phone's hotspot in Android settings first")
                        val name = call.argument<String>("name") ?: "My FlashLink"
                        val ticket = ConnectionInvitation.create(route.address,runtime.identity.fingerprint,name)
                        check(runtime.identity.peer != null) { "Compare and approve the other phone's certificate fingerprint first" }
                        main.post { hosting = ticket }
                        runtime.openLocal(ticket,route,true,name,call.argument<Int>("bufferSize") ?: 262144)
                        ticket.map()
                    }
                    "connect", "connectManual" -> async(result) {
                        check(!runtime.connectionBusy) { "Close the active session before changing peers" }
                        val ticket = if(call.method == "connect") ConnectionInvitation.parse(call.argument<String>("invitation") ?: "")
                            else ConnectionInvitation(call.argument<String>("host") ?: "",call.argument<Int>("port") ?: Protocol.PORT,
                                call.argument<String>("id") ?: "",System.currentTimeMillis()+ConnectionInvitation.LIFETIME,
                                SecureTransport.normalize(call.argument<String>("fingerprint") ?: ""),"Local FlashLink peer").validate()
                        val route = LocalNetworks.toPeer(activity,ticket.host)
                        check(runtime.identity.peer == ticket.fingerprint) { "Compare and explicitly approve this invitation's full fingerprint first" }
                        runtime.openLocal(ticket,route,false,call.argument<String>("name") ?: "My FlashLink",call.argument<Int>("bufferSize") ?: 262144)
                        null
                    }
                    "disconnect" -> { check(runtime.connectionMethod == "localWifi"); runtime.cancel(); hosting = null; unadvertise(); emit(); result.success(null) }
                    else -> result.notImplemented()
                }
            } catch(e:Exception) { result.error("LOCAL_NETWORK",e.message,null) }
        }
        events.setStreamHandler(this)
        runtime.listeners.add(runtimeListener)
    }
    private fun async(result: MethodChannel.Result, action: ()->Any?) {
        runtime.metadata.execute {
            try { val value = action(); main.post { if(!disposed) { message = null; emit(); result.success(value) } } }
            catch(e:Exception) { main.post { if(!disposed) { message = e.message; if(!runtime.connectionBusy) { hosting = null; unadvertise() }; emit(); result.error("LOCAL_NETWORK",e.message,null) } } }
        }
    }
    private fun snapshot(): Map<String,Any?> {
        val routes = runCatching { LocalNetworks.routes(activity) }.getOrDefault(emptyList())
        val active = runtime.connectionMethod == "localWifi"
        return mapOf("revision" to revision,"available" to routes.isNotEmpty(),"addresses" to routes.map { it.map() },
            "discovering" to (discovery != null),"peers" to peers.values.filter { it.expires > System.currentTimeMillis() }.map { it.map() },
            "invitation" to hosting?.takeIf { it.expires > System.currentTimeMillis() }?.map(),
            "listening" to (active && runtime.latest["listenerReady"] == true && runtime.latest["status"] == "connecting"),
            "connected" to (active && runtime.latest["ready"] == true && runtime.latest["secure"] == true),"message" to message)
    }
    private fun emit() { if(!disposed) { revision++; sink?.success(snapshot()) } }
    private fun changed() {
        if(disposed || !foreground) return
        peers.clear()
        if(runtime.connectionMethod == "localWifi" && runtime.connectionBusy && LocalNetworks.routes(activity).isEmpty())
            runtime.interrupt("Local Wi-Fi / hotspot disappeared. Rejoin the network and create a fresh invitation to resume.")
        emit()
    }
    private fun advertise(ticket: ConnectionInvitation) {
        val listener = object: NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) { main.post { emit() } }
            override fun onRegistrationFailed(service: NsdServiceInfo, code: Int) { main.post {
                if(registration === this) { registration = null; message = "Local announcement failed (NSD $code). Use the QR/link or manual IP/port instead."; emit() }
            } }
            override fun onServiceUnregistered(service: NsdServiceInfo) { }
            override fun onUnregistrationFailed(service: NsdServiceInfo, code: Int) { }
        }
        registration = listener
        val info = NsdServiceInfo().apply {
            serviceType = TYPE; serviceName = "FlashLink-${ticket.fingerprint.take(12)}"; port = ticket.port
            for((key,value) in mapOf("v" to "1","host" to ticket.host,"id" to ticket.id,"expires" to ticket.expires.toString(),"pin" to ticket.fingerprint,"name" to ticket.name)) setAttribute(key,value)
        }
        try { nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener) }
        catch(e:Exception) { registration = null; message = "Local announcement unavailable (${e.javaClass.simpleName}). Use the QR/link or manual connection."; emit() }
        main.postDelayed({ if(hosting?.id == ticket.id) { hosting = null; unadvertise(); emit() } },ConnectionInvitation.LIFETIME)
    }
    private fun unadvertise() { registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null }
    private fun discover() {
        check(LocalNetworks.routes(activity).isNotEmpty()) { "Connect both phones to the same Wi-Fi or to one phone's manually enabled hotspot. Internet is not needed." }
        if(discovery != null) return
        peers.clear(); message = null; val token = ++generation
        val listener = object: NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { main.post { if(token == generation) emit() } }
            override fun onDiscoveryStopped(type: String) { }
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post {
                if(token == generation) { stopDiscovery(); message = "Local discovery failed (NSD $code). Use a QR/link or manual IP/port; guest Wi-Fi may isolate clients."; emit() }
            } }
            override fun onStopDiscoveryFailed(type: String, code: Int) { }
            override fun onServiceFound(service: NsdServiceInfo) { main.post {
                if(token == generation && foreground && service.serviceType.contains("_flashlink._tcp") && queue.size < 32 && peers.size < 32) {
                    presentServices.add(service.serviceName); queue.addLast(service); resolveNext(token)
                }
            } }
            override fun onServiceLost(service: NsdServiceInfo) { main.post { if(token == generation) { presentServices.remove(service.serviceName); peers.remove(service.serviceName); emit() } } }
        }
        discovery = listener
        try { nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,listener) }
        catch(e:Exception) { discovery = null; message = "Local discovery unavailable (${e.javaClass.simpleName}). Use QR or manual pairing." }
        main.postDelayed({ if(token == generation && discovery != null) { stopDiscovery(); message = "Discovery finished. Refresh to search again; invitation links still expire after two minutes."; emit() } },30_000)
        emit()
    }
    @Suppress("DEPRECATION") // Serial resolution supports Android 7+; concurrent resolution returns BUSY on older Android.
    private fun resolveNext(token: Int) {
        if(resolving || queue.isEmpty() || token != generation) return
        resolving = true
        val item = queue.removeFirst()
        try { nsd.resolveService(item,object: NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { main.post {
                resolving = false
                if(token == generation) { message = "Could not resolve a local listener (NSD $code). Use its QR/link or manual IP/port."; emit() }
                if(foreground) resolveNext(generation)
            } }
            override fun onServiceResolved(service: NsdServiceInfo) { main.post {
                resolving = false
                if(token != generation || !foreground) { if(foreground) resolveNext(generation); return@post }
                runCatching {
                    fun field(key: String) = service.attributes[key]?.let { String(it,Charsets.UTF_8) } ?: error("Missing invitation field")
                    require(field("v") == "1")
                    val ticket = ConnectionInvitation(field("host"),service.port,field("id"),field("expires").toLong(),field("pin"),field("name")).validate()
                    LocalNetworks.toPeer(activity,ticket.host)
                    if(service.serviceName in presentServices && ticket.fingerprint != runtime.identity.fingerprint) peers[service.serviceName] = ticket
                }
                resolving = false; emit(); resolveNext(token)
            } }
        }) } catch(e:Exception) { resolving = false; message = "Local listener resolution unavailable (${e.javaClass.simpleName}). Use QR or manual pairing."; emit(); resolveNext(token) }
    }
    private fun stopDiscovery() {
        generation++; queue.clear(); presentServices.clear()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
    }
    fun resume() {
        foreground = true
        if(!observing) {
            try { connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),network); observing = true }
            catch(e:Exception) { message = "Network observation unavailable (${e.javaClass.simpleName}); refresh local status manually." }
        }
        emit()
    }
    fun suspend() {
        foreground = false; stopDiscovery(); unadvertise()
        if(observing) { runCatching { connectivity.unregisterNetworkCallback(network) }; observing = false }
    }
    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink) { sink = eventSink; emit() }
    override fun onCancel(arguments: Any?) { sink = null }
    fun dispose() { suspend(); disposed = true; runtime.listeners.remove(runtimeListener); methods.setMethodCallHandler(null); events.setStreamHandler(null); sink = null }
    companion object { const val TYPE = "_flashlink._tcp." }
}
