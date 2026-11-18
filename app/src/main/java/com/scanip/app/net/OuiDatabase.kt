package com.scanip.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Maps the first three bytes of a MAC address to the manufacturer.
 * The list (~35k entries) is downloaded once from the IEEE registry and cached in app storage.
 */
class OuiDatabase(context: Context) {
    private val file = File(context.filesDir, "oui.tsv")
    @Volatile private var table: Map<String, String>? = null

    val isInstalled: Boolean get() = file.exists() && file.length() > 0
    val entryCount: Int get() = table?.size ?: 0
    val lastUpdated: Long? get() = if (isInstalled) file.lastModified() else null

    suspend fun load() = withContext(Dispatchers.IO) {
        if (table != null || !isInstalled) return@withContext
        val map = HashMap<String, String>(40_000)
        file.forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab == 6) map[line.substring(0, 6)] = line.substring(7)
        }
        table = map
    }

    fun lookup(mac: String?): String? {
        val norm = IpUtils.normalizeMac(mac) ?: return null
        val hex = norm.replace(":", "")
        table?.get(hex.substring(0, 6))?.let { return it }
        // Bit 1 of the first octet = locally administered, i.e. a randomised "private" address.
        val first = hex.substring(0, 2).toInt(16)
        return if (first and 0x02 != 0) "Private (randomized) MAC" else null
    }

    /** Downloads and installs the IEEE OUI list. Returns the number of entries. */
    suspend fun update(): Int = withContext(Dispatchers.IO) {
        val conn = URL(SOURCE).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "ScanIP")
        val map = HashMap<String, String>(40_000)
        try {
            conn.inputStream.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val cols = parseCsvLine(line)
                    if (cols.size >= 3 && cols[1].length == 6) {
                        map[cols[1].uppercase()] = cols[2].trim()
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (map.size < 1000) error("Downloaded list looks incomplete (${map.size} entries)")
        val tmp = File(file.parentFile, "oui.tsv.tmp")
        tmp.bufferedWriter().use { w -> map.forEach { (k, v) -> w.append(k).append('\t').append(v).append('\n') } }
        tmp.renameTo(file)
        table = map
        map.size
    }

    companion object {
        const val SOURCE = "https://standards-oui.ieee.org/oui/oui.csv"

        fun parseCsvLine(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out.add(sb.toString()); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            out.add(sb.toString())
            return out
        }
    }
}
