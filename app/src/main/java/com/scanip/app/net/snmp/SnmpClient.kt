package com.scanip.app.net.snmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/** Credentials that worked for a device. */
data class SnmpTarget(val ip: String, val community: String, val version: Int)

/** Minimal SNMP v1/v2c client: GET and table walks over UDP 161. */
class SnmpClient(
    private val bindSocket: (DatagramSocket) -> Unit = {},
    private val timeoutMs: Int = 900,
    private val retries: Int = 1,
) {
    suspend fun get(target: SnmpTarget, oids: List<String>): List<VarBind>? = withContext(Dispatchers.IO) {
        exchange(target, SnmpCodec.GET, oids)?.takeIf { it.errorStatus == 0 }?.varBinds
    }

    /** Tries each community with v2c, then v1. Returns the first that answers sysName/sysDescr. */
    suspend fun discover(ip: String, communities: List<String>): Pair<SnmpTarget, List<VarBind>>? {
        for (community in communities) {
            for (version in listOf(SnmpCodec.V2C, SnmpCodec.V1)) {
                val t = SnmpTarget(ip, community, version)
                val r = get(t, SYSTEM_OIDS)
                if (r != null && r.any { it.value !is SnmpValue.Missing && it.value !is SnmpValue.Null }) return t to r
            }
        }
        return null
    }

    /** Returns every (oid, value) below [root]. Stops at [maxRows] to bound huge tables. */
    suspend fun walk(target: SnmpTarget, root: String, maxRows: Int = 20_000): List<VarBind> = withContext(Dispatchers.IO) {
        val rows = ArrayList<VarBind>()
        var current = root
        while (rows.size < maxRows) {
            val resp = if (target.version == SnmpCodec.V2C) {
                exchange(target, SnmpCodec.GET_BULK, listOf(current), nonRepeaters = 0, maxRepetitions = 30)
            } else {
                exchange(target, SnmpCodec.GET_NEXT, listOf(current))
            } ?: break
            if (resp.errorStatus != 0 || resp.varBinds.isEmpty()) break
            var done = false
            for (vb in resp.varBinds) {
                if (vb.value is SnmpValue.Missing || !SnmpCodec.isUnder(vb.oid, root) ||
                    SnmpCodec.compareOids(vb.oid, current) <= 0
                ) {
                    done = true
                    break
                }
                rows += vb
                current = vb.oid
            }
            if (done) break
        }
        rows
    }

    private fun exchange(
        target: SnmpTarget,
        pdu: Int,
        oids: List<String>,
        nonRepeaters: Int = 0,
        maxRepetitions: Int = 0,
    ): SnmpResponse? {
        val id = Random.nextInt(1, Int.MAX_VALUE)
        val request = SnmpCodec.encodeRequest(target.version, target.community, pdu, id, oids, nonRepeaters, maxRepetitions)
        try {
            DatagramSocket().use { socket ->
                bindSocket(socket)
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(target.ip)
                val buf = ByteArray(65_535)
                repeat(retries + 1) {
                    socket.send(DatagramPacket(request, request.size, address, 161))
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        val resp = SnmpCodec.decodeResponse(buf, packet.length)
                        if (resp != null && resp.requestId == id) return resp
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    companion object {
        const val SYS_DESCR = "1.3.6.1.2.1.1.1.0"
        const val SYS_OBJECT_ID = "1.3.6.1.2.1.1.2.0"
        const val SYS_NAME = "1.3.6.1.2.1.1.5.0"
        const val SYS_LOCATION = "1.3.6.1.2.1.1.6.0"
        val SYSTEM_OIDS = listOf(SYS_DESCR, SYS_OBJECT_ID, SYS_NAME, SYS_LOCATION)
    }
}
