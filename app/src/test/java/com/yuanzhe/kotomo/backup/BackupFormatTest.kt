package com.yuanzhe.kotomo.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the backup file (T19, PLAN §11.6): round trip plain and sealed, the password checks,
 * untrusted files refused (unsafe names, a newer format, not a backup), per-module files, and the
 * automatic backups' config and versions.
 */
class BackupFormatTest {
    private val content = BackupContent(
        BackupManifest(1, 1_000L, "0.1.0", mapOf(BackupModule.SETTINGS to ModuleInfo(900L, 1, 3), BackupModule.THEMES to ModuleInfo(800L, 2, 5))),
        mapOf("settings/settings.json" to byteArrayOf(1, 2, 3), "themes/current.json" to byteArrayOf(4), "themes/images/a.png" to byteArrayOf(5, 6, 7, 8)),
    )

    @Test
    fun plainBackupRoundTrips() { // T19-roundtrip
        val back = BackupFormat.unpack(BackupFormat.pack(content, null), null)
        assertEquals(content.manifest, back.manifest)
        assertEquals(content.files.keys, back.files.keys)
        content.files.forEach { (k, v) -> assertArrayEquals(v, back.files.getValue(k)) }
        assertEquals(setOf("current.json", "images/a.png"), back.of(BackupModule.THEMES).keys)
    }

    @Test
    fun sealedBackupNeedsItsPassword() { // T19-encrypted
        val sealed = BackupFormat.pack(content, "correct horse".toCharArray())
        assertTrue(BackupFormat.sealed(sealed))
        assertEquals(BackupProblem.NEEDS_PASSWORD, problem { BackupFormat.unpack(sealed, null) })
        assertEquals(BackupProblem.WRONG_PASSWORD, problem { BackupFormat.unpack(sealed, "wrong".toCharArray()) })
        assertEquals(content.files.keys, BackupFormat.unpack(sealed, "correct horse".toCharArray()).files.keys)
    }

    @Test
    fun untrustedFilesAreRefused() { // T19-roundtrip (negative)
        assertEquals(BackupProblem.NOT_A_BACKUP, problem { BackupFormat.unpack("hello".toByteArray(), null) })
        assertEquals(BackupProblem.NOT_A_BACKUP, problem { BackupFormat.unpack(zip("manifest.json" to """{"format_version":1}""", "../evil" to "x"), null) })
        assertEquals(BackupProblem.NOT_A_BACKUP, problem { BackupFormat.unpack(zip("settings/x.json" to "{}"), null) })
        assertEquals(BackupProblem.NEWER, problem { BackupFormat.unpack(zip("manifest.json" to """{"format_version":99}"""), null) })
    }

    @Test
    fun autoBackupConfigAndVersions() { // T19-auto
        val c = AutoBackupConfig("content://tree/x", 7, 3, 42L, mapOf("future" to true))
        val back = AutoBackupFormat.read(AutoBackupFormat.write(c, 1L))!!
        assertEquals(c.copy(extra = emptyMap()), back.copy(extra = emptyMap()))
        assertEquals(true, back.extra["future"])
        assertFalse(AutoBackupFormat.read("""{"folder":"file:///sdcard","interval_days":3}""")!!.on)
        val names = listOf("kotomo-auto-20261101-000000.kbak", "kotomo-auto-20261103-000000.kbak", "kotomo-backup-20261102-000000.kbak", "kotomo-auto-20261102-000000.kbak")
        assertEquals(listOf("kotomo-auto-20261101-000000.kbak"), AutoBackupFormat.toDelete(names, 2))
        assertEquals("kotomo-auto-19700101-000000.kbak", AutoBackupFormat.name(0, auto = true))
    }

    private fun problem(block: () -> Unit): BackupProblem? = try {
        block()
        null
    } catch (e: BackupException) {
        e.problem
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }.toByteArray()
}
