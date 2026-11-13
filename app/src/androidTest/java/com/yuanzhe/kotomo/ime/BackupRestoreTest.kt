package com.yuanzhe.kotomo.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuanzhe.kotomo.backup.BackupFormat
import com.yuanzhe.kotomo.backup.BackupModule
import com.yuanzhe.kotomo.backup.Backups
import com.yuanzhe.kotomo.settings.ColorRole
import com.yuanzhe.kotomo.settings.SettingsStore
import com.yuanzhe.kotomo.settings.Theme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backs up, changes, restores and undoes on the device (T19, PLAN §11.6): the settings and theme
 * come back from a sealed backup, a part changed after the backup is flagged as newer, and Undo
 * brings the changed data back.
 * Notes: puts the settings and theme back afterwards; nothing typed is involved.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SettingsStore.get(context)
    private val backups = Backups(context)

    @Test
    fun backupRestoresAndUndoes() = runBlocking {
        val settingsBefore = store.current
        val themeBefore = store.themeFlow.value ?: Theme()
        try {
            store.update { it.copy(incognito = false) }
            store.updateTheme { it.copy(colors = mapOf(ColorRole.KEY to 0xFF123456.toInt())) }
            val sealed = BackupFormat.pack(backups.collect(setOf(BackupModule.SETTINGS, BackupModule.THEMES)), "a long password".toCharArray())
            Thread.sleep(1_100)
            store.update { it.copy(incognito = true) }
            store.updateTheme { it.copy(colors = emptyMap()) }
            val content = BackupFormat.unpack(sealed, "a long password".toCharArray())
            // The data changed after the backup: the preview says this device is newer.
            assertTrue(backups.preview(content).all { it.deviceIsNewer })
            assertEquals(setOf(BackupModule.SETTINGS, BackupModule.THEMES), backups.restore(content, setOf(BackupModule.SETTINGS, BackupModule.THEMES)))
            Thread.sleep(500)
            assertFalse(store.current.incognito)
            assertEquals(0xFF123456.toInt(), store.themeFlow.value?.colors?.get(ColorRole.KEY))
            assertTrue(backups.undo())
            Thread.sleep(500)
            assertTrue(store.current.incognito)
            assertTrue(store.themeFlow.value?.colors.orEmpty().isEmpty())
            assertFalse(backups.canUndo())
        } finally {
            store.update { settingsBefore }
            store.updateTheme { themeBefore }
        }
    }
}
