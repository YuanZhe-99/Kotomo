package com.yuanzhe.kotomo.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuanzhe.kotomo.dictionary.DownloadException
import com.yuanzhe.kotomo.dictionary.DownloadProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Downloads and loads the Noto Color Emoji pack and refuses a file that is not a font
 * (T14-emoji-pack, PLAN §11.5) on an emulator or device.
 * Notes: the download needs raw.githubusercontent.com; it is skipped, not passed, when that cannot
 * be reached. The style is put back to the system's afterwards and the pack deleted.
 */
@RunWith(AndroidJUnit4::class)
class EmojiStyleDeviceTest {
    private val styles = EmojiStyles.get(InstrumentationRegistry.getInstrumentation().targetContext)

    /** Positive: the pinned file downloads, matches and loads as the emoji typeface. */
    @Test
    fun notoDownloadsAndLoads() = runBlocking {
        try {
            styles.downloadNoto { _, _ -> }
        } catch (e: DownloadException) {
            assumeTrue("raw.githubusercontent.com cannot be reached here", e.problem != DownloadProblem.NETWORK && e.problem != DownloadProblem.HTTP)
            throw e
        }
        try {
            assertEquals(EmojiStyles.NOTO_SIZE, styles.noto.length())
            styles.choose(EmojiStyle.NOTO)
            assertNotNull(styles.typeface())
            styles.choose(EmojiStyle.SYSTEM)
            assertNull(styles.typeface())
        } finally {
            styles.deleteNoto()
        }
    }

    /** Negative: a picture offered as a font is refused and changes nothing. */
    @Test
    fun aFileThatIsNotAFontIsRefused() = runBlocking {
        val before = styles.config.value
        assertFalse(styles.importCustom("\u0089PNG\r\n\u001a\nnot a font".byteInputStream(Charsets.ISO_8859_1), "fake"))
        assertEquals(before, styles.config.value)
        assertTrue(!styles.custom.isFile || before.style == EmojiStyle.CUSTOM)
    }
}
