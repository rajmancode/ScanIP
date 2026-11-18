package com.scanip.app.net

import android.util.Xml
import com.scanip.app.model.UpnpInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** SSDP (UPnP) discovery: finds routers, TVs, printers, NAS boxes and reads their descriptions. */
class UpnpDiscovery(
    private val bindSocket: (DatagramSocket) -> Unit = {},
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    data class Response(val ip: String, val location: String, val server: String?)

    /** Sends M-SEARCH a few times and gathers answers for [durationMs]. One response per IP. */
    suspend fun search(durationMs: Long = 4000, onFound: suspend (Response) -> Unit) = withContext(Dispatchers.IO) {
        val seen = mutableSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                bindSocket(socket)
                socket.soTimeout = 300
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf("ssdp:all", "upnp:rootdevice")
                val deadline = System.currentTimeMillis() + durationMs
                var nextSend = 0L
                var sends = 0
                val buf = ByteArray(2048)
                while (isActive && System.currentTimeMillis() < deadline) {
                    if (sends < 3 && System.currentTimeMillis() >= nextSend) {
                        for (st in targets) {
                            val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                            val bytes = msg.toByteArray()
                            socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
                        }
                        sends++
                        nextSend = System.currentTimeMillis() + 1000
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val ip = packet.address?.hostAddress ?: continue
                    val headers = parseHeaders(String(packet.data, 0, packet.length))
                    val location = headers["location"] ?: continue
                    if (seen.add(ip)) onFound(Response(ip, location, headers["server"]))
                }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun describe(response: Response): UpnpInfo = withContext(Dispatchers.IO) {
        val fields = mutableMapOf<String, String>()
        try {
            val conn = openConnection(URL(response.location))
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.inputStream.use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)
                var tag: String? = null
                var depthOfFirstDevice = -1
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> {
                            tag = parser.name.substringAfter(':')
                            if (tag == "device" && depthOfFirstDevice < 0) depthOfFirstDevice = parser.depth
                        }
                        XmlPullParser.TEXT -> {
                            val t = tag
                            // Only take fields of the root device, not embedded sub-devices.
                            if (t != null && parser.depth == depthOfFirstDevice + 1 && t !in fields) {
                                parser.text?.trim()?.takeIf { it.isNotEmpty() }?.let { fields[t] = it }
                            }
                        }
                        XmlPullParser.END_TAG -> tag = null
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        UpnpInfo(
            friendlyName = fields["friendlyName"],
            manufacturer = fields["manufacturer"],
            modelName = fields["modelName"],
            modelNumber = fields["modelNumber"],
            deviceType = fields["deviceType"]?.let { prettyDeviceType(it) },
            serialNumber = fields["serialNumber"],
            presentationUrl = fields["presentationURL"]?.let { resolve(response.location, it) },
            server = response.server,
            location = response.location,
        )
    }

    companion object {
        fun parseHeaders(message: String): Map<String, String> =
            message.split("\r\n", "\n").drop(1).mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }.toMap()

        /** "urn:schemas-upnp-org:device:MediaRenderer:1" -> "MediaRenderer" */
        fun prettyDeviceType(raw: String): String {
            val parts = raw.split(':')
            return if (parts.size >= 5) parts[parts.size - 2] else raw
        }

        private fun resolve(base: String, relative: String): String = try {
            URL(URL(base), relative).toString()
        } catch (_: Exception) {
            relative
        }
    }
}
