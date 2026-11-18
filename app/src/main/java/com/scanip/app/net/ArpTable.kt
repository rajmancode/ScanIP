package com.scanip.app.net

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads IP -> MAC mappings from the kernel neighbour table.
 *
 * Android 10+ blocks /proc/net/arp and Android 11+ blocks `ip neigh` for normal apps, so this
 * often returns an empty map on newer phones. MACs are then filled in from NetBIOS or UPnP.
 */
object ArpTable {
    fun read(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        readProcArp(result)
        if (result.isEmpty()) readIpNeigh(result)
        return result
    }

    private fun readProcArp(into: MutableMap<String, String>) {
        try {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size >= 4) {
                    IpUtils.normalizeMac(cols[3])?.let { into[cols[0]] = it }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readIpNeigh(into: MutableMap<String, String>) {
        try {
            val proc = ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroy()
                return
            }
            into.putAll(parseIpNeigh(proc.inputStream.bufferedReader().readText()))
        } catch (_: Exception) {
        }
    }

    fun parseIpNeigh(output: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        output.lineSequence().forEach { line ->
            val cols = line.trim().split(Regex("\\s+"))
            val idx = cols.indexOf("lladdr")
            if (idx > 0 && idx + 1 < cols.size && IpUtils.toLong(cols[0]) != null) {
                if (cols.lastOrNull() == "FAILED" || cols.lastOrNull() == "INCOMPLETE") return@forEach
                IpUtils.normalizeMac(cols[idx + 1])?.let { map[cols[0]] = it }
            }
        }
        return map
    }
}
