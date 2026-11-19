package com.scanip.app

import android.app.Application
import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanip.app.model.Device
import com.scanip.app.model.NetworkSnapshot
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
) {
    val sortedDevices: List<Device>
        get() = devices.values.sortedBy { it.ipValue }

    val visibleDevices: List<Device>
        get() {
            val q = filter.trim()
            if (q.isEmpty()) return sortedDevices
            return sortedDevices.filter { d ->
                listOfNotNull(d.ip, d.displayName, d.hostname, d.netbiosName, d.mdnsName, d.mac, d.manufacturer,
                    d.upnp?.modelName).any { it.contains(q, ignoreCase = true) } ||
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

    init {
        viewModelScope.launch { oui.load() }
        refreshNetwork()
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
        val targets = IpUtils.parseTargets(s.rangeInput)
        if (targets == null) {
            _state.update {
                it.copy(message = "Invalid range. Use e.g. 192.168.1.1-254 or 192.168.1.0/24 (max ${IpUtils.MAX_HOSTS} hosts).")
            }
            return
        }
        if (s.rangeInput != autoRange) prefs.edit().putString(KEY_RANGE, s.rangeInput).apply()
        else prefs.edit().remove(KEY_RANGE).apply()

        scanJob = viewModelScope.launch { runScan(targets, s.network) }
    }

    private suspend fun runScan(targets: List<Long>, snap: NetworkSnapshot?) {
        val tools = tools(snap)
        val targetSet = targets.map { IpUtils.toIp(it) }.toHashSet()
        _state.update {
            it.copy(scanning = true, devices = emptyMap(), scanned = 0, total = targets.size, phase = "Pinging hosts")
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
            _state.update { it.copy(lastScanFinishedAt = System.currentTimeMillis()) }
        } finally {
            try { lock?.release() } catch (_: Exception) {}
            _state.update { it.copy(scanning = false, phase = null) }
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

        val present = java.util.Collections.synchronizedList(mutableListOf<String>())
        coroutineScope {
            val limiter = Semaphore(NEIGHBOUR_PARALLELISM)
            for (ip in remaining) {
                launch {
                    if (limiter.withPermit { tools.prober.neighbourProbe(ip) }) present.add(ip)
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
            updateDevice(ip) { it.copy(isAlive = r.alive, latencyMs = r.latencyMs) }
            _state.update { it.copy(message = if (r.alive) "$ip is up (${r.latencyMs ?: "?"} ms)" else "$ip did not respond") }
            if (r.alive) enrich(ip, tools)
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
        val header = "Status,Name,IP,Manufacturer,MAC,Hostname,NetBIOS,Workgroup,mDNS,Model,Open ports,Latency ms"
        val rows = _state.value.sortedDevices.map { d ->
            listOf(
                if (d.isAlive) "Online" else "Offline", d.displayName, d.ip, d.manufacturer, d.mac, d.hostname,
                d.netbiosName, d.workgroup, d.mdnsName,
                listOfNotNull(d.upnp?.modelName, d.upnp?.modelNumber).joinToString(" "),
                d.openPorts.joinToString(" ") { "${it.port}/${it.service}" }, d.latencyMs?.toString(),
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
        private const val SWEEP_PARALLELISM = 48
        private const val ENRICH_PARALLELISM = 24
        private const val NEIGHBOUR_PARALLELISM = 128
    }
}
