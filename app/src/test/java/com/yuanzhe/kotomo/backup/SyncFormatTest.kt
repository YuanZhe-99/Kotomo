package com.yuanzhe.kotomo.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers sync's rules (T19, PLAN §11.7, MyApps-DATA's invariants): what each side's changes lead
 * to, a conflict never settled by itself, the lock and its expiry, and the formats keeping
 * unknown members and defaulting to off without the clipboard or history data.
 */
class SyncFormatTest {
    private val last = ModuleSync(100L, "aaa")

    @Test
    fun eachSideChangingDecidesTheAction() { // T19-conflict
        assertEquals(SyncAction.NONE, SyncFormat.decide(last, 100L, "aaa"))
        assertEquals(SyncAction.UPLOAD, SyncFormat.decide(last, 200L, "aaa"))
        assertEquals(SyncAction.DOWNLOAD, SyncFormat.decide(last, 100L, "bbb"))
        assertEquals(SyncAction.CONFLICT, SyncFormat.decide(last, 200L, "bbb"))
        // First sync: data on both sides is a conflict, never a silent overwrite.
        assertEquals(SyncAction.CONFLICT, SyncFormat.decide(null, 50L, "bbb"))
        assertEquals(SyncAction.UPLOAD, SyncFormat.decide(null, 50L, null))
        assertEquals(SyncAction.DOWNLOAD, SyncFormat.decide(null, null, "bbb"))
        assertEquals(SyncAction.NONE, SyncFormat.decide(null, null, null))
    }

    @Test
    fun theLockKeepsOtherDevicesOutUntilItExpires() { // T19-lock
        val lock = SyncFormat.lock("phone", 1_000L)
        assertTrue(SyncFormat.lockedOut(lock, "tablet", 1_000L + SyncFormat.LOCK_MS - 1))
        assertFalse(SyncFormat.lockedOut(lock, "tablet", 1_000L + SyncFormat.LOCK_MS + 1))
        assertFalse(SyncFormat.lockedOut(lock, "phone", 1_000L))
        assertFalse(SyncFormat.lockedOut(null, "tablet", 1_000L))
        assertFalse(SyncFormat.lockedOut("not json", "tablet", 1_000L))
    }

    @Test
    fun configDefaultsOffAndKeepsUnknownMembers() { // T19-config
        val d = SyncConfig()
        assertEquals(SyncKind.OFF, d.kind)
        assertFalse(d.auto)
        assertFalse(BackupModule.LEARNED in d.modules || BackupModule.MEMORY in d.modules)
        val c = SyncConfig(SyncKind.WEBDAV, null, "https://dav.example/kotomo/", "me", "dav.example", setOf(BackupModule.THEMES), true, "dev-1", mapOf("future" to 1L))
        val back = SyncFormat.readConfig(SyncFormat.writeConfig(c, 9L))!!
        assertEquals(c.copy(extra = emptyMap()), back.copy(extra = emptyMap()))
        assertEquals(1L, back.extra["future"])
        // Negative: a plain-HTTP address is not kept.
        assertEquals(null, SyncFormat.readConfig("""{"kind":"webdav","url":"http://dav.example/"}""")!!.url)
    }

    @Test
    fun stateRoundTrips() { // T19-config
        val s = SyncState(mapOf(BackupModule.SETTINGS to last), setOf(BackupModule.THEMES), 5L)
        assertEquals(s, SyncFormat.readState(SyncFormat.writeState(s, 6L)))
    }
}
