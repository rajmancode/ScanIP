package com.scanip.app.topology

import com.scanip.app.net.IpUtils
import com.scanip.app.net.snmp.SnmpDetails

/** What the builder needs to know about each scanned device. */
data class TopoDevice(
    val ip: String,
    val mac: String?,
    val name: String,
    val isGateway: Boolean,
    val isSelf: Boolean,
)

/**
 * Builds a network map from scan results.
 *
 * - Switch-to-switch links come from LLDP, or are inferred from the switches' MAC tables.
 * - Each device is placed on the switch port where its MAC is learned, ignoring uplink ports.
 *   An edge port with several devices behind it gets an "unmanaged switch / AP" node.
 * - Anything that can't be placed hangs off its subnet (VLAN), which hangs off the router that
 *   serves it (from SNMP addresses or traceroute), else off the default gateway.
 */
class TopologyBuilder(
    private val devices: List<TopoDevice>,
    private val snmp: Map<String, SnmpDetails>,
    /** Subnet (CIDR) -> routers on the path from this phone, in order. */
    private val routes: Map<String, List<String>>,
    /** Subnets scanned, as CIDRs. */
    private val subnets: List<String>,
) {
    private val nodes = LinkedHashMap<String, TopoNode>()
    private val links = mutableListOf<TopoLink>()
    private val linked = HashSet<Pair<String, String>>()
    private val attachments = HashMap<String, String>()

    private val byIp = devices.associateBy { it.ip }
    private val switches = snmp.filterValues { it.isSwitch }

    /** All MACs that identify each switch. */
    private val switchMacs: Map<String, Set<String>> = switches.mapValues { (ip, d) ->
        d.ownMacs() + setOfNotNull(byIp[ip]?.mac)
    }

    /** switch ip -> port key -> MACs learned there (excluding the switch's own). */
    private val portMacs: Map<String, Map<Int, Set<String>>> = switches.mapValues { (ip, d) ->
        d.fdb.filter { it.mac !in switchMacs[ip].orEmpty() }
            .groupBy { it.ifIndex ?: it.bridgePort }
            .mapValues { (_, e) -> e.map { it.mac }.toSet() }
    }

    fun build(): Topology {
        devices.forEach { addDeviceNode(it) }
        // Routers also known only from SNMP or traceroute.
        snmp.keys.filter { it !in nodes }.forEach { ip ->
            nodes[ip] = TopoNode(ip, snmp[ip]?.sysName ?: ip, ip, kindFor(ip), ip)
        }

        val uplinks = HashMap<String, MutableSet<Int>>()
        linkSwitches(uplinks)
        val placed = placeDevicesOnPorts(uplinks)
        val routerFor = routerPerSubnet()
        val gateway = devices.firstOrNull { it.isGateway }?.ip

        // Switches that are not linked to anything: under the gateway's subnet.
        val connected = HashSet<String>()
        links.forEach { connected += it.a; connected += it.b }

        for (d in devices) {
            if (d.ip in placed || (d.ip in switches && d.ip in connected)) continue
            if (d.ip == gateway && d.ip in connected) continue
            val subnet = subnets.firstOrNull { IpUtils.cidrContains(it, d.ip) }
            val parentRouter = subnet?.let { routerFor[it] } ?: gateway
            if (parentRouter == d.ip) continue
            if (subnet != null) {
                val sid = "subnet:$subnet"
                if (sid !in nodes) {
                    nodes[sid] = TopoNode(sid, "Subnet $subnet", null, NodeKind.SUBNET)
                    if (parentRouter != null) link(parentRouter, sid)
                }
                link(sid, d.ip)
            } else if (parentRouter != null) {
                link(parentRouter, d.ip)
            }
        }

        // Router chains from traceroute: gateway -> next hop -> ...
        for ((_, hops) in routes) {
            var prev: String? = null
            for (hop in hops.map { routerIdentity(it) }) {
                if (hop !in nodes) nodes[hop] = TopoNode(hop, hop, "router", NodeKind.ROUTER, hop.takeIf { it in byIp })
                if (prev != null && prev != hop) link(prev, hop)
                prev = hop
            }
        }

        val root = gateway ?: switches.keys.firstOrNull() ?: devices.firstOrNull { it.isSelf }?.ip
        return Topology(nodes, links, root, attachments)
    }

    private fun addDeviceNode(d: TopoDevice) {
        val details = snmp[d.ip]
        val label = details?.sysName?.takeIf { it.isNotBlank() } ?: d.name
        nodes[d.ip] = TopoNode(d.ip, label, d.ip, kindFor(d.ip), d.ip)
    }

    private fun kindFor(ip: String): NodeKind {
        val d = snmp[ip]
        return when {
            byIp[ip]?.isGateway == true || d?.isRouter == true -> NodeKind.ROUTER
            d?.isSwitch == true -> NodeKind.SWITCH
            else -> NodeKind.DEVICE
        }
    }

    /** Port of switch [x] on which any MAC of switch [y] is learned. */
    private fun seesPort(x: String, y: String): Int? {
        val macs = switchMacs[y].orEmpty()
        if (macs.isEmpty()) return null
        return portMacs[x].orEmpty().entries.firstOrNull { (_, m) -> m.any { it in macs } }?.key
    }

    private fun linkSwitches(uplinks: MutableMap<String, MutableSet<Int>>) {
        val ips = switches.keys.sortedBy { IpUtils.toLong(it) }

        // Every port facing another managed switch is an uplink.
        for (x in ips) for (y in ips) if (x != y) {
            seesPort(x, y)?.let { uplinks.getOrPut(x) { HashSet() } += it }
        }

        // 1) LLDP.
        for (x in ips) {
            val d = switches.getValue(x)
            for (n in d.lldp) {
                val y = ips.firstOrNull { y ->
                    y != x && (n.chassisId != null && (n.chassisId in switchMacs[y].orEmpty() || n.chassisId == y) ||
                        n.sysName != null && n.sysName.equals(switches.getValue(y).sysName, ignoreCase = true))
                } ?: continue
                val portX = n.localPortName
                d.ports.entries.firstOrNull { it.value == portX }?.key?.let { uplinks.getOrPut(x) { HashSet() } += it }
                link(x, y, portX, n.portDesc?.takeIf { it.isNotBlank() } ?: n.portId)
            }
        }

        // 2) MAC-table inference for pairs LLDP didn't connect.
        for (i in ips.indices) for (j in i + 1 until ips.size) {
            val x = ips[i]
            val y = ips[j]
            if (isLinked(x, y)) continue
            val px = seesPort(x, y)
            val py = seesPort(y, x)
            if (px == null && py == null) continue
            val between = ips.any { z ->
                if (z == x || z == y) return@any false
                val xz = seesPort(x, z)
                val yz = seesPort(y, z)
                if (xz == null && yz == null) return@any false
                val condX = px == null || xz == null || xz == px
                val condY = py == null || yz == null || yz == py
                val zx = seesPort(z, x)
                val zy = seesPort(z, y)
                condX && condY && (zx == null || zy == null || zx != zy)
            }
            if (!between) {
                link(x, y, switches.getValue(x).portName(px), switches.getValue(y).portName(py), inferred = true)
            }
        }
    }

    /** Returns IPs of devices that were placed on a switch port. */
    private fun placeDevicesOnPorts(uplinks: Map<String, Set<Int>>): Set<String> {
        val placed = HashSet<String>()
        // switch -> port -> devices on that edge port
        val perPort = HashMap<Pair<String, Int>, MutableList<TopoDevice>>()
        for (d in devices) {
            val mac = d.mac ?: continue
            val isSwitch = d.ip in switches
            var best: Pair<String, Int>? = null
            var bestSize = Int.MAX_VALUE
            for ((sw, ports) in portMacs) {
                if (sw == d.ip) continue
                for ((port, macs) in ports) {
                    if (mac !in macs || port in uplinks[sw].orEmpty()) continue
                    if (macs.size < bestSize) {
                        best = sw to port
                        bestSize = macs.size
                    }
                }
            }
            if (best != null && !(isSwitch && isLinked(d.ip, best.first))) {
                perPort.getOrPut(best) { mutableListOf() } += d
            }
        }
        for ((key, devs) in perPort) {
            val (sw, port) = key
            val portName = switches.getValue(sw).portName(port)
            val swName = nodes[sw]?.label ?: sw
            // Several devices behind one port: an unmanaged switch or Wi-Fi access point.
            val parent = if (devs.size > 1) {
                val id = "unmanaged:$sw:$port"
                nodes[id] = TopoNode(id, "Unmanaged switch / AP", "on $swName $portName", NodeKind.UNMANAGED)
                link(sw, id, portName, null)
                id
            } else sw
            for (d in devs) {
                if (parent == sw) link(sw, d.ip, portName, null) else link(parent, d.ip)
                attachments[d.ip] = "$swName · $portName" + if (devs.size > 1) " (shared)" else ""
                placed += d.ip
            }
        }
        return placed
    }

    /** Which router serves each subnet: the SNMP device that has an address in it, or the last traceroute hop. */
    private fun routerPerSubnet(): Map<String, String> {
        val result = HashMap<String, String>()
        for (subnet in subnets) {
            val owner = snmp.entries.firstOrNull { (_, d) ->
                d.isRouter && d.addresses.any { (ip, _) -> IpUtils.cidrContains(subnet, ip) }
            }?.key
            val viaTrace = routes[subnet]?.lastOrNull()?.let { routerIdentity(it) }
            (owner ?: viaTrace)?.let { result[subnet] = it }
        }
        return result
    }

    /** Maps a router interface address to the node of the router that owns it. */
    private fun routerIdentity(ip: String): String {
        if (ip in byIp || ip in snmp) return ip
        return snmp.entries.firstOrNull { (_, d) -> d.addresses.any { it.first == ip } }?.key ?: ip
    }

    private fun isLinked(a: String, b: String) = (a to b) in linked || (b to a) in linked

    private fun link(a: String, b: String, portA: String? = null, portB: String? = null, inferred: Boolean = false) {
        if (a == b || isLinked(a, b)) return
        linked += a to b
        links += TopoLink(a, b, portA, portB, inferred)
    }
}
