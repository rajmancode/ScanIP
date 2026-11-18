package com.scanip.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Decides whether a host is up, the way Advanced IP Scanner does: ICMP first, then TCP. */
class HostProber(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val timeoutMs: Int = 800,
) {
    data class Result(val alive: Boolean, val latencyMs: Long?)

    suspend fun probe(ip: String): Result = withContext(Dispatchers.IO) {
        ping(ip)?.let { return@withContext Result(true, it) }
        tcpProbe(ip)?.let { return@withContext Result(true, it) }
        Result(false, null)
    }

    /** Uses the system ping binary, which can send ICMP without root on Android. */
    private fun ping(ip: String): Long? {
        val start = System.nanoTime()
        return try {
            val waitSec = maxOf(1, (timeoutMs + 999) / 1000)
            val proc = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", waitSec.toString(), ip)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(timeoutMs + 1500L, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroy()
                return null
            }
            val output = proc.inputStream.bufferedReader().readText()
            if (proc.exitValue() != 0) return null
            PING_TIME.find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
                ?: ((System.nanoTime() - start) / 1_000_000)
        } catch (_: Exception) {
            null
        }
    }

    /** A TCP connect that succeeds, or is actively refused, proves the host is up. */
    private suspend fun tcpProbe(ip: String): Long? = coroutineScope {
        PROBE_PORTS.map { port ->
            async(Dispatchers.IO) { tcpTouch(ip, port) }
        }.awaitAll().filterNotNull().minOrNull()
    }

    private fun tcpTouch(ip: String, port: Int): Long? {
        val start = System.nanoTime()
        val socket = try {
            socketFactory.createSocket()
        } catch (_: IOException) {
            Socket()
        }
        return try {
            socket.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
            (System.nanoTime() - start) / 1_000_000
        } catch (e: ConnectException) {
            // "Connection refused" means a host answered with a RST.
            if (e.message?.contains("refused", ignoreCase = true) == true) {
                (System.nanoTime() - start) / 1_000_000
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    companion object {
        private val PING_TIME = Regex("time[=<]([0-9.]+)")
        private val PROBE_PORTS = listOf(80, 443, 22, 445, 139, 3389, 8080, 62078, 5353, 7000)
    }
}
