package com.scanip.app.net

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Just enough DNS wire format to send PTR queries and read the answers. */
object DnsMessage {
    const val TYPE_PTR = 12

    fun reverseName(ip: String): String = ip.split('.').reversed().joinToString(".") + ".in-addr.arpa"

    fun buildQuery(name: String, type: Int, id: Int = Random.nextInt(0, 0xFFFF), recursion: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xff)
        out.write(if (recursion) 0x01 else 0x00); out.write(0x00) // flags
        out.write(0); out.write(1) // QDCOUNT
        repeat(6) { out.write(0) } // AN, NS, AR
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        out.write(type shr 8); out.write(type and 0xff)
        out.write(0); out.write(1) // class IN
        return out.toByteArray()
    }

    /** Returns the first PTR target name found in the answer section, or null. */
    fun parsePtrAnswer(data: ByteArray, length: Int = data.size): String? {
        if (length < 12) return null
        val qd = u16(data, 4)
        val an = u16(data, 6)
        var pos = 12
        repeat(qd) {
            pos = skipName(data, pos, length) ?: return null
            pos += 4
        }
        repeat(an) {
            pos = skipName(data, pos, length) ?: return null
            if (pos + 10 > length) return null
            val type = u16(data, pos)
            val rdLength = u16(data, pos + 8)
            val rdStart = pos + 10
            if (rdStart + rdLength > length) return null
            if (type == TYPE_PTR) return readName(data, rdStart, length)?.trimEnd('.')
            pos = rdStart + rdLength
        }
        return null
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)

    private fun skipName(d: ByteArray, start: Int, length: Int): Int? {
        var pos = start
        while (pos < length) {
            val len = d[pos].toInt() and 0xff
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return pos + 2
                else -> pos += len + 1
            }
        }
        return null
    }

    fun readName(d: ByteArray, start: Int, length: Int): String? {
        val sb = StringBuilder()
        var pos = start
        var jumps = 0
        while (pos < length) {
            val len = d[pos].toInt() and 0xff
            when {
                len == 0 -> return sb.toString()
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= length || ++jumps > 16) return null
                    pos = ((len and 0x3F) shl 8) or (d[pos + 1].toInt() and 0xff)
                }
                else -> {
                    if (pos + 1 + len > length) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(d, pos + 1, len, Charsets.UTF_8))
                    pos += len + 1
                }
            }
        }
        return null
    }
}
