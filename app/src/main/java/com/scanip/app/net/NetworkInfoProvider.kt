package com.scanip.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.scanip.app.model.NetworkSnapshot
import java.net.Inet4Address

/** Reads the IPv4 configuration of the current Wi-Fi / Ethernet connection. */
class NetworkInfoProvider(private val context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun current(): NetworkSnapshot {
        val (network, props) = findLocalNetwork()
            ?: return NetworkSnapshot(null, null, null, null, emptyList(), null, false, null, null)

        val caps = cm.getNetworkCapabilities(network)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val linkAddress = props.linkAddresses.firstOrNull { it.address is Inet4Address }
        val localIp = linkAddress?.address?.hostAddress
        val prefix = linkAddress?.prefixLength

        val gateway = props.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        val dns = props.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }

        val range = if (localIp != null && prefix != null) {
            IpUtils.hostRange(localIp, prefix)?.let { r ->
                IpUtils.clampAround(r, IpUtils.toLong(localIp)!!)
            }
        } else null

        return NetworkSnapshot(
            interfaceName = props.interfaceName,
            localIp = localIp,
            prefixLength = prefix,
            gateway = gateway,
            dnsServers = dns,
            ssid = if (isWifi) readSsid() else null,
            isWifi = isWifi,
            firstHost = range?.first,
            lastHost = range?.last,
        )
    }

    /** The network the scan should run on: Wi-Fi or Ethernet, never cellular or VPN. */
    fun localNetwork(): Network? = findLocalNetwork()?.first

    @Suppress("DEPRECATION")
    private fun findLocalNetwork(): Pair<Network, LinkProperties>? {
        val candidates = buildList {
            cm.activeNetwork?.let { add(it) }
            addAll(cm.allNetworks)
        }.distinct()
        for (n in candidates) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) continue
            val props = cm.getLinkProperties(n) ?: continue
            if (props.linkAddresses.any { it.address is Inet4Address }) return n to props
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readSsid(): String? = try {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val ssid = wm?.connectionInfo?.ssid?.trim('"')
        ssid?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
    } catch (_: Exception) {
        null
    }
}
