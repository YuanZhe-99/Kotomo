package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.settings.ChineseScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** PLAN §8.8 Clear learning data: what goes and, as negative cases, what stays. */
class LearningDataTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun learnedFilesGoAndTheDeployedDataStays() {
        val user = folder.newFolder("user")
        File(user, "luna_pinyin.userdb").apply { mkdirs(); File(this, "CURRENT").writeText("x") }
        File(user, "luna_pinyin.userdb.txt").writeText("x")
        File(user, "sync").apply { mkdirs(); File(this, "a.txt").writeText("x") }
        File(user, "build").apply { mkdirs(); File(this, "luna_pinyin.prism.bin").writeText("x") }
        File(user, "user.yaml").writeText("x")
        val profile = folder.newFolder("profile").also { File(it, "history.db").writeText("x") }
        LearningData.clearFiles(user, profile)
        assertEquals(setOf("build", "user.yaml"), user.list()!!.toSet())
        assertTrue(File(user, "build/luna_pinyin.prism.bin").isFile)
        assertTrue(profile.isDirectory && profile.list()!!.isEmpty())
    }

    @Test
    fun memoryKeepsOnlyFixedModes() {
        val memory = Memory(
            lastMode = Mode.CHINESE,
            apps = mapOf(
                "a" to AppMemory(mode = Mode.JAPANESE, script = ChineseScript.TAIWAN),
                "b" to AppMemory(mode = Mode.CHINESE, pinned = Mode.ENGLISH),
            ),
            emoji = EmojiMemory(listOf("🐱"), mapOf("🐱" to 3)),
        )
        val cleared = LearningData.forget(memory)
        assertNull(cleared.lastMode)
        assertFalse("a" in cleared.apps)
        assertEquals(AppMemory(pinned = Mode.ENGLISH), cleared.app("b"))
        assertEquals(EmojiMemory(), cleared.emoji)
    }
}
