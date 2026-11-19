package com.scanip.app

import android.app.Application
import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanip.app.model.Device
import com.scanip.app.model.NetworkSnapshot
import com.scanip.app.model.SnmpSummary
import com.scanip.app.model.SubnetEntry
import com.scanip.app.net.Traceroute
import com.scanip.app.net.snmp.SnmpClient
import com.scanip.app.net.snmp.SnmpCodec
import com.scanip.app.net.snmp.SnmpCollector
import com.scanip.app.net.snmp.SnmpDetails
import com.scanip.app.topology.TopoDevice
import com.scanip.app.topology.Topology
import com.scanip.app.topology.TopologyBuilder
import com.scanip.app.net.ArpTable
import com.scanip.app.net.HostProber
import com.scanip.app.net.IpUtils
import com.scanip.app.net.NameResolver
import com.scanip.app.net.NetworkInfoProvider
import com.scanip.app.net.OuiDatabase
import com.scanip.app.net.PortScanner
import com.scanip.app.net.UpnpDiscovery
import com.scanip.app.net.WakeOnLan
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.DatagramSocket
import javax.net.SocketFactory

data class ScanUiState(
    val network: NetworkSnapshot? = null,
    val rangeInput: String = "",
    val devices: Map<String, Device> = emptyMap(),
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    /** Text describing the current scan stage, shown above the progress bar. */
    val phase: String? = null,
    val filter: String = "",
    val message: String? = null,
    val ouiInstalled: Boolean = false,
    val ouiBusy: Boolean = false,
    val portScansRunning: Set<String> = emptySet(),
    val lastScanFinishedAt: Long? = null,
    /** Extra subnets (VLANs) to scan besides the range above. */
    val subnets: List<SubnetEntry> = emptyList(),
    val snmpEnabled: Boolean = true,
    val snmpCommunities: String = "public",
    val topology: Topology? = null,
    /** Progress text while looking for VLANs, null when idle. */
    val discovering: String? = null,
    /** Subnets covered by the last scan, used to group the device list. */
    val scannedSubnets: List<String> = emptyList(),
) {
    fun subnetOf(ip: String): String? = scannedSubnets.firstOrNull { IpUtils.cidrContains(it, ip) }

    val sortedDevices: List<Device>
        get() = devices.values.sortedBy { it.ipValue }

    val visibleDevices: List<Device>
        get() {
            val q = filter.trim()
            if (q.isEmpty()) return sortedDevices
            return sortedDevices.filter { d ->
                listOfNotNull(d.ip, d.displayName, d.hostname, d.netbiosName, d.mdnsName, d.mac, d.manufacturer,
                    d.upnp?.modelName, d.snmp?.description, d.attachedTo).any { it.contains(q, ignoreCase = true) } ||
                    d.openPorts.any { it.service.contains(q, ignoreCase = true) }
            }
        }
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("scanip", Context.MODE_PRIVATE)
    private val networkInfo = NetworkInfoProvider(app)
    private val oui = OuiDatabase(app)

    private val _state = MutableStateFlow(ScanUiState(ouiInstalled = oui.isInstalled))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var autoRange: String? = null

    /** Full SNMP data from the last scan, keyed by device IP. */
    private var snmpDetails: Map<String, SnmpDetails> = emptyMap()

    init {
        viewModelScope.launch { oui.load() }
        _state.update {
            it.copy(
                subnets = loadSubnets(),
                snmpEnabled = prefs.getBoolean(KEY_SNMP_ENABLED, true),
                snmpCommunities = prefs.getString(KEY_SNMP_COMMUNITIES, "public") ?: "public",
            )
        }
        refreshNetwork()
    }

    // ----- Subnets (VLANs) and SNMP settings -----

    private fun loadSubnets(): List<SubnetEntry> =
        prefs.getString(KEY_SUBNETS, "").orEmpty().lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 3 || IpUtils.parseCidr(p[0]) == null) return@mapNotNull null
            SubnetEntry(p[0], p[1] == "1", runCatching { SubnetEntry.Source.valueOf(p[2]) }.getOrDefault(SubnetEntry.Source.MANUAL))
        }

    private fun saveSubnets(list: List<SubnetEntry>) {
        prefs.edit().putString(KEY_SUBNETS, list.joinToString("\n") { "${it.cidr}|${if (it.enabled) 1 else 0}|${it.source}" }).apply()
    }

    private fun updateSubnets(transform: (List<SubnetEntry>) -> List<SubnetEntry>) {
        _state.update { s -> s.copy(subnets = transform(s.subnets)).also { saveSubnets(it.subnets) } }
    }

    /** Adds a subnet like "192.168.20.0/24". Returns an error message, or null on success. */
    fun addSubnet(text: String, source: SubnetEntry.Source = SubnetEntry.Source.MANUAL): String? {
        val parsed = IpUtils.parseCidr(text) ?: return "Use CIDR notation, e.g. 192.168.20.0/24"
        val (net, prefix) = parsed
        if (prefix < 20) return "That subnet is too large. Use /20 or smaller (up to ${IpUtils.MAX_HOSTS} hosts)."
        val cidr = "${IpUtils.toIp(net)}/$prefix"
        if (_state.value.subnets.any { it.cidr == cidr }) return null
        updateSubnets { it + SubnetEntry(cidr, true, source) }
        return null
    }

    fun setSubnetEnabled(cidr: String, enabled: Boolean) =
        updateSubnets { list -> list.map { if (it.cidr == cidr) it.copy(enabled = enabled) else it } }

    fun removeSubnet(cidr: String) = updateSubnets { list -> list.filter { it.cidr != cidr } }

    fun setSnmpEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SNMP_ENABLED, enabled).apply()
        _state.update { it.copy(snmpEnabled = enabled) }
    }

    fun setSnmpCommunities(text: String) {
        prefs.edit().putString(KEY_SNMP_COMMUNITIES, text).apply()
        _state.update { it.copy(snmpCommunities = text) }
    }

    private fun communities(): List<String> =
        _state.value.snmpCommunities.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("public") }

    /**
     * Looks for other VLANs: asks the gateway (and known routers) for their interface addresses over
     * SNMP, then probes x.y.Z.1 and x.y.Z.254 across this network's /16 for gateways that answer.
     */
    fun discoverSubnets() {
        if (_state.value.discovering != null || _state.value.scanning) return
        viewModelScope.launch {
            val snap = networkInfo.current()
            val localIp = snap.localIp
            val prefix = snap.prefixLength
            if (localIp == null || prefix == null) {
                _state.update { it.copy(message = "Connect to Wi-Fi or Ethernet first.") }
                return@launch
            }
            val tools = tools(snap)
            val ownCidr = IpUtils.cidr(localIp, prefix)
            val found = linkedSetOf<Pair<String, SubnetEntry.Source>>()
            try {
                // 1) SNMP: interface addresses of the gateway and any router seen in the last scan.
                if (_state.value.snmpEnabled) {
                    _state.update { it.copy(discovering = "Asking routers over SNMP…") }
                    val client = SnmpClient(tools.bind)
                    val routers = (listOfNotNull(snap.gateway) + snmpDetails.filterValues { it.isRouter }.keys).distinct()
                    for (r in routers) {
                        val known = snmpDetails[r]
                        val addresses = known?.addresses ?: run {
                            val (target, system) = client.discover(r, communities()) ?: return@run emptyList()
                            SnmpCollector(client).collect(target, system).addresses
                        }
                        for ((ip, p) in addresses) {
                            if (p in 20..30) IpUtils.cidr(ip, p)?.let { found += it to SubnetEntry.Source.SNMP }
                        }
                    }
                }

                // 2) Gateway sweep across this /16.
                if (IpUtils.isPrivate(localIp)) {
                    val base = IpUtils.toLong(localIp)!! and 0xFFFF0000L
                    val candidates = (0..255).flatMap { z -> listOf(base or (z.toLong() shl 8) or 1L, base or (z.toLong() shl 8) or 254L) }
                        .map { IpUtils.toIp(it) }
                        .filter { ownCidr == null || !IpUtils.cidrContains(ownCidr, it) }
                    var done = 0
                    _state.update { it.copy(discovering = "Probing gateways 0 / ${candidates.size}") }
                    coroutineScope {
                        val limiter = Semaphore(64)
                        for (ip in candidates) launch {
                            val alive = limiter.withPermit { tools.prober.probe(ip).alive }
                            if (alive) IpUtils.cidr(ip, 24)?.let { synchronized(found) { found += it to SubnetEntry.Source.DISCOVERED } }
                            val n = synchronized(this@ScanViewModel) { ++done }
                            if (n % 16 == 0) _state.update { it.copy(discovering = "Probing gateways $n / ${candidates.size}") }
                        }
                    }
                }
            } finally {
                val added = found.filter { (cidr, _) -> cidr != ownCidr }
                    .count { (cidr, src) -> _state.value.subnets.none { it.cidr == cidr } && addSubnet(cidr, src) == null }
                _state.update {
                    it.copy(
                        discovering = null,
                        message = if (added == 0) "No new subnets found." else "Found $added new subnet(s). They are included in the next scan.",
                    )
                }
            }
        }
    }

    fun refreshNetwork() {
        val snap = networkInfo.current()
        val newAuto = if (snap.firstHost != null && snap.lastHost != null) {
            "${IpUtils.toIp(snap.firstHost)}-${IpUtils.toIp(snap.lastHost)}"
        } else null
        _state.update { s ->
            val saved = prefs.getString(KEY_RANGE, null)
            val keepUserRange = s.rangeInput.isNotBlank() && s.rangeInput != autoRange
            val range = when {
                keepUserRange -> s.rangeInput
                s.rangeInput.isBlank() && saved != null -> saved
                else -> newAuto ?: s.rangeInput
            }
            s.copy(network = snap, rangeInput = range)
        }
        autoRange = newAuto
    }

    fun resetRange() {
        prefs.edit().remove(KEY_RANGE).apply()
        _state.update { it.copy(rangeInput = autoRange ?: "") }
    }

    fun setRange(text: String) {
        _state.update { it.copy(rangeInput = text) }
    }

    fun setFilter(text: String) = _state.update { it.copy(filter = text) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun toggleScan() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            return
        }
        refreshNetwork()
        val s = _state.value
        val base = IpUtils.parseTargets(s.rangeInput)
        val extra = s.subnets.filter { it.enabled }.flatMap { e ->
            val (net, prefix) = IpUtils.parseCidr(e.cidr) ?: return@flatMap emptyList()
            IpUtils.hostRange(IpUtils.toIp(net), prefix)?.toList().orEmpty()
        }
        val targets = base?.let { (it + extra).distinct().sorted() }
        if (targets != null && targets.size > MAX_TOTAL_HOSTS) {
            _state.update { it.copy(message = "Too many addresses (${targets.size}). Disable some subnets; the limit is $MAX_TOTAL_HOSTS.") }
            return
        }
        if (targets == null) {
            _state.update {
                it.copy(message = "Invalid range. Use e.g. 192.168.1.1-254 or 192.168.1.0/24 (max ${IpUtils.MAX_HOSTS} hosts).")
            }
            return
        }
        if (s.rangeInput != autoRange) prefs.edit().putString(KEY_RANGE, s.rangeInput).apply()
        else prefs.edit().remove(KEY_RANGE).apply()

        val scanned = buildList {
            s.network?.let { n -> if (n.localIp != null && n.prefixLength != null) IpUtils.cidr(n.localIp, n.prefixLength)?.let(::add) }
            addAll(s.subnets.filter { it.enabled }.map { it.cidr })
        }.distinct()
        scanJob = viewModelScope.launch { runScan(targets, s.network, scanned) }
    }

    private suspend fun runScan(targets: List<Long>, snap: NetworkSnapshot?, scannedSubnets: List<String>) {
        val tools = tools(snap)
        val targetSet = targets.map { IpUtils.toIp(it) }.toHashSet()
        snmpDetails = emptyMap()
        _state.update {
            it.copy(
                scanning = true, devices = emptyMap(), scanned = 0, total = targets.size, phase = "Pinging hosts",
                topology = null, scannedSubnets = scannedSubnets,
            )
        }

        val wifi = getApplication<Application>().applicationContext.getSystemService(WifiManager::class.java)
        val lock = try {
            wifi?.createMulticastLock("scanip")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
            null
        }
        try {
            coroutineScope {
                // UPnP runs alongside the sweep: it also finds hosts that ignore ping.
                launch {
                    tools.upnp.search { resp ->
                        if (resp.ip !in targetSet) return@search
                        ensureDevice(resp.ip, snap)
                        launch {
                            val info = tools.upnp.describe(resp)
                            updateDevice(resp.ip) { it.copy(upnp = info) }
                        }
                    }
                }

                val sweep = Semaphore(SWEEP_PARALLELISM)
                val enrich = Semaphore(ENRICH_PARALLELISM)
                for (value in targets) {
                    launch {
                        val ip = IpUtils.toIp(value)
                        val result = sweep.withPermit { tools.prober.probe(ip) }
                        _state.update { it.copy(scanned = it.scanned + 1) }
                        if (result.alive || ip == snap?.localIp) {
                            ensureDevice(ip, snap)
                            updateDevice(ip) { it.copy(latencyMs = result.latencyMs ?: it.latencyMs) }
                            launch { enrich.withPermit { enrich(ip, tools) } }
                        }
                    }
                }
            }
            findFirewalledHosts(targets, snap, tools)
            mergeArp()
            if (_state.value.snmpEnabled) querySnmp(tools)
            val routes = traceSubnets(scannedSubnets, snap)
            buildTopology(routes, scannedSubnets)
            _state.update { it.copy(lastScanFinishedAt = System.currentTimeMillis()) }
        } finally {
            try { lock?.release() } catch (_: Exception) {}
            _state.update { it.copy(scanning = false, phase = null) }
        }
    }

    /** Asks every found device for SNMP data; switches and routers answer with their tables. */
    private suspend fun querySnmp(tools: Tools) {
        val ips = _state.value.devices.keys.filter { it != _state.value.network?.localIp }
        if (ips.isEmpty()) return
        _state.update { it.copy(scanned = 0, total = ips.size, phase = "Querying SNMP") }
        val client = SnmpClient(tools.bind)
        val collector = SnmpCollector(client)
        val communities = communities()
        val results = java.util.concurrent.ConcurrentHashMap<String, SnmpDetails>()
        coroutineScope {
            val probe = Semaphore(32)
            val walk = Semaphore(4)
            for (ip in ips) launch {
                val hit = probe.withPermit { client.discover(ip, communities) }
                _state.update { it.copy(scanned = it.scanned + 1) }
                if (hit != null) {
                    val details = walk.withPermit { collector.collect(hit.first, hit.second) }
                    results[ip] = details
                    updateDevice(ip) { it.copy(snmp = summarize(details)) }
                }
            }
        }
        snmpDetails = results.toMap()

        // Router and switch ARP tables give MAC addresses for every VLAN.
        val arp = HashMap<String, String>()
        results.values.forEach { arp.putAll(it.arp) }
        if (arp.isNotEmpty()) {
            _state.update { s ->
                s.copy(devices = s.devices.mapValues { (ip, d) ->
                    val mac = d.mac ?: arp[ip]
                    d.copy(mac = mac, vendor = d.vendor ?: oui.lookup(mac))
                })
            }
        }
    }

    private fun summarize(d: SnmpDetails) = SnmpSummary(
        name = d.sysName?.takeIf { it.isNotBlank() },
        description = d.sysDescr?.takeIf { it.isNotBlank() },
        location = d.sysLocation?.takeIf { it.isNotBlank() },
        isSwitch = d.isSwitch,
        isRouter = d.isRouter,
        portCount = d.ports.size,
        lldpNeighbors = d.lldp.size,
        macTableSize = d.fdb.size,
        addresses = d.addresses.map { (ip, p) -> "$ip/$p" },
        community = d.target.community,
        version = if (d.target.version == SnmpCodec.V2C) "v2c" else "v1",
    )

    /** Traceroute to one live host (or the .1) in every subnet that isn't ours. */
    private suspend fun traceSubnets(subnets: List<String>, snap: NetworkSnapshot?): Map<String, List<String>> {
        val own = snap?.let { n -> if (n.localIp != null && n.prefixLength != null) IpUtils.cidr(n.localIp, n.prefixLength) else null }
        val remote = subnets.filter { it != own }
        if (remote.isEmpty()) return emptyMap()
        _state.update { it.copy(scanned = 0, total = remote.size, phase = "Tracing routes") }
        val devices = _state.value.sortedDevices
        val result = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        coroutineScope {
            for (subnet in remote) launch {
                val target = devices.firstOrNull { IpUtils.cidrContains(subnet, it.ip) }?.ip
                    ?: IpUtils.parseCidr(subnet)?.let { (net, _) -> IpUtils.toIp(net + 1) }
                if (target != null) result[subnet] = Traceroute.routers(target)
                _state.update { it.copy(scanned = it.scanned + 1) }
            }
        }
        return result
    }

    private fun buildTopology(routes: Map<String, List<String>>, subnets: List<String>) {
        val devices = _state.value.sortedDevices
        val topo = TopologyBuilder(
            devices = devices.map { TopoDevice(it.ip, it.mac, it.displayName, it.isGateway, it.isSelf) },
            snmp = snmpDetails,
            routes = routes,
            subnets = subnets,
        ).build()
        _state.update { s ->
            s.copy(
                topology = topo,
                devices = s.devices.mapValues { (ip, d) -> d.copy(attachedTo = topo.attachments[ip]) },
            )
        }
    }

    /** Second pass for hosts that ignored ping and TCP: detects them by their ARP reply. */
    private suspend fun findFirewalledHosts(targets: List<Long>, snap: NetworkSnapshot?, tools: Tools) {
        // Only addresses on our own link get ARP; routed addresses would all just time out.
        val local = if (snap?.localIp != null && snap.prefixLength != null) {
            IpUtils.hostRange(snap.localIp, snap.prefixLength)
        } else null
        if (local == null) return
        val found = _state.value.devices.keys
        val remaining = targets.filter { it in local }.map { IpUtils.toIp(it) }.filter { it !in found }
        if (remaining.isEmpty()) return
        _state.update { it.copy(scanned = 0, total = remaining.size, phase = "Looking for firewalled hosts") }

        val candidates = java.util.Collections.synchronizedList(mutableListOf<String>())
        coroutineScope {
            val limiter = Semaphore(NEIGHBOUR_PARALLELISM)
            for (ip in remaining) {
                launch {
                    val p = limiter.withPermit { tools.prober.neighbourProbe(ip) }
                    if (p != HostProber.Presence.ABSENT) candidates.add(ip)
                    _state.update { it.copy(scanned = it.scanned + 1) }
                }
            }
        }
        if (candidates.isEmpty()) return

        // Confirm with a long wait: devices that recently left are still in the ARP cache and
        // only fail once the kernel has re-checked them. A device that is really there stays silent.
        _state.update { it.copy(scanned = 0, total = candidates.size, phase = "Confirming firewalled hosts") }
        val present = java.util.Collections.synchronizedList(mutableListOf<String>())
        coroutineScope {
            val limiter = Semaphore(NEIGHBOUR_PARALLELISM)
            for (ip in candidates.toList()) {
                launch {
                    val p = limiter.withPermit { tools.prober.neighbourProbe(ip, HostProber.CONFIRM_TIMEOUT_MS) }
                    if (p != HostProber.Presence.ABSENT) present.add(ip)
                    _state.update { it.copy(scanned = it.scanned + 1) }
                }
            }
        }
        // Some routers answer ARP for every address (proxy ARP). Then this test means nothing.
        if (remaining.size >= 16 && present.size > remaining.size * 0.6) {
            _state.update {
                it.copy(message = "This network answers ARP for every address, so firewalled hosts can't be detected.")
            }
            return
        }
        coroutineScope {
            val enrich = Semaphore(ENRICH_PARALLELISM)
            for (ip in present) {
                ensureDevice(ip, snap)
                updateDevice(ip) { it.copy(firewalled = true) }
                launch { enrich.withPermit { enrich(ip, tools) } }
            }
        }
    }

    private class Tools(
        val network: Network?,
        val prober: HostProber,
        val resolver: NameResolver,
        val ports: PortScanner,
        val upnp: UpnpDiscovery,
        val bind: (DatagramSocket) -> Unit,
    )

    private fun tools(snap: NetworkSnapshot?): Tools {
        val network = networkInfo.localNetwork()
        val factory: SocketFactory = network?.socketFactory ?: SocketFactory.getDefault()
        val bind: (DatagramSocket) -> Unit = { socket ->
            try { network?.bindSocket(socket) } catch (_: Exception) {}
        }
        val dns = (snap?.dnsServers.orEmpty() + listOfNotNull(snap?.gateway)).distinct()
        return Tools(
            network = network,
            prober = HostProber(factory),
            resolver = NameResolver(dns, bind),
            ports = PortScanner(factory),
            upnp = UpnpDiscovery(bind) { url ->
                (network?.openConnection(url) ?: url.openConnection()) as java.net.HttpURLConnection
            },
            bind = bind,
        )
    }

    private suspend fun enrich(ip: String, tools: Tools) = coroutineScope {
        val dns = async { tools.resolver.reverseDns(ip) }
        val nb = async { tools.resolver.netbios(ip) }
        val mdns = async { tools.resolver.mdns(ip) }
        val quick = async { tools.ports.scan(ip, PortScanner.QUICK_PORTS, grabBanners = false) }

        val nbInfo = nb.await()
        updateDevice(ip) { d ->
            val mac = d.mac ?: nbInfo?.mac
            d.copy(
                netbiosName = nbInfo?.name ?: d.netbiosName,
                workgroup = nbInfo?.workgroup ?: d.workgroup,
                mac = mac,
                vendor = d.vendor ?: oui.lookup(mac),
            )
        }
        val host = dns.await()
        updateDevice(ip) { it.copy(hostname = host ?: it.hostname) }
        val mdnsName = mdns.await()
        updateDevice(ip) { it.copy(mdnsName = mdnsName ?: it.mdnsName) }
        val open = quick.await()
        updateDevice(ip) { d ->
            if (d.portsScanned) d else d.copy(openPorts = (d.openPorts + open).distinctBy { it.port }.sortedBy { it.port })
        }
    }

    private fun mergeArp() {
        val arp = ArpTable.read()
        if (arp.isEmpty()) return
        _state.update { s ->
            s.copy(devices = s.devices.mapValues { (ip, d) ->
                val mac = d.mac ?: arp[ip]
                d.copy(mac = mac, vendor = d.vendor ?: oui.lookup(mac))
            })
        }
    }

    private fun ensureDevice(ip: String, snap: NetworkSnapshot?) {
        _state.update { s ->
            if (ip in s.devices) s
            else s.copy(devices = s.devices + (ip to Device(
                ip = ip,
                ipValue = IpUtils.toLong(ip) ?: 0,
                isGateway = ip == snap?.gateway,
                isSelf = ip == snap?.localIp,
            )))
        }
    }

    private fun updateDevice(ip: String, transform: (Device) -> Device) {
        _state.update { s ->
            val d = s.devices[ip] ?: return@update s
            s.copy(devices = s.devices + (ip to transform(d)))
        }
    }

    // ----- Per-device actions -----

    fun scanPorts(ip: String, ports: Collection<Int> = PortScanner.COMMON_PORTS.keys) {
        if (ip in _state.value.portScansRunning) return
        _state.update { it.copy(portScansRunning = it.portScansRunning + ip) }
        viewModelScope.launch {
            try {
                val tools = tools(_state.value.network)
                val open = tools.ports.scan(ip, ports, grabBanners = true)
                updateDevice(ip) { it.copy(openPorts = open, portsScanned = true) }
                _state.update { it.copy(message = "${open.size} open port(s) on $ip") }
            } finally {
                _state.update { it.copy(portScansRunning = it.portScansRunning - ip) }
            }
        }
    }

    fun refreshDevice(ip: String) {
        viewModelScope.launch {
            val tools = tools(_state.value.network)
            val r = tools.prober.probe(ip)
            if (r.alive) {
                updateDevice(ip) { it.copy(isAlive = true, latencyMs = r.latencyMs, firewalled = false) }
                _state.update { it.copy(message = "$ip is up (${r.latencyMs ?: "?"} ms)") }
                enrich(ip, tools)
                return@launch
            }
            // No ping or TCP reply: check whether it still answers ARP (firewalled host).
            val onLink = state.value.network?.let { n ->
                if (n.localIp == null || n.prefixLength == null) false
                else IpUtils.hostRange(n.localIp, n.prefixLength)?.contains(IpUtils.toLong(ip) ?: -1) == true
            } == true
            _state.update { it.copy(message = "No ping reply from $ip, checking ARP…") }
            val present = onLink &&
                tools.prober.neighbourProbe(ip, HostProber.CONFIRM_TIMEOUT_MS) != HostProber.Presence.ABSENT
            updateDevice(ip) { it.copy(isAlive = present, latencyMs = null, firewalled = present || it.firewalled) }
            _state.update {
                it.copy(message = if (present) "$ip is up (firewalled, answers ARP only)" else "$ip did not respond")
            }
        }
    }

    fun wake(device: Device) {
        val mac = device.mac ?: return
        viewModelScope.launch {
            val snap = _state.value.network
            val broadcast = if (snap?.localIp != null && snap.prefixLength != null && snap.prefixLength < 31) {
                IpUtils.hostRange(snap.localIp, snap.prefixLength)?.let { IpUtils.toIp(it.last + 1) }
            } else null
            val ok = WakeOnLan.send(mac, broadcast, tools(snap).bind)
            _state.update { it.copy(message = if (ok) "Wake-on-LAN packet sent to $mac" else "Could not send Wake-on-LAN packet") }
        }
    }

    fun updateVendorDatabase() {
        if (_state.value.ouiBusy) return
        _state.update { it.copy(ouiBusy = true, message = "Downloading vendor database…") }
        viewModelScope.launch {
            val msg = try {
                val n = oui.update()
                _state.update { s ->
                    s.copy(devices = s.devices.mapValues { (_, d) -> d.copy(vendor = oui.lookup(d.mac) ?: d.vendor) })
                }
                "Vendor database updated ($n manufacturers)"
            } catch (e: Exception) {
                "Vendor database download failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _state.update { it.copy(ouiBusy = false, ouiInstalled = oui.isInstalled, message = msg) }
        }
    }

    fun exportCsv(): String {
        val header = "Status,Name,IP,Manufacturer,MAC,Hostname,NetBIOS,Workgroup,mDNS,Model,Open ports,Latency ms,Connected to,SNMP description"
        val rows = _state.value.sortedDevices.map { d ->
            listOf(
                if (d.isAlive) "Online" else "Offline", d.displayName, d.ip, d.manufacturer, d.mac, d.hostname,
                d.netbiosName, d.workgroup, d.mdnsName,
                listOfNotNull(d.upnp?.modelName, d.upnp?.modelNumber).joinToString(" "),
                d.openPorts.joinToString(" ") { "${it.port}/${it.service}" }, d.latencyMs?.toString(),
                d.attachedTo, d.snmp?.description,
            ).joinToString(",") { csv(it) }
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun csv(v: String?): String {
        val s = v ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    companion object {
        private const val KEY_RANGE = "range"
        private const val KEY_SUBNETS = "subnets"
        private const val KEY_SNMP_ENABLED = "snmp_enabled"
        private const val KEY_SNMP_COMMUNITIES = "snmp_communities"
        private const val MAX_TOTAL_HOSTS = 16_384
        private const val SWEEP_PARALLELISM = 48
        private const val ENRICH_PARALLELISM = 24
        private const val NEIGHBOUR_PARALLELISM = 128
    }
}
