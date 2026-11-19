package com.scanip.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Hop-by-hop path to a host, using the system ping binary with an increasing TTL. */
object Traceroute {
    private val FROM = Regex("""[Ff]rom (\d+\.\d+\.\d+\.\d+)""")

    /** Returns the routers on the way to [ip] (not including [ip] itself). Unknown hops are skipped. */
    suspend fun routers(ip: String, maxHops: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val hops = mutableListOf<String>()
        for (ttl in 1..maxHops) {
            val from = probe(ip, ttl)
            if (from == ip) break
            if (from != null && from !in hops) hops += from
        }
        hops
    }

    private fun probe(ip: String, ttl: Int): String? = try {
        val proc = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", "-t", ttl.toString(), ip)
            .redirectErrorStream(true).start()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroy()
            null
        } else parseHop(proc.inputStream.bufferedReader().readText(), ip)
    } catch (_: Exception) {
        null
    }

    /** Extracts the replying address: a router ("From x ... Time to live exceeded") or the target itself. */
    fun parseHop(output: String, target: String): String? {
        FROM.find(output)?.let { return it.groupValues[1] }
        return if (Regex("""bytes from ${Regex.escape(target)}""").containsMatchIn(output)) target else null
    }
}
