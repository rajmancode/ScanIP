package com.scanip.app.net.snmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnmpCodecTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun encodesGetRequestLikeNetSnmp() {
        // snmpget -v2c -c public host 1.3.6.1.2.1.1.5.0 with request-id 1
        val expected = hex("30 26 02 01 01 04 06 70 75 62 6c 69 63 a0 19 02 01 01 02 01 00 02 01 00 30 0e 30 0c 06 08 2b 06 01 02 01 01 05 00 05 00")
        val actual = SnmpCodec.encodeRequest(SnmpCodec.V2C, "public", SnmpCodec.GET, 1, listOf("1.3.6.1.2.1.1.5.0"))
        assertArrayEquals(expected, actual)
    }

    @Test fun oidRoundTripWithLargeArcs() {
        for (oid in listOf("1.3.6.1.2.1.17.7.1.2.2.1.2.1.0.17.34.51.68.85", "1.0.8802.1.1.2.1.4.1.1.9", "1.3.6.1.4.1.39165.1.1")) {
            assertEquals(oid, SnmpCodec.decodeOid(SnmpCodec.encodeOid(oid)))
        }
    }

    @Test fun decodesResponseValues() {
        // Response with sysName = "core-sw", an IpAddress and a Counter32 > 2^31.
        val varbinds = listOf(
            hex("06 08 2b 06 01 02 01 01 05 00") + hex("04 07") + "core-sw".toByteArray(),
            hex("06 0a 2b 06 01 02 01 04 14 01 03 01") + hex("40 04 ff ff ff 00"),
            hex("06 08 2b 06 01 02 01 01 03 00") + hex("41 05 00 80 00 00 01"),
        ).map { seq(0x30, it) }
        val list = seq(0x30, varbinds.reduce { a, b -> a + b })
        val pdu = seq(0xa2, hex("02 01 07 02 01 00 02 01 00") + list)
        val msg = seq(0x30, hex("02 01 01 04 06") + "public".toByteArray() + pdu)
        val r = SnmpCodec.decodeResponse(msg)!!
        assertEquals(7, r.requestId)
        assertEquals("core-sw", (r.varBinds[0].value as SnmpValue.Bytes).textOrNull())
        assertEquals(SnmpValue.Ip("255.255.255.0"), r.varBinds[1].value)
        assertEquals(SnmpValue.Num(2147483649L), r.varBinds[2].value)
    }

    @Test fun rejectsGarbage() {
        assertNull(SnmpCodec.decodeResponse(byteArrayOf(0x30, 0x7f, 0x02)))
        assertNull(SnmpCodec.decodeResponse(ByteArray(0)))
    }

    @Test fun comparesOidsNumerically() {
        assertTrue(SnmpCodec.compareOids("1.3.6.1.2", "1.3.6.1.10") < 0)
        assertTrue(SnmpCodec.compareOids("1.3.6.1", "1.3.6.1.0") < 0)
        assertTrue(SnmpCodec.isUnder("1.3.6.1.2.1.1.5.0", "1.3.6.1.2.1.1"))
        assertTrue(!SnmpCodec.isUnder("1.3.6.1.2.1.10", "1.3.6.1.2.1.1"))
    }

    @Test fun parsesMasksAndMacIndexes() {
        assertEquals(24, SnmpCollector.maskToPrefix("255.255.255.0"))
        assertNull(SnmpCollector.maskToPrefix("255.0.255.0"))
        assertEquals("00:11:22:AA:BB:CC", SnmpCollector.macFromIndex(listOf("0", "17", "34", "170", "187", "204")))
    }

    private fun seq(tag: Int, body: ByteArray): ByteArray {
        require(body.size < 128)
        return byteArrayOf(tag.toByte(), body.size.toByte()) + body
    }
}
