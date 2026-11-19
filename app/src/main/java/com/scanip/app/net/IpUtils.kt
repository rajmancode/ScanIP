package com.scanip.app.net

object IpUtils {
    /** Largest range we will sweep in one go (a /20). */
    const val MAX_HOSTS = 4096

    fun toLong(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255 || (p.length > 1 && p.startsWith('0'))) return null
            value = (value shl 8) or n.toLong()
        }
        return value
    }

    fun toIp(value: Long): String =
        "${(value shr 24) and 0xff}.${(value shr 16) and 0xff}.${(value shr 8) and 0xff}.${value and 0xff}"

    /** Usable host range (excludes network and broadcast address when prefix <= 30). */
    fun hostRange(ip: String, prefix: Int): LongRange? {
        val addr = toLong(ip) ?: return null
        if (prefix !in 0..32) return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = addr and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        return if (prefix >= 31) network..broadcast else (network + 1)..(broadcast - 1)
    }

    /** Limits a subnet to at most [MAX_HOSTS] addresses, centred on the given address. */
    fun clampAround(range: LongRange, center: Long): LongRange {
        val size = range.last - range.first + 1
        if (size <= MAX_HOSTS) return range
        val block = center - (center - range.first) % MAX_HOSTS
        val start = maxOf(range.first, block)
        return start..minOf(range.last, start + MAX_HOSTS - 1)
    }

    /**
     * Parses user input such as:
     *  - `192.168.1.0/24`
     *  - `192.168.1.1-254`
     *  - `192.168.1.10-192.168.1.50`
     *  - `10.0.0.5`
     *  - several of the above separated by commas
     * Returns the sorted, de-duplicated list of addresses, or null if anything is invalid or too large.
     */
    fun parseTargets(input: String): List<Long>? {
        val result = sortedSetOf<Long>()
        val tokens = input.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        for (token in tokens) {
            val range: LongRange = when {
                '/' in token -> {
                    val (ip, p) = token.split('/', limit = 2)
                    hostRange(ip, p.toIntOrNull() ?: return null) ?: return null
                }
                '-' in token -> {
                    val (a, b) = token.split('-', limit = 2)
                    val start = toLong(a) ?: return null
                    val end = if ('.' in b) {
                        toLong(b) ?: return null
                    } else {
                        val last = b.toIntOrNull() ?: return null
                        if (last !in 0..255) return null
                        (start and 0xFFFFFF00L) or last.toLong()
                    }
                    if (end < start) return null
                    start..end
                }
                else -> {
                    val v = toLong(token) ?: return null
                    v..v
                }
            }
            if (range.last - range.first + 1 + result.size > MAX_HOSTS) return null
            for (v in range) result.add(v)
        }
        return result.toList()
    }

    /** Parses "192.168.20.0/24" (any host address is accepted) into (network, prefix). */
    fun parseCidr(text: String): Pair<Long, Int>? {
        val parts = text.trim().split('/')
        if (parts.size != 2) return null
        val ip = toLong(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull()?.takeIf { it in 8..32 } ?: return null
        return (ip and mask(prefix)) to prefix
    }

    fun mask(prefix: Int): Long = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    fun cidr(ip: String, prefix: Int): String? = toLong(ip)?.let { "${toIp(it and mask(prefix))}/$prefix" }

    fun cidrContains(cidr: String, ip: String): Boolean {
        val (net, prefix) = parseCidr(cidr) ?: return false
        val v = toLong(ip) ?: return false
        return (v and mask(prefix)) == net
    }

    fun isPrivate(ip: String): Boolean {
        val v = toLong(ip) ?: return false
        return (v shr 24) == 10L || (v shr 20) == 0xAC1L || (v shr 16) == 0xC0A8L
    }

    fun normalizeMac(raw: String?): String? {
        if (raw == null) return null
        val hex = raw.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
        if (hex == "000000000000" || hex == "FFFFFFFFFFFF") return null
        return hex.chunked(2).joinToString(":")
    }

    fun macToBytes(mac: String): ByteArray? {
        val hex = normalizeMac(mac)?.replace(":", "") ?: return null
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
