package com.scanip.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanip.app.ScanUiState
import com.scanip.app.ScanViewModel
import com.scanip.app.model.Device
import com.scanip.app.model.NetworkSnapshot
import com.scanip.app.net.IpUtils
import com.scanip.app.ui.theme.OnlineGreen

@Composable
fun ScanIPApp(vm: ScanViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedIp by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!state.scanning) vm.refreshNetwork() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val selected = selectedIp?.let { state.devices[it] }
    if (selected != null) {
        BackHandler { selectedIp = null }
        DeviceDetailScreen(
            device = selected,
            portScanRunning = selected.ip in state.portScansRunning,
            snackbar = snackbar,
            onBack = { selectedIp = null },
            onScanPorts = { ports -> if (ports == null) vm.scanPorts(selected.ip) else vm.scanPorts(selected.ip, ports) },
            onRefresh = { vm.refreshDevice(selected.ip) },
            onWake = { vm.wake(selected) },
        )
    } else {
        ScanScreen(state, vm, snackbar, onOpen = { selectedIp = it.ip })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanScreen(
    state: ScanUiState,
    vm: ScanViewModel,
    snackbar: SnackbarHostState,
    onOpen: (Device) -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ScanIP") },
                actions = {
                    if (state.devices.isNotEmpty()) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "ScanIP results")
                                putExtra(Intent.EXTRA_TEXT, vm.exportCsv())
                            }
                            context.startActivity(Intent.createChooser(send, "Export scan results"))
                        }) { Icon(Icons.Filled.Share, contentDescription = "Export CSV") }
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (state.ouiInstalled) "Update vendor database" else "Download vendor database") },
                            enabled = !state.ouiBusy,
                            onClick = { menuOpen = false; vm.updateVendorDatabase() },
                        )
                        DropdownMenuItem(text = { Text("Refresh network info") }, onClick = { menuOpen = false; vm.refreshNetwork() })
                        DropdownMenuItem(text = { Text("About") }, onClick = { menuOpen = false; showAbout = true })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::toggleScan,
                icon = { Icon(if (state.scanning) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null) },
                text = { Text(if (state.scanning) "Stop" else "Scan") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { NetworkCard(state.network) }
            item {
                OutlinedTextField(
                    value = state.rangeInput,
                    onValueChange = vm::setRange,
                    label = { Text("IP range") },
                    placeholder = { Text("192.168.1.1-254, 10.0.0.0/24") },
                    singleLine = true,
                    enabled = !state.scanning,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        IconButton(onClick = vm::resetRange, enabled = !state.scanning) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Use current subnet")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { ProgressRow(state) }
            if (state.devices.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = state.filter,
                        onValueChange = vm::setFilter,
                        placeholder = { Text("Filter by name, IP, MAC, vendor, service") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.filter.isNotEmpty()) IconButton(onClick = { vm.setFilter("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear filter")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            val devices = state.visibleDevices
            items(devices, key = { it.ip }) { d ->
                DeviceRow(d, onClick = { onOpen(d) })
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
            if (state.devices.isEmpty() && !state.scanning) {
                item {
                    Text(
                        "Tap Scan to discover devices on this network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("OK") } },
            title = { Text("About ScanIP") },
            text = {
                Text(
                    "ScanIP finds devices with ping and TCP probes, then asks each one for its name over " +
                        "reverse DNS, NetBIOS, mDNS (Bonjour) and UPnP.\n\n" +
                        "Android 10 and later hide other devices' MAC addresses from apps, so a MAC is shown only " +
                        "when the device reports it itself (NetBIOS) or the system still exposes the ARP table.\n\n" +
                        "Manufacturer names come from the IEEE OUI registry. Download it from the menu."
                )
            },
        )
    }
}

@Composable
private fun NetworkCard(net: NetworkSnapshot?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (net?.localIp == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WifiOff, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Not connected to Wi-Fi or Ethernet. You can still enter a range to scan.")
                }
                return@Column
            }
            Text(
                net.ssid ?: if (net.isWifi) "Wi-Fi" else "Ethernet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            InfoLine("This device", "${net.localIp}/${net.prefixLength}")
            net.gateway?.let { InfoLine("Gateway", it) }
            if (net.dnsServers.isNotEmpty()) InfoLine("DNS", net.dnsServers.joinToString(", "))
            if (net.firstHost != null && net.lastHost != null) {
                InfoLine("Range", "${IpUtils.toIp(net.firstHost)} – ${IpUtils.toIp(net.lastHost)} (${net.lastHost - net.firstHost + 1} hosts)")
            }
            net.interfaceName?.let { InfoLine("Interface", it) }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ProgressRow(state: ScanUiState) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (state.scanning) {
            val p = if (state.total > 0) state.scanned.toFloat() / state.total else 0f
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
        }
        if (state.scanning || state.devices.isNotEmpty()) {
            Text(
                buildString {
                    if (state.scanning) append("Scanned ${state.scanned} of ${state.total} · ")
                    append("${state.devices.size} device(s) found")
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(d: Device, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(deviceIcon(d), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Box(
                Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                    .background(if (d.isAlive) OnlineGreen else MaterialTheme.colorScheme.outline)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (d.isGateway) Tag("Gateway")
                if (d.isSelf) Tag("You")
            }
            Text(d.ip, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            val sub = listOfNotNull(d.manufacturer, d.mac).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val badges = serviceBadges(d)
            if (badges.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    badges.forEach { Badge(it) }
                }
            }
        }
        d.latencyMs?.let {
            Text("$it ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
