package eu.einhq.app

import eu.einhq.app.track.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixTest {
    @Test
    fun `a fix is EiN's own point with only what is known`() {
        val fix = Fix(lat = 57.5, lng = 11.9, time = 1_717_236_120_000, accuracy = 12.0, battery = 53, charging = false, activity = "in_vehicle")
        assertEquals(
            """{"lat":57.5,"lng":11.9,"ts":"2024-06-01T10:02:00Z","accuracy":12.0,"battery":53,"charging":false,"activity":"in_vehicle"}""",
            fix.json(),
        )
        assertEquals("""{"lat":-1.0,"lng":2.0,"ts":"1970-01-01T00:00:00Z"}""", Fix(lat = -1.0, lng = 2.0, time = 0).json())
    }

    @Test
    fun `fixes go as a JSON list`() {
        assertEquals("[]", Fix.body(emptyList()))
        assertEquals("""[{"a":1},{"b":2}]""", Fix.body(listOf("""{"a":1}""", """{"b":2}""")))
    }

    @Test
    fun `a fix less sure than about 100 m is left out`() {
        assertTrue(Fix(lat = 57.5, lng = 11.9, time = 0, accuracy = 100.0).usable)
        assertTrue(Fix(lat = 57.5, lng = 11.9, time = 0).usable)
        assertFalse(Fix(lat = 57.5, lng = 11.9, time = 0, accuracy = 1500.0).usable)
        assertFalse(Fix(lat = 91.0, lng = 11.9, time = 0).usable)
    }
}
