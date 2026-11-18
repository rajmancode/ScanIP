package com.scanip.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class ProtocolTest {

    @Test fun reverseName() {
        assertEquals("4.1.168.192.in-addr.arpa", DnsMessage.reverseName("192.168.1.4"))
    }

    @Test fun parsesPtrAnswerWithCompression() {
        val query = DnsMessage.buildQuery("4.1.168.192.in-addr.arpa", DnsMessage.TYPE_PTR, id = 7)
        val out = ByteArrayOutputStream()
        out.write(query, 0, query.size)
        // Flip to a response with ANCOUNT = 1.
        val bytes = out.toByteArray()
        bytes[2] = 0x81.toByte(); bytes[3] = 0x80.toByte(); bytes[7] = 1
        val answer = ByteArrayOutputStream()
        answer.write(bytes)
        answer.write(byteArrayOf(0xC0.toByte(), 12)) // name -> pointer to question
        answer.write(byteArrayOf(0, 12, 0, 1, 0, 0, 0, 60)) // PTR IN TTL
        val target = byteArrayOf(7) + "macbook".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0)
        answer.write(byteArrayOf(0, target.size.toByte()))
        answer.write(target)
        assertEquals("macbook.local", DnsMessage.parsePtrAnswer(answer.toByteArray()))
    }

    @Test fun netbiosRequestEncodesWildcard() {
        val p = NetBios.buildNodeStatusRequest()
        assertEquals(50, p.size)
        assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(p, 13, 32))
        assertArrayEquals(byteArrayOf(0, 0x21, 0, 1), p.copyOfRange(46, 50))
    }

    @Test fun parsesNetbiosNodeStatus() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4E, 0x42, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0))
        out.write(NetBios.buildNodeStatusRequest().copyOfRange(12, 46)) // name
        out.write(byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0, 0, 0)) // type, class, ttl, rdlength (unused)
        out.write(3)
        fun entry(name: String, suffix: Int, flags: Int) {
            out.write(name.padEnd(15, ' ').toByteArray(Charsets.US_ASCII))
            out.write(suffix); out.write(flags); out.write(0)
        }
        entry("DESKTOP-1", 0x00, 0x04)
        entry("WORKGROUP", 0x00, 0x84)
        entry("DESKTOP-1", 0x20, 0x04)
        out.write(byteArrayOf(0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E))
        val info = NetBios.parseNodeStatusResponse(out.toByteArray())!!
        assertEquals("DESKTOP-1", info.name)
        assertEquals("WORKGROUP", info.workgroup)
        assertEquals("00:1A:2B:3C:4D:5E", info.mac)
    }

    @Test fun describesHttp() {
        val r = "HTTP/1.1 200 OK\r\nServer: nginx\r\nContent-Type: text/html\r\n\r\n<html><head><title>\n Router  Login </title>"
        assertEquals("Router Login — nginx", PortScanner.describeHttp(r))
        assertEquals("401 Unauthorized", PortScanner.describeHttp("HTTP/1.1 401 Unauthorized\r\n\r\n"))
        assertNull(PortScanner.describeHttp("SSH-2.0-OpenSSH"))
    }

    @Test fun parsesPortLists() {
        assertEquals(listOf(22, 80, 8000, 8001, 8002), PortScanner.parsePorts("80, 22,8000-8002"))
        assertNull(PortScanner.parsePorts("0"))
        assertNull(PortScanner.parsePorts("1-65535"))
    }

    @Test fun wakeOnLanPacket() {
        val p = WakeOnLan.magicPacket(IpUtils.macToBytes("01:02:03:04:05:06")!!)
        assertEquals(102, p.size)
        assertEquals(0xFF.toByte(), p[0])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), p.copyOfRange(96, 102))
    }
}
