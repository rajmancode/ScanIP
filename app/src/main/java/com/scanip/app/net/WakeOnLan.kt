package com.scanip.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (r in 0 until 16) System.arraycopy(mac, 0, packet, 6 + r * 6, 6)
        return packet
    }

    /** Sends the magic packet to the subnet broadcast and the global broadcast, ports 9 and 7. */
    suspend fun send(mac: String, subnetBroadcast: String?, bindSocket: (DatagramSocket) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val bytes = IpUtils.macToBytes(mac) ?: return@withContext false
            val payload = magicPacket(bytes)
            val targets = listOfNotNull(subnetBroadcast, "255.255.255.255").distinct()
            var sent = false
            try {
                DatagramSocket().use { socket ->
                    bindSocket(socket)
                    socket.broadcast = true
                    for (t in targets) for (port in listOf(9, 7)) {
                        try {
                            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(t), port))
                            sent = true
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
            sent
        }
}
