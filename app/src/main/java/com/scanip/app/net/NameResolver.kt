package com.scanip.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Resolves names for a host via reverse DNS, NetBIOS and multicast DNS (Bonjour). */
class NameResolver(
    private val dnsServers: List<String>,
    private val bindSocket: (DatagramSocket) -> Unit = {},
    private val timeoutMs: Int = 1200,
) {
    suspend fun reverseDns(ip: String): String? = withContext(Dispatchers.IO) {
        val query = DnsMessage.buildQuery(DnsMessage.reverseName(ip), DnsMessage.TYPE_PTR)
        for (server in dnsServers.take(2)) {
            udpExchange(server, 53, query)?.let { (buf, len) ->
                DnsMessage.parsePtrAnswer(buf, len)?.let { return@withContext it }
            }
        }
        null
    }

    suspend fun netbios(ip: String): NetBios.Info? = withContext(Dispatchers.IO) {
        udpExchange(ip, 137, NetBios.buildNodeStatusRequest())?.let { (buf, len) ->
            NetBios.parseNodeStatusResponse(buf, len)
        }
    }

    /** A unicast query sent straight to port 5353; Apple, Android and Linux (Avahi) hosts answer it. */
    suspend fun mdns(ip: String): String? = withContext(Dispatchers.IO) {
        val query = DnsMessage.buildQuery(DnsMessage.reverseName(ip), DnsMessage.TYPE_PTR, id = 0, recursion = false)
        udpExchange(ip, 5353, query)?.let { (buf, len) -> DnsMessage.parsePtrAnswer(buf, len) }
    }

    private fun udpExchange(host: String, port: Int, payload: ByteArray): Pair<ByteArray, Int>? {
        return try {
            DatagramSocket().use { socket ->
                bindSocket(socket)
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(host)
                socket.send(DatagramPacket(payload, payload.size, address, port))
                val buf = ByteArray(1500)
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                buf to packet.length
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
