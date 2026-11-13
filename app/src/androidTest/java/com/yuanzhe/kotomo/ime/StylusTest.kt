package com.yuanzhe.kotomo.ime

import android.content.Intent
import android.text.InputType
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T22 (PLAN §12): stylus handwriting in any field (Android 14 and later). A stylus stroke over a
 * text field starts a handwriting session in Kotomo, which reads it and commits the text; a
 * password field gets nothing.
 *
 * Notes: the stroke is injected with `input stylus swipe`, the emulator's stylus; the mainland
 * Chinese model is downloaded first (ML Kit, real network), and the case is skipped when that
 * cannot run here. The system's "write in text fields" setting is turned on for the run.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class StylusTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext

    @Before
    fun prepare() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        device.executeShellCommand("settings put secure stylus_handwriting_enabled 1")
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.CHINESE) } }
        val ready = runCatching {
            runBlocking {
                val r = InkRecognizer()
                try {
                    r.download(InkLang.CHINESE_CN)
                    r.downloaded(InkLang.CHINESE_CN)
                } finally {
                    r.close()
                }
            }
        }.getOrDefault(false)
        assumeTrue("ML Kit's handwriting model could not be downloaded here", ready)
    }

    @After
    fun unpin() {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
    }

    /** Positive: a horizontal stylus stroke over the field becomes 一. */
    @Test
    fun strokeOverAFieldIsWritten() {
        assertEquals("一", written(InputType.TYPE_CLASS_TEXT))
    }

    /** Negative: a password field does not take stylus writing. */
    @Test
    fun passwordFieldTakesNoWriting() {
        assertEquals("", written(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }

    /** Opens a field of [inputType], draws one stylus stroke across it and returns its text. */
    private fun written(inputType: Int): String {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, inputType)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            device.wait(Until.hasObject(By.desc("q")), WAIT_MS)
            Thread.sleep(SETTLE_MS)
            val at = IntArray(2)
            var w = 0
            var h = 0
            scenario.onActivity { a ->
                val field = a.findViewById<EditText>(FieldTestActivity.FIELD_ID)
                field.getLocationOnScreen(at)
                w = field.width
                h = field.height
            }
            val y = at[1] + minOf(h / 2, MAX_Y_PX)
            device.executeShellCommand("input stylus swipe ${at[0] + w / 5} $y ${at[0] + w * 4 / 5} $y $STROKE_MS")
            var text = ""
            repeat(READS) {
                Thread.sleep(SETTLE_MS)
                scenario.onActivity { text = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
                if (text.isNotEmpty()) return text
            }
            return text
        }
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val WAIT_MS = 8000L
        const val SETTLE_MS = 1500L
        const val STROKE_MS = 600
        const val READS = 4
        /** Writes near the top of a tall field, where its text is. */
        const val MAX_Y_PX = 120
    }
}
