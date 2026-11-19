package com.scanip.app.topology

import com.scanip.app.net.snmp.FdbEntry
import com.scanip.app.net.snmp.LldpNeighbor
import com.scanip.app.net.snmp.SnmpDetails
import com.scanip.app.net.snmp.SnmpTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyBuilderTest {
    private fun mac(n: Int) = "00:00:00:00:00:%02X".format(n)

    private fun switch(ip: String, name: String, bridgeMac: String, fdb: Map<Int, List<String>>, lldp: List<LldpNeighbor> = emptyList()) =
        SnmpDetails(
            target = SnmpTarget(ip, "public", 1), sysName = name, sysDescr = null, sysObjectId = null, sysLocation = null,
            bridgeMac = bridgeMac, ipForwarding = false,
            ports = (1..24).associateWith { "Gi0/$it" },
            addresses = listOf(ip to 24), arp = emptyMap(),
            fdb = fdb.flatMap { (port, macs) -> macs.map { FdbEntry(it, 1, port, port) } },
            lldp = lldp,
        )

    // Router(.1, mac 1) - core(.2, mac 2) port 1 ; core port 24 -> access(.3, mac 3) port 23
    // PC A (.10, mac 10) on access port 5; PC B (.11) and C (.12) both on core port 7 (unmanaged switch)
    private val devices = listOf(
        TopoDevice("192.168.1.1", mac(1), "router", isGateway = true, isSelf = false),
        TopoDevice("192.168.1.2", mac(2), "core", false, false),
        TopoDevice("192.168.1.3", mac(3), "access", false, false),
        TopoDevice("192.168.1.10", mac(0x10), "PC A", false, false),
        TopoDevice("192.168.1.11", mac(0x11), "PC B", false, false),
        TopoDevice("192.168.1.12", mac(0x12), "PC C", false, false),
        TopoDevice("192.168.1.50", null, "no mac", false, true),
    )

    private val core = switch("192.168.1.2", "core", mac(2), mapOf(
        1 to listOf(mac(1)),
        24 to listOf(mac(3), mac(0x10)),
        7 to listOf(mac(0x11), mac(0x12)),
    ))
    private val access = switch("192.168.1.3", "access", mac(3), mapOf(
        23 to listOf(mac(1), mac(2), mac(0x11), mac(0x12)),
        5 to listOf(mac(0x10)),
    ))

    private fun build(snmp: Map<String, SnmpDetails>) =
        TopologyBuilder(devices, snmp, emptyMap(), listOf("192.168.1.0/24")).build()

    @Test fun infersSwitchLinksAndPorts() {
        val t = build(mapOf(core.target.ip to core, access.target.ip to access))
        assertEquals("192.168.1.1", t.root)
        val tree = t.tree()
        assertEquals("192.168.1.2", tree.children["192.168.1.1"]?.first())
        assertTrue("192.168.1.3" in tree.children["192.168.1.2"].orEmpty())
        assertEquals("core · Gi0/1", t.attachments["192.168.1.1"])
        assertEquals("access · Gi0/5", t.attachments["192.168.1.10"])
        assertEquals("Gi0/24 ↔ Gi0/23", tree.portLabel("192.168.1.2", "192.168.1.3"))
        // Two devices on one port -> unmanaged switch node.
        assertTrue(t.nodes.values.any { it.kind == NodeKind.UNMANAGED })
        assertEquals("core · Gi0/7 (shared)", t.attachments["192.168.1.11"])
        // Device without MAC lands under its subnet.
        assertTrue(t.links.any { it.a == "subnet:192.168.1.0/24" && it.b == "192.168.1.50" })
        assertTrue(tree.unreachable.isEmpty())
    }

    @Test fun usesLldpWhenTablesAreEmpty() {
        val c = core.copy(fdb = emptyList(), lldp = listOf(LldpNeighbor(24, "Gi0/24", mac(3), "Gi0/23", "uplink", "access")))
        val a = access.copy(fdb = emptyList(), lldp = listOf(LldpNeighbor(23, "Gi0/23", mac(2), "Gi0/24", null, "core")))
        val t = build(mapOf(c.target.ip to c, a.target.ip to a))
        val l = t.links.single { setOf(it.a, it.b) == setOf("192.168.1.2", "192.168.1.3") }
        assertEquals("Gi0/24", if (l.a == "192.168.1.2") l.portA else l.portB)
    }

    @Test fun layoutHasNoOverlaps() {
        val t = build(mapOf(core.target.ip to core, access.target.ip to access))
        val tree = t.tree()
        val r = TreeLayout(tree.children).layout(tree.root!!)
        val boxes = r.boxes.values.toList()
        assertEquals(t.nodes.size, boxes.size)
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            val overlap = a.x < b.x + 150 && b.x < a.x + 150 && a.y < b.y + 58 && b.y < a.y + 58
            assertTrue("boxes $a and $b overlap", !overlap)
        }
    }
}
