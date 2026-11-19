package com.scanip.app.model

/** A host found on the local network, built up as each probe reports back. */
data class Device(
    val ip: String,
    val ipValue: Long,
    val isAlive: Boolean = true,
    val latencyMs: Long? = null,
    val hostname: String? = null,
    val netbiosName: String? = null,
    val workgroup: String? = null,
    val mdnsName: String? = null,
    val mac: String? = null,
    val vendor: String? = null,
    val upnp: UpnpInfo? = null,
    val openPorts: List<OpenPort> = emptyList(),
    val portsScanned: Boolean = false,
    val isGateway: Boolean = false,
    val isSelf: Boolean = false,
    /** Found only by its ARP reply: it ignores ping and every probed port (firewalled). */
    val firewalled: Boolean = false,
    /** Information read over SNMP, when the device answered. */
    val snmp: SnmpSummary? = null,
    /** "Switch · port" where the device is plugged in, from the switches' MAC tables. */
    val attachedTo: String? = null,
) {
    /** Best human-readable name, in the same preference order Advanced IP Scanner uses. */
    val displayName: String
        get() = when {
            isSelf -> netbiosName ?: mdnsName ?: hostname ?: "This device"
            else -> snmp?.name ?: netbiosName ?: mdnsName ?: upnp?.friendlyName ?: hostname ?: ip
        }

    val manufacturer: String?
        get() = vendor ?: upnp?.manufacturer
}

data class SnmpSummary(
    val name: String?,
    val description: String?,
    val location: String?,
    val isSwitch: Boolean,
    val isRouter: Boolean,
    val portCount: Int,
    val lldpNeighbors: Int,
    val macTableSize: Int,
    /** This device's addresses, e.g. one per VLAN on a router. */
    val addresses: List<String>,
    val community: String,
    val version: String,
)

/** A subnet (VLAN) to include in scans. */
data class SubnetEntry(
    val cidr: String,
    val enabled: Boolean = true,
    val source: Source = Source.MANUAL,
) {
    enum class Source { LOCAL, DISCOVERED, SNMP, MANUAL }
}

data class UpnpInfo(
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val deviceType: String?,
    val serialNumber: String?,
    val presentationUrl: String?,
    val server: String?,
    val location: String,
)

data class OpenPort(
    val port: Int,
    val service: String,
    val banner: String? = null,
)

data class NetworkSnapshot(
    val interfaceName: String?,
    val localIp: String?,
    val prefixLength: Int?,
    val gateway: String?,
    val dnsServers: List<String>,
    val ssid: String?,
    val isWifi: Boolean,
    val firstHost: Long?,
    val lastHost: Long?,
)
