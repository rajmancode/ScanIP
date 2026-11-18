package com.scanip.app.net

import com.scanip.app.model.OpenPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** TCP connect scanner with light banner grabbing for HTTP, SSH, FTP, SMTP and Telnet. */
class PortScanner(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val timeoutMs: Int = 700,
) {
    suspend fun scan(ip: String, ports: Collection<Int>, grabBanners: Boolean = true): List<OpenPort> = coroutineScope {
        val limiter = Semaphore(32)
        ports.distinct().map { port ->
            async(Dispatchers.IO) {
                limiter.withPermit { check(ip, port, grabBanners) }
            }
        }.awaitAll().filterNotNull().sortedBy { it.port }
    }

    private fun check(ip: String, port: Int, grabBanner: Boolean): OpenPort? {
        val socket = try { socketFactory.createSocket() } catch (_: IOException) { Socket() }
        return try {
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            val banner = if (grabBanner) readBanner(socket, ip, port) else null
            OpenPort(port, serviceName(port), banner)
        } catch (_: Exception) {
            null
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun readBanner(socket: Socket, ip: String, port: Int): String? = try {
        socket.soTimeout = 1200
        if (port in HTTP_PORTS) {
            val req = "GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: ScanIP\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(req.toByteArray())
            val text = readUpTo(socket, 16 * 1024)
            describeHttp(text)
        } else if (port in SPEAK_FIRST_PORTS) {
            readUpTo(socket, 512).lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
        } else null
    } catch (_: Exception) {
        null
    }

    private fun readUpTo(socket: Socket, max: Int): String {
        val input = socket.getInputStream()
        val buf = ByteArray(max)
        var total = 0
        try {
            while (total < max) {
                val n = input.read(buf, total, max - total)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {
            // Timeout: use what we have.
        }
        return String(buf, 0, total, Charsets.ISO_8859_1)
    }

    companion object {
        private val HTTP_PORTS = setOf(80, 8000, 8008, 8080, 8081, 8888, 5000, 9000)
        private val SPEAK_FIRST_PORTS = setOf(21, 22, 23, 25, 110, 143, 3306, 5900)
        private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        /** Ports checked during the main sweep, for the service icons in the list. */
        val QUICK_PORTS = listOf(21, 22, 80, 443, 445, 3389, 5900, 8080)

        val COMMON_PORTS: Map<Int, String> = linkedMapOf(
            20 to "FTP-data", 21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS",
            67 to "DHCP", 80 to "HTTP", 81 to "HTTP-alt", 88 to "Kerberos", 110 to "POP3", 111 to "RPC",
            135 to "MS-RPC", 139 to "NetBIOS", 143 to "IMAP", 161 to "SNMP", 389 to "LDAP",
            443 to "HTTPS", 445 to "SMB", 465 to "SMTPS", 515 to "LPD", 548 to "AFP", 554 to "RTSP",
            587 to "SMTP-sub", 631 to "IPP", 873 to "rsync", 993 to "IMAPS", 995 to "POP3S",
            1080 to "SOCKS", 1194 to "OpenVPN", 1433 to "MSSQL", 1723 to "PPTP", 1883 to "MQTT",
            1900 to "UPnP", 2049 to "NFS", 3000 to "HTTP-dev", 3306 to "MySQL", 3389 to "RDP",
            3689 to "DAAP", 5000 to "UPnP/HTTP", 5001 to "HTTPS-alt", 5060 to "SIP", 5353 to "mDNS",
            5432 to "PostgreSQL", 5555 to "ADB", 5900 to "VNC", 6379 to "Redis", 6466 to "Android TV",
            7000 to "AirPlay", 8000 to "HTTP-alt", 8008 to "HTTP-alt", 8009 to "Chromecast",
            8080 to "HTTP-proxy", 8081 to "HTTP-alt", 8123 to "Home Assistant", 8443 to "HTTPS-alt",
            8883 to "MQTT-TLS", 8888 to "HTTP-alt", 9000 to "HTTP-alt", 9100 to "Printer (JetDirect)",
            9200 to "Elasticsearch", 27017 to "MongoDB", 32400 to "Plex", 49152 to "UPnP", 62078 to "iPhone sync",
        )

        fun serviceName(port: Int): String = COMMON_PORTS[port] ?: "tcp/$port"

        /** Summarises an HTTP response as "Title — Server". */
        fun describeHttp(response: String): String? {
            if (!response.startsWith("HTTP/")) return null
            val headerEnd = response.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: response.length
            val headers = response.substring(0, headerEnd).split("\r\n")
            val status = headers.firstOrNull()?.substringAfter(' ')?.trim()
            val server = headers.firstOrNull { it.startsWith("server:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            val title = TITLE.find(response)?.groupValues?.get(1)
                ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
            return listOfNotNull(title, server).joinToString(" — ").ifEmpty { status }
        }

        /** Parses "22, 80, 8000-8100" into a port list (max 2048 ports). */
        fun parsePorts(input: String): List<Int>? {
            val result = sortedSetOf<Int>()
            for (token in input.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
                if ('-' in token) {
                    val (a, b) = token.split('-', limit = 2).map { it.trim().toIntOrNull() ?: return null }
                    if (a !in 1..65535 || b !in a..65535) return null
                    for (p in a..b) result.add(p)
                } else {
                    val p = token.toIntOrNull() ?: return null
                    if (p !in 1..65535) return null
                    result.add(p)
                }
                if (result.size > 2048) return null
            }
            return result.toList().ifEmpty { null }
        }
    }
}
