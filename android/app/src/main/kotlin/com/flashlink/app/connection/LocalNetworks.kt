package com.flashlink.app.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.flashlink.app.transfer.ConnectionInvitation
import java.net.Inet4Address
import java.net.NetworkInterface

/** Only observed Wi-Fi / tethering interfaces; never an internet or cellular fallback. */
data class LocalRoute(val address: String, val prefix: Int, val interfaceName: String, val network: Network?) {
    fun contains(host: String): Boolean {
        if(!ConnectionInvitation.localAddress(host)) return false
        fun number(ip: String) = ip.split('.').fold(0L) { value, part -> (value shl 8) or part.toLong() }
        val mask = (0xffffffffL shl (32-prefix)) and 0xffffffffL
        return number(host) and mask == number(address) and mask
    }
    fun map() = mapOf("address" to address,"interface" to interfaceName,"prefix" to prefix)
}
object LocalNetworks {
    fun routes(context: Context): List<LocalRoute> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val result = mutableListOf<LocalRoute>()
        for(network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if(!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val links = connectivity.getLinkProperties(network) ?: continue
            for(link in links.linkAddresses) {
                val ip = link.address.hostAddress ?: continue
                if(link.address is Inet4Address && ConnectionInvitation.localAddress(ip))
                    result.add(LocalRoute(ip,link.prefixLength,links.interfaceName ?: "Wi-Fi",network))
            }
        }
        // A hotspot owner often has no ConnectivityManager Wi-Fi Network. Bind its actual AP address.
        for(device in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if(!device.isUp || device.isLoopback || device.name.startsWith("p2p") ||
                !Regex("(?i)(wlan|wifi|ap|swlan|softap).*" ).matches(device.name)) continue
            for(link in device.interfaceAddresses) {
                val ip = link.address.hostAddress ?: continue
                if(link.address is Inet4Address && ConnectionInvitation.localAddress(ip) && result.none { it.address == ip })
                    result.add(LocalRoute(ip,link.networkPrefixLength.toInt(),device.name,null))
            }
        }
        return result.distinctBy { it.address }
    }
    fun toPeer(context: Context, host: String): LocalRoute = routes(context).firstOrNull { it.contains(host) && it.address != host }
        ?: error("That address is not on an observed local Wi-Fi / hotspot subnet. Connect both phones to the same Wi-Fi or hotspot; guest/client isolation may block peers.")
}
