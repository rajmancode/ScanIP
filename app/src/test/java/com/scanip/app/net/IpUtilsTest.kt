package com.scanip.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpUtilsTest {
    @Test fun roundTrip() {
        val v = IpUtils.toLong("192.168.1.42")!!
        assertEquals("192.168.1.42", IpUtils.toIp(v))
    }

    @Test fun rejectsBadAddresses() {
        assertNull(IpUtils.toLong("256.1.1.1"))
        assertNull(IpUtils.toLong("1.2.3"))
        assertNull(IpUtils.toLong("1.2.3.x"))
    }

    @Test fun hostRangeExcludesNetworkAndBroadcast() {
        val r = IpUtils.hostRange("192.168.1.77", 24)!!
        assertEquals("192.168.1.1", IpUtils.toIp(r.first))
        assertEquals("192.168.1.254", IpUtils.toIp(r.last))
    }

    @Test fun clampsLargeSubnets() {
        val r = IpUtils.hostRange("10.20.30.40", 8)!!
        val c = IpUtils.clampAround(r, IpUtils.toLong("10.20.30.40")!!)
        assertEquals(IpUtils.MAX_HOSTS.toLong(), c.last - c.first + 1)
        assert(IpUtils.toLong("10.20.30.40")!! in c)
    }

    @Test fun parsesTargetSyntaxes() {
        assertEquals(254, IpUtils.parseTargets("192.168.1.0/24")!!.size)
        assertEquals(254, IpUtils.parseTargets("192.168.1.1-254")!!.size)
        assertEquals(41, IpUtils.parseTargets("192.168.1.10-192.168.1.50")!!.size)
        assertEquals(3, IpUtils.parseTargets("10.0.0.1, 10.0.0.2,10.0.0.1, 10.0.0.9")!!.size)
        assertNull(IpUtils.parseTargets("10.0.0.0/8"))
        assertNull(IpUtils.parseTargets("192.168.1.50-10"))
        assertNull(IpUtils.parseTargets(""))
    }

    @Test fun normalizesMacs() {
        assertEquals("AA:BB:CC:DD:EE:FF", IpUtils.normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertNull(IpUtils.normalizeMac("00:00:00:00:00:00"))
        assertNull(IpUtils.normalizeMac("zz:bb:cc:dd:ee:ff"))
    }

    @Test fun parsesIpNeigh() {
        val out = """
            192.168.1.1 dev wlan0 lladdr 11:22:33:44:55:66 REACHABLE
            192.168.1.9 dev wlan0  FAILED
            fe80::1 dev wlan0 lladdr 11:22:33:44:55:66 router STALE
        """.trimIndent()
        assertEquals(mapOf("192.168.1.1" to "11:22:33:44:55:66"), ArpTable.parseIpNeigh(out))
    }
}
