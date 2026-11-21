package eu.einhq.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
    @Test
    fun `the QR code's link names the server, the phone and the code`() {
        assertEquals(
            PairingLink("https://ein.example", "anna-phone", "k3y-c0de"),
            PairingLink.parse("ein://pair?server=https%3A%2F%2Fein.example&device=anna-phone&code=k3y-c0de"),
        )
    }

    @Test
    fun `the server is taken as its origin, without a default port or a path`() {
        assertEquals("https://ein.example", PairingLink.parse("ein://pair?server=HTTPS%3A%2F%2FEin.Example%3A443%2Fdocs&device=a&code=b")?.server)
        assertEquals("http://192.168.1.20:8080", PairingLink.parse("ein://pair?server=http%3A%2F%2F192.168.1.20%3A8080&device=a&code=b")?.server)
    }

    @Test
    fun `a link that is not a pairing link, or misses a part, is none`() {
        assertNull(PairingLink.parse("https://ein.example/devices/anna-phone"))
        assertNull(PairingLink.parse("ein://share?server=https%3A%2F%2Fein.example&device=a&code=b"))
        assertNull(PairingLink.parse("ein://pair?server=https%3A%2F%2Fein.example&device=a"))
        assertNull(PairingLink.parse("ein://pair?server=https%3A%2F%2Fein.example&device=&code=b"))
        assertNull(PairingLink.parse("ein://pair?server=ftp%3A%2F%2Fein.example&device=a&code=b"))
        assertNull(PairingLink.parse("not a link at all"))
    }
}
