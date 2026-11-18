package com.scanip.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scanip.app.model.Device
import com.scanip.app.model.OpenPort
import com.scanip.app.net.PortScanner

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceDetailScreen(
    device: Device,
    portScanRunning: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onScanPorts: (List<Int>?) -> Unit,
    onRefresh: () -> Unit,
    onWake: () -> Unit,
) {
    val context = LocalContext.current
    var customPortsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Ping again") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ports = device.openPorts.map { it.port }.toSet()
                if (80 in ports || 8080 in ports) {
                    val url = if (80 in ports) "http://${device.ip}/" else "http://${device.ip}:8080/"
                    AssistChip(onClick = { openUrl(context, url) }, label = { Text("HTTP") },
                        leadingIcon = { Icon(Icons.Filled.OpenInBrowser, null) })
                }
                if (443 in ports) {
                    AssistChip(onClick = { openUrl(context, "https://${device.ip}/") }, label = { Text("HTTPS") },
                        leadingIcon = { Icon(Icons.Filled.OpenInBrowser, null) })
                }
                device.upnp?.presentationUrl?.let { url ->
                    AssistChip(onClick = { openUrl(context, url) }, label = { Text("Web UI") },
                        leadingIcon = { Icon(Icons.Filled.OpenInBrowser, null) })
                }
                if (device.mac != null && !device.isSelf) {
                    AssistChip(onClick = onWake, label = { Text("Wake-on-LAN") },
                        leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, null) })
                }
                AssistChip(onClick = { copy(context, device.ip) }, label = { Text("Copy IP") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
            }

            Section("General") {
                Field("Status", if (device.isAlive) "Online" else "Offline")
                Field("IP address", device.ip)
                Field("Response time", device.latencyMs?.let { "$it ms" })
                Field("MAC address", device.mac ?: if (device.isSelf) "Hidden by Android" else "Not available")
                Field("Manufacturer", device.manufacturer)
                Field("DNS name", device.hostname)
                Field("NetBIOS name", device.netbiosName)
                Field("Workgroup", device.workgroup)
                Field("mDNS name", device.mdnsName)
                if (device.isGateway) Field("Role", "Default gateway")
                if (device.isSelf) Field("Role", "This device")
            }

            device.upnp?.let { u ->
                Section("UPnP") {
                    Field("Friendly name", u.friendlyName)
                    Field("Manufacturer", u.manufacturer)
                    Field("Model", listOfNotNull(u.modelName, u.modelNumber).joinToString(" ").ifEmpty { null })
                    Field("Device type", u.deviceType)
                    Field("Serial number", u.serialNumber)
                    Field("Server", u.server)
                    Field("Description", u.location)
                }
            }

            Section(if (device.portsScanned) "Open ports" else "Open ports (quick check)") {
                if (device.openPorts.isEmpty()) {
                    Text(
                        if (device.portsScanned) "No open ports found." else "None of the quick-check ports are open.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                device.openPorts.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    PortRow(device.ip, p)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onScanPorts(null) }, enabled = !portScanRunning) {
                        Text("Scan ${PortScanner.COMMON_PORTS.size} common ports")
                    }
                    OutlinedButton(onClick = { customPortsDialog = true }, enabled = !portScanRunning) {
                        Icon(Icons.Filled.Tune, contentDescription = "Custom ports", modifier = Modifier.size(18.dp))
                    }
                    if (portScanRunning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }

    if (customPortsDialog) {
        var text by remember { mutableStateOf("1-1024") }
        val parsed = PortScanner.parsePorts(text)
        AlertDialog(
            onDismissRequest = { customPortsDialog = false },
            title = { Text("Scan custom ports") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Ports") },
                    supportingText = { Text(if (parsed == null) "e.g. 22, 80, 8000-8100 (max 2048)" else "${parsed.size} port(s)") },
                    isError = parsed == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            },
            confirmButton = {
                TextButton(enabled = parsed != null, onClick = {
                    customPortsDialog = false
                    onScanPorts(parsed)
                }) { Text("Scan") }
            },
            dismissButton = { TextButton(onClick = { customPortsDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SelectionContainer { Column { content() } }
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PortRow(ip: String, p: OpenPort) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${p.port}/tcp  ${p.service}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            p.banner?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        webUrl(ip, p)?.let { url ->
            IconButton(onClick = { openUrl(context, url) }) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open in browser")
            }
        }
    }
}

private fun webUrl(ip: String, p: OpenPort): String? = when {
    p.port == 443 || p.port == 8443 || p.port == 5001 -> "https://$ip:${p.port}/"
    p.service.startsWith("HTTP") || p.port in setOf(80, 81, 3000, 5000, 8000, 8008, 8080, 8081, 8123, 8888, 9000, 32400) ->
        "http://$ip:${p.port}/"
    else -> null
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("IP address", text))
    Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
}
