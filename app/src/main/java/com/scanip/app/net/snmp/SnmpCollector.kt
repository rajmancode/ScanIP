package com.scanip.app.net.snmp

import com.scanip.app.net.IpUtils

/** One MAC address learned on a switch port (forwarding database entry). */
data class FdbEntry(val mac: String, val vlan: Int?, val ifIndex: Int?, val bridgePort: Int)

/** One LLDP neighbour seen on a local port. */
data class LldpNeighbor(
    val localPortNum: Int,
    val localPortName: String?,
    val chassisId: String?,
    val portId: String?,
    val portDesc: String?,
    val sysName: String?,
)

/** Everything read from one SNMP-speaking device. */
data class SnmpDetails(
    val target: SnmpTarget,
    val sysName: String?,
    val sysDescr: String?,
    val sysObjectId: String?,
    val sysLocation: String?,
    val bridgeMac: String?,
    val ipForwarding: Boolean,
    /** ifIndex -> short port name (ifName, else ifDescr). */
    val ports: Map<Int, String>,
    /** This device's own IP addresses with prefix length (its VLAN interfaces on a router). */
    val addresses: List<Pair<String, Int>>,
    /** ARP cache of the device: IP -> MAC. On a router this covers every VLAN. */
    val arp: Map<String, String>,
    val fdb: List<FdbEntry>,
    val lldp: List<LldpNeighbor>,
) {
    val isSwitch: Boolean get() = fdb.isNotEmpty() || bridgeMac != null && lldp.isNotEmpty()
    val isRouter: Boolean get() = ipForwarding && addresses.size > 1

    fun portName(ifIndex: Int?): String? = ifIndex?.let { ports[it] ?: "port $it" }

    /** Every MAC this box may appear under (bridge MAC, LLDP chassis IDs it reports as itself). */
    fun ownMacs(): Set<String> = setOfNotNull(bridgeMac)
}

/** Reads system, interface, address, ARP, bridge forwarding and LLDP tables. */
class SnmpCollector(private val client: SnmpClient) {

    suspend fun collect(target: SnmpTarget, system: List<VarBind>): SnmpDetails {
        fun sys(oid: String) = system.firstOrNull { it.oid == oid }?.value?.let(::text)

        val ifName = client.walk(target, IF_NAME).associate { index(it.oid, IF_NAME).toInt() to text(it.value) }
        val ifDescr = if (ifName.values.all { it.isNullOrBlank() }) {
            client.walk(target, IF_DESCR).associate { index(it.oid, IF_DESCR).toInt() to text(it.value) }
        } else emptyMap()
        val ports = (ifDescr + ifName).mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()

        val masks = client.walk(target, IP_AD_MASK).associate { index(it.oid, IP_AD_MASK) to (it.value as? SnmpValue.Ip)?.value }
        val addresses = masks.mapNotNull { (ip, mask) ->
            val prefix = mask?.let { maskToPrefix(it) } ?: return@mapNotNull null
            if (ip.startsWith("127.") || ip == "0.0.0.0") null else ip to prefix
        }

        val arp = client.walk(target, IP_NET_TO_MEDIA_PHYS).mapNotNull { vb ->
            // index = ifIndex.a.b.c.d
            val ip = index(vb.oid, IP_NET_TO_MEDIA_PHYS).split('.').drop(1).joinToString(".")
            val mac = (vb.value as? SnmpValue.Bytes)?.let { macOf(it.value) } ?: return@mapNotNull null
            if (IpUtils.toLong(ip) == null) null else ip to mac
        }.toMap()

        val forwarding = client.get(target, listOf(IP_FORWARDING))?.firstOrNull()?.value.let {
            (it as? SnmpValue.Num)?.value == 1L
        }
        val bridgeMac = client.get(target, listOf(DOT1D_BASE_BRIDGE_ADDRESS))?.firstOrNull()?.value
            ?.let { (it as? SnmpValue.Bytes)?.value?.let(::macOf) }

        val basePortIfIndex = client.walk(target, DOT1D_BASE_PORT_IFINDEX).associate {
            index(it.oid, DOT1D_BASE_PORT_IFINDEX).toInt() to ((it.value as? SnmpValue.Num)?.value?.toInt())
        }

        // Prefer the VLAN-aware Q-BRIDGE table; fall back to the plain bridge table.
        var fdb = client.walk(target, DOT1Q_TP_FDB_PORT).mapNotNull { vb ->
            val idx = index(vb.oid, DOT1Q_TP_FDB_PORT).split('.')
            if (idx.size != 7) return@mapNotNull null
            val port = (vb.value as? SnmpValue.Num)?.value?.toInt() ?: return@mapNotNull null
            if (port == 0) return@mapNotNull null
            val mac = macFromIndex(idx.drop(1)) ?: return@mapNotNull null
            FdbEntry(mac, idx[0].toIntOrNull(), basePortIfIndex[port] ?: port, port)
        }
        if (fdb.isEmpty()) {
            fdb = client.walk(target, DOT1D_TP_FDB_PORT).mapNotNull { vb ->
                val idx = index(vb.oid, DOT1D_TP_FDB_PORT).split('.')
                val port = (vb.value as? SnmpValue.Num)?.value?.toInt() ?: return@mapNotNull null
                if (port == 0) return@mapNotNull null
                val mac = macFromIndex(idx) ?: return@mapNotNull null
                FdbEntry(mac, null, basePortIfIndex[port] ?: port, port)
            }
        }

        val locPortNames = client.walk(target, LLDP_LOC_PORT_DESC).associate {
            index(it.oid, LLDP_LOC_PORT_DESC).toInt() to text(it.value)
        }.toMutableMap()
        client.walk(target, LLDP_LOC_PORT_ID).forEach { vb ->
            val n = index(vb.oid, LLDP_LOC_PORT_ID).toInt()
            if (locPortNames[n].isNullOrBlank()) locPortNames[n] = text(vb.value)
        }
        val rem = HashMap<String, MutableMap<Int, SnmpValue>>()
        for (col in listOf(5, 7, 8, 9)) {
            val base = "$LLDP_REM_ENTRY.$col"
            client.walk(target, base).forEach { vb ->
                rem.getOrPut(index(vb.oid, base)) { HashMap() }[col] = vb.value
            }
        }
        val lldp = rem.mapNotNull { (idx, cols) ->
            // index = timeMark.localPortNum.remIndex
            val parts = idx.split('.')
            if (parts.size < 3) return@mapNotNull null
            val localPort = parts[1].toIntOrNull() ?: return@mapNotNull null
            val chassis = cols[5]?.let { v -> (v as? SnmpValue.Bytes)?.let { b -> macOf(b.value) ?: b.textOrNull() } }
            LldpNeighbor(
                localPortNum = localPort,
                localPortName = locPortNames[localPort] ?: ports[localPort],
                chassisId = chassis,
                portId = cols[7]?.let(::text),
                portDesc = cols[8]?.let(::text),
                sysName = cols[9]?.let(::text),
            )
        }

        return SnmpDetails(
            target = target,
            sysName = sys(SnmpClient.SYS_NAME),
            sysDescr = sys(SnmpClient.SYS_DESCR),
            sysObjectId = sys(SnmpClient.SYS_OBJECT_ID),
            sysLocation = sys(SnmpClient.SYS_LOCATION),
            bridgeMac = bridgeMac,
            ipForwarding = forwarding,
            ports = ports,
            addresses = addresses,
            arp = arp,
            fdb = fdb,
            lldp = lldp,
        )
    }

    companion object {
        const val IF_DESCR = "1.3.6.1.2.1.2.2.1.2"
        const val IF_NAME = "1.3.6.1.2.1.31.1.1.1.1"
        const val IP_FORWARDING = "1.3.6.1.2.1.4.1.0"
        const val IP_AD_MASK = "1.3.6.1.2.1.4.20.1.3"
        const val IP_NET_TO_MEDIA_PHYS = "1.3.6.1.2.1.4.22.1.2"
        const val DOT1D_BASE_BRIDGE_ADDRESS = "1.3.6.1.2.1.17.1.1.0"
        const val DOT1D_BASE_PORT_IFINDEX = "1.3.6.1.2.1.17.1.4.1.2"
        const val DOT1D_TP_FDB_PORT = "1.3.6.1.2.1.17.4.3.1.2"
        const val DOT1Q_TP_FDB_PORT = "1.3.6.1.2.1.17.7.1.2.2.1.2"
        const val LLDP_LOC_PORT_ID = "1.0.8802.1.1.2.1.3.7.1.3"
        const val LLDP_LOC_PORT_DESC = "1.0.8802.1.1.2.1.3.7.1.4"
        const val LLDP_REM_ENTRY = "1.0.8802.1.1.2.1.4.1.1"

        fun index(oid: String, root: String) = oid.removePrefix("$root.")

        fun text(v: SnmpValue): String? = when (v) {
            is SnmpValue.Bytes -> v.textOrNull()?.trim() ?: macOf(v.value) ?: v.hex()
            is SnmpValue.Num -> v.value.toString()
            is SnmpValue.Oid -> v.value
            is SnmpValue.Ip -> v.value
            else -> null
        }

        fun macOf(b: ByteArray): String? =
            if (b.size == 6) IpUtils.normalizeMac(b.joinToString("") { "%02X".format(it.toInt() and 0xff) }) else null

        fun macFromIndex(parts: List<String>): String? {
            if (parts.size != 6) return null
            val bytes = parts.map { it.toIntOrNull() ?: return null }
            if (bytes.any { it !in 0..255 }) return null
            return IpUtils.normalizeMac(bytes.joinToString("") { "%02X".format(it) })
        }

        fun maskToPrefix(mask: String): Int? {
            val v = IpUtils.toLong(mask) ?: return null
            val prefix = java.lang.Long.bitCount(v)
            // Must be contiguous ones.
            val expected = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            return if (v == expected) prefix else null
        }
    }
}
