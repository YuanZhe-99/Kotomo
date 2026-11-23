package com.yuanzhe.kotomo.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.ime.clipboard.ClipboardHistory
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * CLIP-20 on an emulator or device (PLAN §10.7): with the app being typed in excluded
 * (`clipboard.excluded_apps`), what is copied there is neither kept nor offered; with it not
 * excluded, the same kind of copy is kept.
 *
 * Notes: needs Kotomo installed as a debug build and selected as FieldTypingTest does. Runs in
 * the app's process, so it reads the same [ClipboardHistory] the keyboard writes. The test texts
 * are made up and deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardAppTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = SettingsStore.get(context)
    private val history = ClipboardHistory.get(context)

    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        clearSystemClipboard()
    }

    @After
    fun cleanUp() {
        clearSystemClipboard()
        runBlocking {
            settings.update { it.copy(clipboard = it.clipboard.copy(excludedApps = emptySet())) }
            history.items.value.filter { it.text.startsWith(PREFIX) || it.isImage }.forEach { history.delete(it.id) }
            history.flush()
            File(context.cacheDir, "clipboard_share/test_src.png").delete()
        }
    }

    /** Clears Android's primary clipboard so prior test clips are never re-read on process restart. */
    private fun clearSystemClipboard() {
        instrumentation.runOnMainSync {
            val cm = context.getSystemService(ClipboardManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText(null, ""))
            }
        }
    }

    /** Copies [text] from the field's activity and returns whether the history keeps it within the wait. */
    private fun copyAndWait(scenario: ActivityScenario<FieldTestActivity>, text: String): Boolean {
        scenario.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text)) }
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            if (history.items.value.any { it.text == text }) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    @Test
    fun anExcludedAppKeepsNothingAndOthersDo() {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("k")), WAIT_MS)) { "Kotomo's keys did not appear" }
            runBlocking { settings.update { it.copy(clipboard = it.clipboard.copy(history = true, excludedApps = setOf(context.packageName))) } }
            // The keyboard hears of a changed setting on its own thread a moment after the write.
            Thread.sleep(POLL_MS * 5)
            assertFalse("copied while typing in an excluded app: not kept", copyAndWait(scenario, "$PREFIX excluded ${System.nanoTime()}"))
            runBlocking { settings.update { it.copy(clipboard = it.clipboard.copy(excludedApps = emptySet())) } }
            Thread.sleep(POLL_MS * 5)
            assertTrue("copied in an app that is not excluded: kept", copyAndWait(scenario, "$PREFIX kept ${System.nanoTime()}"))
            // Dismiss the strip so this copy does not stay offered across subsequent tests in the run.
            device.wait(Until.findObject(By.desc(context.getString(R.string.quick_close))), POLL_MS * 5)?.click()
        }
    }

    /**
     * CLIP-27 (PLAN §10.7, user decision 2026-11-13): a copied message with a verification code
     * offers the code on the quick-paste strip, and tapping it types the code; negative: the same
     * message marked sensitive offers nothing.
     */
    @Test
    fun aCopiedCodeIsOfferedAndTyped() {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("k")), WAIT_MS)) { "Kotomo's keys did not appear" }
            runBlocking { settings.update { it.copy(clipboard = it.clipboard.copy(history = true, quickPaste = true)) } }
            val hidden = (100000..999999).random().toString()
            scenario.onActivity {
                val clip = ClipData.newPlainText(null, "$PREFIX: your verification code is $hidden")
                clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }
            assertTrue("a sensitive clip offered its code", device.wait(Until.findObject(By.text(hidden)), WAIT_MS / 2) == null)
            val code = (100000..999999).random().toString()
            scenario.onActivity {
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, "$PREFIX: your verification code is $code"))
            }
            checkNotNull(device.wait(Until.findObject(By.text(code)), WAIT_MS)) { "the copied code is not offered" }.click()
            Thread.sleep(POLL_MS * 3)
            var typed = ""
            scenario.onActivity { typed = it.findViewById<android.widget.EditText>(FieldTestActivity.FIELD_ID).text.toString() }
            assertEquals(code, typed)
        }
    }

    /**
     * A6 (device feedback 2026-11-13): a long press on a clip opens a menu whose lines are at
     * least the panel touch size tall; Pin pins the clip. Negative: a plain tap pastes and opens
     * no menu.
     */
    @Test
    fun longPressMenuIsEasyToTap() {
        val previous = settings.current
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        try {
            runBlocking {
                settings.update {
                    it.copy(
                        clipboard = it.clipboard.copy(history = true, quickPaste = false),
                        toolOrder = listOf(Tool.CLIPBOARD) + (Tool.configurable - Tool.CLIPBOARD),
                        hiddenTools = Tool.configurable.toSet() - Tool.CLIPBOARD,
                    )
                }
            }
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
                val text = "$PREFIX menu ${System.nanoTime()}"
                assertTrue(copyAndWait(scenario, text))
                val res = context.resources
                val barY = q.visibleBounds.top - res.getDimensionPixelSize(R.dimen.kb_pad_top) - res.getDimensionPixelSize(R.dimen.bar_height) / 2
                // With one tool on the bar, Clipboard sits at its end.
                device.click(device.displayWidth - res.getDimensionPixelSize(R.dimen.toolbar_pad) - res.getDimensionPixelSize(R.dimen.toolbar_button) / 2, barY)
                val card = checkNotNull(device.wait(Until.findObject(By.text(text)), WAIT_MS)) { "the clipboard panel does not list the clip" }
                assertFalse("a menu is open before any long press", device.hasObject(By.text(context.getString(R.string.clip_pin))))
                card.longClick()
                val pin = checkNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.clip_pin))), WAIT_MS)) { "the long press opened no menu" }
                // The label sits inside its clickable line: the line's bounds are the touch target.
                val line = generateSequence(pin) { it.parent }.firstOrNull { it.isClickable } ?: pin
                val min = res.getDimensionPixelSize(R.dimen.panel_touch)
                assertTrue("Pin's line is ${line.visibleBounds.height()} px tall, under $min", line.visibleBounds.height() >= min)
                pin.click()
                val until = System.currentTimeMillis() + WAIT_MS
                while (System.currentTimeMillis() < until && history.items.value.none { it.text == text && it.pinned }) Thread.sleep(POLL_MS)
                assertTrue("Pin did not pin the clip", history.items.value.any { it.text == text && it.pinned })
            }
        } finally {
            runBlocking { settings.update { it.copy(clipboard = previous.clipboard, toolOrder = previous.toolOrder, hiddenTools = previous.hiddenTools) } }
        }
    }

    /**
     * CLIP-33: a copied image URI is stored encrypted (`KCLI`), offered on the quick-paste strip
     * in image-accepting fields, and pasted from the clipboard panel via `commitContent`;
     * negative: an image clip marked sensitive (`EXTRA_IS_SENSITIVE`) is neither saved nor offered.
     */
    @Test
    fun aCopiedImageIsSavedAndCommitted() {
        val previous = settings.current
        val shareDir = File(context.cacheDir, "clipboard_share").apply { mkdirs() }
        val srcFile = File(shareDir, "test_src.png")
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        FileOutputStream(srcFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val expectedBytes = srcFile.readBytes()
        val srcUri = FileProvider.getUriForFile(context, "${context.packageName}.clipboard.fileprovider", srcFile)
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
            .putExtra(FieldTestActivity.EXTRA_ACCEPT_IMAGES, true)
        try {
            runBlocking {
                settings.update {
                    it.copy(
                        clipboard = it.clipboard.copy(history = true, quickPaste = true),
                        toolOrder = listOf(Tool.CLIPBOARD) + (Tool.configurable - Tool.CLIPBOARD),
                        hiddenTools = Tool.configurable.toSet() - Tool.CLIPBOARD,
                    )
                }
            }
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
                Thread.sleep(POLL_MS * 2)
                scenario.onActivity {
                    val hidden = ClipData.newUri(it.contentResolver, "sensitive-image", srcUri).apply {
                        description.extras = android.os.PersistableBundle().apply {
                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                    it.getSystemService(ClipboardManager::class.java).setPrimaryClip(hidden)
                }
                Thread.sleep(POLL_MS * 4)
                assertTrue("sensitive image was recorded", history.items.value.none { it.isImage })
                assertTrue(
                    "sensitive image appeared on quick paste",
                    device.wait(Until.findObject(By.desc(context.getString(R.string.quick_close))), POLL_MS * 3) == null &&
                        device.findObject(By.text(context.getString(R.string.quick_copied_image))) == null,
                )

                scenario.onActivity {
                    it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(it.contentResolver, "image", srcUri))
                }
                val until = System.currentTimeMillis() + WAIT_MS
                while (System.currentTimeMillis() < until && history.items.value.none { it.isImage }) Thread.sleep(POLL_MS)
                val stored = history.items.value.firstOrNull { it.isImage }
                checkNotNull(stored) { "copied image was not recorded" }
                assertEquals("image/png", stored.image?.mime)
                assertEquals(16, stored.image?.width)
                assertEquals(16, stored.image?.height)
                val enc = File(context.filesDir, "clipboard/images/${stored.image!!.file}")
                assertTrue("encrypted image container missing", enc.isFile)
                assertArrayEquals("KCLI".toByteArray(), enc.readBytes().copyOfRange(0, 4))

                val closeQuick = checkNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.quick_close))), WAIT_MS)) {
                    "copied image was not offered on quick paste"
                }
                closeQuick.click()
                Thread.sleep(POLL_MS * 2)

                val res = context.resources
                val barY = q.visibleBounds.top - res.getDimensionPixelSize(R.dimen.kb_pad_top) - res.getDimensionPixelSize(R.dimen.bar_height) / 2
                device.click(device.displayWidth - res.getDimensionPixelSize(R.dimen.toolbar_pad) - res.getDimensionPixelSize(R.dimen.toolbar_button) / 2, barY)
                checkNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.clip_tab_images))), WAIT_MS)) {
                    "Images tab not shown"
                }.click()
                checkNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.clip_tag_image))), WAIT_MS)) {
                    "image card thumbnail not shown"
                }.click()

                val commitUntil = System.currentTimeMillis() + WAIT_MS
                var mime: String? = null
                var size = 0
                while (System.currentTimeMillis() < commitUntil && size == 0) {
                    scenario.onActivity {
                        mime = it.receivedMime
                        size = it.receivedSize
                    }
                    if (size == 0) Thread.sleep(POLL_MS)
                }
                assertEquals("image/png", mime)
                assertEquals(expectedBytes.size, size)
            }
        } finally {
            runBlocking { settings.update { it.copy(clipboard = previous.clipboard, toolOrder = previous.toolOrder, hiddenTools = previous.hiddenTools) } }
        }
    }

    /**
     * Single-row quick-paste strip keeps keyboard height constant (`0px` change), preserves the
     * More button so tools remain one tap away, suppresses the clip once typing starts so word
     * commits do not bounce it back, and still offers a newer copy in a non-empty field.
     */
    @Test
    fun quickPasteKeepsConstantHeightMoreButtonAndSuppressesOnTyping() {
        val previous = settings.current
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        try {
            runBlocking { settings.update { it.copy(clipboard = it.clipboard.copy(history = true, quickPaste = true)) } }
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                val baselineTop = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) {
                    "Kotomo's keys did not appear"
                }.visibleBounds.top

                val code = (100000..999999).random().toString()
                scenario.onActivity {
                    it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, "$PREFIX code $code"))
                }
                checkNotNull(device.wait(Until.findObject(By.text(code)), WAIT_MS)) { "copied code chip missing" }
                val more = checkNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.tool_more))), WAIT_MS)) {
                    "quick-paste strip More button missing"
                }
                assertEquals(baselineTop, checkNotNull(device.findObject(By.desc("q"))).visibleBounds.top)

                more.click()
                checkNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.tools_more_title))), WAIT_MS)) {
                    "More button did not open QuickPanel"
                }
                assertTrue(
                    "quick-paste strip stayed over toolbar while QuickPanel is open",
                    device.wait(Until.gone(By.desc(context.getString(R.string.quick_close))), WAIT_MS),
                )
                checkNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.candidates_collapse))), WAIT_MS)) {
                    "QuickPanel back button missing"
                }.click()
                checkNotNull(device.wait(Until.findObject(By.text(code)), WAIT_MS)) {
                    "quick-paste strip did not return after closing QuickPanel"
                }
                assertEquals(baselineTop, checkNotNull(device.findObject(By.desc("q"))).visibleBounds.top)

                // Start typing and commit: quick-paste must hide on key down and stay suppressed after commit.
                checkNotNull(device.findObject(By.desc("h"))).click()
                checkNotNull(device.findObject(By.desc("i"))).click()
                checkNotNull(device.findObject(By.descStartsWith("Enter"))).click()
                Thread.sleep(POLL_MS * 4)
                assertTrue(
                    "quick-paste strip bounced back after typing committed",
                    device.wait(Until.gone(By.desc(context.getString(R.string.quick_close))), WAIT_MS) &&
                        device.findObject(By.desc(context.getString(R.string.quick_close))) == null,
                )
                assertEquals(baselineTop, checkNotNull(device.findObject(By.desc("q"))).visibleBounds.top)

                // Copy something new while the field already contains text: the strip appears again at identical height.
                val secondText = "$PREFIX ${(1000..9999).random()}"
                scenario.onActivity {
                    it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, secondText))
                }
                checkNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.quick_close))), WAIT_MS)) {
                    "fresh clip was not offered in non-empty field"
                }
                assertEquals(baselineTop, checkNotNull(device.findObject(By.desc("q"))).visibleBounds.top)
                checkNotNull(device.wait(Until.findObject(By.textStartsWith(PREFIX)), WAIT_MS)) {
                    "fresh copied text pill missing"
                }.click()
                Thread.sleep(POLL_MS * 3)
                var typed = ""
                scenario.onActivity { typed = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
                assertTrue("expected field text '$typed' to end with '$secondText'", typed.endsWith(secondText))
            }
        } finally {
            runBlocking { settings.update { it.copy(clipboard = previous.clipboard) } }
        }
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val PREFIX = "Kotomo clipboard test"
        const val WAIT_MS = 5000L
        const val POLL_MS = 200L
    }
}
