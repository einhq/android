package eu.einhq.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {
    @Test
    fun `an origin is the scheme, host and a port that is not the default`() {
        assertEquals("https://ein.example", originOf("https://ein.example/albums?x=1"))
        assertEquals("https://ein.example:8443", originOf("https://ein.example:8443/"))
        assertEquals("http://ein.example", originOf("http://ein.example:80"))
        assertNull(originOf("mailto:anna@example.com"))
        assertNull(originOf("about:blank"))
    }

    @Test
    fun `a typed address is https unless it says otherwise`() {
        assertEquals("https://ein.example", serverOf(" ein.example "))
        assertEquals("https://ein.example:8443", serverOf("ein.example:8443/albums"))
        assertEquals("http://192.168.1.10:8080", serverOf("http://192.168.1.10:8080"))
        assertEquals("https://ein.example", serverOf("HTTPS://EIN.example/"))
        assertNull(serverOf(""))
        assertNull(serverOf("ftp://ein.example"))
    }

    @Test
    fun `the sign-in page is the paired server's login and nothing else`() {
        assertTrue(isSignInPage("https://ein.example/login?next=%2Falbums", "https://ein.example"))
        assertTrue(isSignInPage("https://ein.example/login", "https://ein.example"))
        assertFalse(isSignInPage("https://ein.example/login/help", "https://ein.example"))
        assertFalse(isSignInPage("https://other.example/login", "https://ein.example"))
    }

    @Test
    fun `signing in goes on to next when it is a path of the same server`() {
        assertEquals("/albums/summer?tab=map", signInNext("https://ein.example/login?next=%2Falbums%2Fsummer%3Ftab%3Dmap"))
        assertEquals("/", signInNext("https://ein.example/login"))
        assertEquals("/", signInNext("https://ein.example/login?next=https%3A%2F%2Fother.example"))
        assertEquals("/", signInNext("https://ein.example/login?next=%2F%2Fother.example"))
        assertEquals("/", signInNext("https://ein.example/login?next=%2Flogin%3Fnext%3D%252F"))
    }

    @Test
    fun `a name given twice in a query is its first`() {
        assertEquals(mapOf("a" to "1", "b" to "x y"), queryOf("a=1&b=x+y&a=2"))
    }
}
