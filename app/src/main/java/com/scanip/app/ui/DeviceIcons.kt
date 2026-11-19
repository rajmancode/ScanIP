package com.scanip.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.scanip.app.model.Device

/** Guesses a device category from its open ports, UPnP type and names. */
fun deviceIcon(d: Device): ImageVector {
    val ports = d.openPorts.map { it.port }.toSet()
    val text = listOfNotNull(d.upnp?.deviceType, d.upnp?.modelName, d.upnp?.manufacturer, d.displayName, d.vendor, d.snmp?.description)
        .joinToString(" ").lowercase()
    return when {
        d.isSelf -> Icons.Filled.PhoneAndroid
        d.isGateway || d.snmp?.isRouter == true || "internetgateway" in text || "router" in text -> Icons.Filled.Router
        d.snmp?.isSwitch == true || "switch" in text -> Icons.Filled.Lan
        9100 in ports || 631 in ports || 515 in ports || "printer" in text -> Icons.Filled.Print
        "mediarenderer" in text || "tv" in text.split(' ') || "chromecast" in text || "roku" in text || 8009 in ports -> Icons.Filled.Tv
        "camera" in text || 554 in ports -> Icons.Filled.Videocam
        62078 in ports || "iphone" in text || "ipad" in text -> Icons.Filled.PhoneIphone
        "speaker" in text || "sonos" in text -> Icons.Filled.Speaker
        "nas" in text.split(' ') || "synology" in text || "qnap" in text || 2049 in ports -> Icons.Filled.Storage
        3389 in ports || 445 in ports || 139 in ports || d.netbiosName != null -> Icons.Filled.Computer
        else -> Icons.Filled.Devices
    }
}

/** Short labels shown as chips in the list, similar to the expandable service rows in Advanced IP Scanner. */
fun serviceBadges(d: Device): List<String> {
    val ports = d.openPorts.map { it.port }.toSet()
    return buildList {
        if (80 in ports || 8080 in ports) add("HTTP")
        if (443 in ports) add("HTTPS")
        if (22 in ports) add("SSH")
        if (21 in ports) add("FTP")
        if (445 in ports) add("SMB")
        if (3389 in ports) add("RDP")
        if (5900 in ports) add("VNC")
        if (d.upnp != null) add("UPnP")
        if (d.snmp != null) add("SNMP")
    }
}
