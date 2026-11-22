package eu.einhq.app

import eu.einhq.app.backup.Backup
import eu.einhq.app.backup.BackupChange
import eu.einhq.app.backup.BackupSettings
import eu.einhq.app.backup.BackupTarget
import eu.einhq.app.backup.EinUploads
import eu.einhq.app.backup.MediaItem
import eu.einhq.app.backup.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val staging = BackupTarget("/home/anna/staging/files/annas-phone", "Anna's phone")

    @Test
    fun `a change keeps what it does not name`() {
        val settings = BackupSettings(enabled = true, into = staging, folders = setOf("DCIM/Camera/", "Pictures/Screenshots/"))
        val changed = BackupChange(wifiOnly = false).applyTo(settings)
        assertEquals(settings.copy(wifiOnly = false), changed)
    }

    @Test
    fun `a backup with nowhere to send to is off`() {
        assertFalse(BackupChange(enabled = true).applyTo(BackupSettings()).enabled)
        val on = BackupChange(enabled = true, into = staging).applyTo(BackupSettings())
        assertTrue(on.active)
        val cleared = BackupChange(clearInto = true).applyTo(on)
        assertNull(cleared.into)
        assertFalse(cleared.enabled)
    }

    @Test
    fun `the camera's folder is chosen until others are`() {
        assertEquals(setOf("DCIM/Camera/"), BackupSettings().folders)
        assertEquals(emptySet<String>(), BackupChange(folders = emptySet()).applyTo(BackupSettings()).folders)
    }

    @Test
    fun `what was sent at the version the phone has is not sent again, and a changed one is`() {
        val beach = MediaItem(1, false, "PXL_20260704_101500123.jpg", "DCIM/Camera/", 3_000_000, 1_783_160_100)
        val clip = MediaItem(2, true, "PXL_20260704_101800456.mp4", "DCIM/Camera/", 80_000_000, 1_783_160_300)
        val edited = MediaItem(3, false, "PXL_20260705_090000789.jpg", "DCIM/Camera/", 2_500_000, 1_783_250_000)
        val sent = mapOf(1L to beach.version, 3L to Version(2_400_000, 1_783_240_000))
        assertEquals(listOf(clip, edited), Backup.toSend(listOf(beach, clip, edited), sent))
    }

    @Test
    fun `tus metadata gives each value in Base64`() {
        assertEquals(
            "dir L2hvbWUvYW5uYQ==,filename w4VyZS5qcGc=,mtime MTc4MzE2MDEwMA==",
            EinUploads.metadata("dir" to "/home/anna", "filename" to "Åre.jpg", "mtime" to "1783160100"),
        )
    }
}
