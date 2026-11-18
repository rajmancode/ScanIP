package com.scanip.app.net

/** NetBIOS Node Status (NBSTAT) request/response, as used by Windows "nbtstat -A". */
object NetBios {
    data class Info(val name: String?, val workgroup: String?, val mac: String?)

    fun buildNodeStatusRequest(id: Int = 0x4E42): ByteArray {
        val packet = ByteArray(50)
        packet[0] = (id shr 8).toByte(); packet[1] = id.toByte()
        packet[5] = 1 // QDCOUNT = 1
        packet[12] = 0x20
        // "*" padded with NULs, first-level encoded: '*' = 0x2A -> "CK", 0x00 -> "AA".
        packet[13] = 'C'.code.toByte(); packet[14] = 'K'.code.toByte()
        for (i in 15 until 45) packet[i] = 'A'.code.toByte()
        packet[45] = 0
        packet[46] = 0x00; packet[47] = 0x21 // NBSTAT
        packet[48] = 0x00; packet[49] = 0x01 // IN
        return packet
    }

    fun parseNodeStatusResponse(data: ByteArray, length: Int = data.size): Info? {
        if (length < 57) return null
        var pos = 12
        // Skip the answer name (labels or a compression pointer).
        while (pos < length) {
            val len = data[pos].toInt() and 0xff
            if (len == 0) { pos += 1; break }
            if (len and 0xC0 == 0xC0) { pos += 2; break }
            pos += len + 1
        }
        pos += 2 + 2 + 4 + 2 // type, class, ttl, rdlength
        if (pos >= length) return null
        val count = data[pos].toInt() and 0xff
        pos += 1
        var name: String? = null
        var group: String? = null
        for (i in 0 until count) {
            if (pos + 18 > length) return null
            val entry = String(data, pos, 15, Charsets.US_ASCII).trimEnd(' ', '\u0000')
            val suffix = data[pos + 15].toInt() and 0xff
            val isGroup = (data[pos + 16].toInt() and 0x80) != 0
            if (suffix == 0x00 && entry.isNotEmpty()) {
                if (isGroup) { if (group == null) group = entry }
                else if (name == null) name = entry
            }
            pos += 18
        }
        val mac = if (pos + 6 <= length) {
            IpUtils.normalizeMac(
                (0 until 6).joinToString(":") { "%02X".format(data[pos + it].toInt() and 0xff) }
            )
        } else null
        if (name == null && group == null && mac == null) return null
        return Info(name, group, mac)
    }
}
