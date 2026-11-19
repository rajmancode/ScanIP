package com.scanip.app.net.snmp

import java.io.ByteArrayOutputStream

/** A decoded SNMP variable value. */
sealed class SnmpValue {
    data class Num(val value: Long) : SnmpValue()
    class Bytes(val value: ByteArray) : SnmpValue() {
        /** Printable text, or null when the bytes are binary (e.g. a MAC address). */
        fun textOrNull(): String? {
            val s = value.toString(Charsets.UTF_8).trimEnd('\u0000')
            return if (s.isNotEmpty() && s.all { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }) s else null
        }
        fun hex(sep: String = ":"): String = value.joinToString(sep) { "%02X".format(it.toInt() and 0xff) }
        override fun toString() = textOrNull() ?: hex()
    }
    data class Oid(val value: String) : SnmpValue()
    data class Ip(val value: String) : SnmpValue()
    object Null : SnmpValue()
    /** noSuchObject / noSuchInstance / endOfMibView. */
    data class Missing(val tag: Int) : SnmpValue()
}

data class VarBind(val oid: String, val value: SnmpValue)

data class SnmpResponse(val requestId: Int, val errorStatus: Int, val varBinds: List<VarBind>)

/** BER encoding of SNMP v1/v2c messages (RFC 1157, RFC 3416). */
object SnmpCodec {
    const val V1 = 0
    const val V2C = 1

    const val GET = 0xA0
    const val GET_NEXT = 0xA1
    const val RESPONSE = 0xA2
    const val GET_BULK = 0xA5

    fun encodeRequest(
        version: Int,
        community: String,
        pduType: Int,
        requestId: Int,
        oids: List<String>,
        nonRepeaters: Int = 0,
        maxRepetitions: Int = 0,
    ): ByteArray {
        val varBinds = ByteArrayOutputStream()
        for (oid in oids) {
            varBinds.write(tlv(0x30, tlv(0x06, encodeOid(oid)) + byteArrayOf(0x05, 0x00)))
        }
        val second = if (pduType == GET_BULK) nonRepeaters else 0
        val third = if (pduType == GET_BULK) maxRepetitions else 0
        val pdu = tlv(
            pduType,
            integer(requestId.toLong()) + integer(second.toLong()) + integer(third.toLong()) +
                tlv(0x30, varBinds.toByteArray()),
        )
        return tlv(0x30, integer(version.toLong()) + tlv(0x04, community.toByteArray(Charsets.UTF_8)) + pdu)
    }

    fun decodeResponse(data: ByteArray, length: Int = data.size): SnmpResponse? = try {
        val r = Reader(data, 0, length)
        val msg = r.enter(0x30)
        msg.readInteger() // version
        msg.read(0x04) // community
        val (pduTag, pdu) = msg.enterAny()
        if (pduTag != RESPONSE) null
        else {
            val reqId = pdu.readInteger().toInt()
            val errStatus = pdu.readInteger().toInt()
            pdu.readInteger() // error index
            val list = pdu.enter(0x30)
            val binds = mutableListOf<VarBind>()
            while (list.hasMore()) {
                val vb = list.enter(0x30)
                val oid = decodeOid(vb.read(0x06))
                val (tag, bytes) = vb.readAny()
                binds += VarBind(oid, decodeValue(tag, bytes))
            }
            SnmpResponse(reqId, errStatus, binds)
        }
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun decodeValue(tag: Int, b: ByteArray): SnmpValue = when (tag) {
        0x02 -> SnmpValue.Num(signed(b))
        0x41, 0x42, 0x43, 0x46 -> SnmpValue.Num(unsigned(b)) // Counter32, Gauge32, TimeTicks, Counter64
        0x04, 0x44 -> SnmpValue.Bytes(b)
        0x06 -> SnmpValue.Oid(decodeOid(b))
        0x40 -> if (b.size == 4) SnmpValue.Ip(b.joinToString(".") { (it.toInt() and 0xff).toString() }) else SnmpValue.Bytes(b)
        0x05 -> SnmpValue.Null
        0x80, 0x81, 0x82 -> SnmpValue.Missing(tag)
        else -> SnmpValue.Bytes(b)
    }

    fun encodeOid(oid: String): ByteArray {
        val parts = oid.trim('.').split('.').map { it.toLong() }
        require(parts.size >= 2)
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = p
            stack.add((v and 0x7f).toInt())
            v = v shr 7
            while (v > 0) {
                stack.add(((v and 0x7f) or 0x80).toInt())
                v = v shr 7
            }
            for (i in stack.indices.reversed()) out.write(stack[i])
        }
        return out.toByteArray()
    }

    fun decodeOid(b: ByteArray): String {
        if (b.isEmpty()) return ""
        val first = b[0].toInt() and 0xff
        val sb = StringBuilder()
        sb.append(if (first >= 80) 2 else first / 40).append('.').append(if (first >= 80) first - 80 else first % 40)
        var v = 0L
        for (i in 1 until b.size) {
            val x = b[i].toInt() and 0xff
            v = (v shl 7) or (x and 0x7f).toLong()
            if (x and 0x80 == 0) {
                sb.append('.').append(v)
                v = 0
            }
        }
        return sb.toString()
    }

    /** Compares dotted OIDs numerically. */
    fun compareOids(a: String, b: String): Int {
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toLongOrNull() ?: 0).compareTo(y[i].toLongOrNull() ?: 0)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    fun isUnder(oid: String, root: String) = oid.startsWith("$root.")

    private fun integer(v: Long): ByteArray {
        var bytes = java.math.BigInteger.valueOf(v).toByteArray()
        if (bytes.isEmpty()) bytes = byteArrayOf(0)
        return tlv(0x02, bytes)
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = value.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xff) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xff); out.write(len and 0xff) }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun signed(b: ByteArray): Long = if (b.isEmpty()) 0 else java.math.BigInteger(b).toLong()
    private fun unsigned(b: ByteArray): Long = if (b.isEmpty()) 0 else java.math.BigInteger(1, b).toLong()

    private class Reader(val d: ByteArray, var pos: Int, val end: Int) {
        fun hasMore() = pos < end

        fun readAny(): Pair<Int, ByteArray> {
            val (tag, start, len) = header()
            pos = start + len
            return tag to d.copyOfRange(start, start + len)
        }

        fun read(expected: Int): ByteArray {
            val (tag, bytes) = readAny()
            check(tag == expected) { "Expected tag $expected, got $tag" }
            return bytes
        }

        fun readInteger(): Long {
            val b = read(0x02)
            return if (b.isEmpty()) 0 else java.math.BigInteger(b).toLong()
        }

        fun enter(expected: Int): Reader {
            val (tag, r) = enterAny()
            check(tag == expected) { "Expected tag $expected, got $tag" }
            return r
        }

        fun enterAny(): Pair<Int, Reader> {
            val (tag, start, len) = header()
            pos = start + len
            return tag to Reader(d, start, start + len)
        }

        private fun header(): Triple<Int, Int, Int> {
            check(pos + 2 <= end) { "Truncated" }
            val tag = d[pos].toInt() and 0xff
            var p = pos + 1
            var len = d[p].toInt() and 0xff
            p++
            if (len and 0x80 != 0) {
                val n = len and 0x7f
                check(n in 1..3 && p + n <= end) { "Bad length" }
                len = 0
                repeat(n) { len = (len shl 8) or (d[p].toInt() and 0xff); p++ }
            }
            check(p + len <= end) { "Truncated value" }
            return Triple(tag, p, len)
        }
    }
}
