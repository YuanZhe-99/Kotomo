package com.yuanzhe.kotomo.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuanzhe.kotomo.backup.BackupFormat
import com.yuanzhe.kotomo.backup.BackupModule
import com.yuanzhe.kotomo.backup.Backups
import com.yuanzhe.kotomo.backup.Remote
import com.yuanzhe.kotomo.backup.Sync
import com.yuanzhe.kotomo.backup.SyncFormat
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Syncs the settings through an in-memory place on the device (T19, PLAN §11.7): the first sync
 * sends them, another device's change comes in, a change on both sides stays a conflict until
 * the person chooses, and the lock is released afterwards.
 * Notes: the place stands in for a folder or WebDAV server; the settings and sync state are put
 * back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SyncDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SettingsStore.get(context)

    /** Files kept in memory, as a synced folder would keep them. */
    private class Memory : Remote {
        val files = HashMap<String, ByteArray>()
        override fun get(name: String) = files[name]
        override fun put(name: String, bytes: ByteArray) { files[name] = bytes }
        override fun delete(name: String) { files.remove(name) }
    }

    @Test
    fun sendsReceivesAndLeavesConflictsToThePerson() = runBlocking {
        val before = store.current
        val config = Sync.config(context)
        val state = File(context.filesDir, "sync/state.json")
        state.delete()
        Sync.saveConfig(context, config.copy(modules = setOf(BackupModule.SETTINGS)))
        val place = Memory()
        val name = SyncFormat.fileOf(BackupModule.SETTINGS)
        try {
            // Make sure the settings file exists (a fresh install has none, and then nothing is sent).
            store.update { it.copy(incognito = true) }
            store.update { it.copy(incognito = false) }
            assertEquals(setOf(BackupModule.SETTINGS), Sync.run(context, at = place).uploaded)
            assertTrue(name in place.files)
            assertFalse(SyncFormat.LOCK in place.files)

            // Another device turned incognito on and synced.
            store.update { it.copy(incognito = true) }
            val theirs = BackupFormat.pack(Backups(context).collect(setOf(BackupModule.SETTINGS)), null)
            store.update { it.copy(incognito = false) }
            // As if this device had not changed since the last sync: only the copy changed.
            state.writeText(SyncFormat.writeState(Sync.state(context).let { s -> s.copy(modules = s.modules.mapValues { it.value.copy(localUtcMs = Long.MAX_VALUE) }) }, 0))
            place.files[name] = theirs
            assertEquals(setOf(BackupModule.SETTINGS), Sync.run(context, at = place).downloaded)
            Thread.sleep(300)
            assertTrue(store.current.incognito)

            // Both sides change: a conflict, and nothing changes until the person chooses.
            Thread.sleep(1_100)
            store.update { it.copy(incognito = false) }
            place.files[name] = BackupFormat.pack(Backups(context).collect(setOf(BackupModule.SETTINGS)).let { c -> c.copy(manifest = c.manifest.copy(appVersion = "other")) }, null)
            val report = Sync.run(context, at = place)
            assertEquals(setOf(BackupModule.SETTINGS), report.conflicts)
            assertEquals(setOf(BackupModule.SETTINGS), Sync.state(context).conflicts)
            assertFalse(store.current.incognito)
            // The person keeps this device's version: it is sent, and the conflict is gone.
            val resolved = Sync.run(context, mapOf(BackupModule.SETTINGS to true), at = place)
            assertEquals(setOf(BackupModule.SETTINGS), resolved.uploaded)
            assertTrue(Sync.state(context).conflicts.isEmpty())
        } finally {
            store.update { before }
            Sync.saveConfig(context, config)
            state.delete()
        }
    }
}
