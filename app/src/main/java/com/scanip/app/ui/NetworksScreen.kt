package com.scanip.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scanip.app.ScanUiState
import com.scanip.app.ScanViewModel
import com.scanip.app.model.SubnetEntry
import com.scanip.app.net.IpUtils

@Composable
fun NetworksScreen(state: ScanUiState, vm: ScanViewModel, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubnetsCard(state, vm)
        SnmpCard(state, vm)
    }
}

@Composable
private fun SubnetsCard(state: ScanUiState, vm: ScanViewModel) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val own = state.network?.let { n ->
        if (n.localIp != null && n.prefixLength != null) IpUtils.cidr(n.localIp, n.prefixLength) else null
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Subnets / VLANs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Your phone sits in one VLAN. Other VLANs can be scanned when your router routes to them. " +
                    "Firewalled devices and MAC addresses there are only visible through SNMP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            own?.let { SubnetRow(it, "This network (IP range on the Devices tab)", checked = true, enabled = false, onChecked = {}, onDelete = null) }
            state.subnets.forEach { e ->
                SubnetRow(
                    cidr = e.cidr,
                    note = when (e.source) {
                        SubnetEntry.Source.SNMP -> "From router (SNMP)"
                        SubnetEntry.Source.DISCOVERED -> "Gateway found by probing"
                        SubnetEntry.Source.LOCAL -> "This network"
                        SubnetEntry.Source.MANUAL -> "Added by you"
                    },
                    checked = e.enabled,
                    enabled = !state.scanning,
                    onChecked = { vm.setSubnetEnabled(e.cidr, it) },
                    onDelete = { vm.removeSubnet(e.cidr) },
                )
            }
            Spacer(Modifier.height(8.dp))
            val err = error
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = { Text("Add subnet") },
                    placeholder = { Text("192.168.20.0/24") },
                    singleLine = true,
                    isError = err != null,
                    supportingText = if (err != null) { { Text(err) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    error = vm.addSubnet(input)
                    if (error == null) input = ""
                }) { Icon(Icons.Filled.Add, contentDescription = "Add subnet") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::discoverSubnets, enabled = state.discovering == null && !state.scanning) {
                    Icon(Icons.Filled.TravelExplore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Find VLANs")
                }
                state.discovering?.let {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SubnetRow(cidr: String, note: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit, onDelete: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(cidr, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Remove $cidr") }
        }
    }
}

@Composable
private fun SnmpCard(state: ScanUiState, vm: ScanViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SNMP (managed switches)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Reads MAC tables, LLDP neighbours and VLAN addresses to draw the map.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.snmpEnabled, onCheckedChange = vm::setSnmpEnabled)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.snmpCommunities,
                onValueChange = vm::setSnmpCommunities,
                label = { Text("Community strings") },
                supportingText = { Text("Comma-separated, tried in order (v2c, then v1). Read-only is enough.") },
                singleLine = true,
                enabled = state.snmpEnabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "On each managed switch (e.g. Hikvision), open its web interface, find the SNMP settings, enable " +
                    "SNMP v1/v2c and set a read-only community. Enable LLDP too, so links between switches are exact.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
