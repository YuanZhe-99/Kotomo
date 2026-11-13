package com.yuanzhe.kotomo.ime

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.InputType
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.ColorRole
import com.yuanzhe.kotomo.settings.KeyboardTheme
import com.yuanzhe.kotomo.settings.SettingsStore
import com.yuanzhe.kotomo.settings.Theme
import com.yuanzhe.kotomo.settings.ThemeLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Checks on the screen that the keyboard draws the theme editor's theme (T14-theme, PLAN §11.4):
 * a key color the person set, a mode's own theme, and the background picture behind the keys.
 *
 * Notes: reads pixels of a screenshot at a letter key and between keys; emulator results cover
 * drawing only. Each case puts the theme and the library back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ThemeKeyboardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val store = SettingsStore.get(context)
    private val library = ThemeLibrary.get(context)

    /** Makes Kotomo the keyboard. */
    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
    }

    /** Positive: a key color the person set is the color the keys are drawn in. */
    @Test
    fun keysUseTheColorThePersonSet() = withTheme({ it.copy(base = KeyboardTheme.LIGHT, colors = mapOf(ColorRole.KEY to RED)) }) {
        inEnglish { assertNear(RED, keyPixel()) }
    }

    /**
     * Positive and negative: a mode with its own theme draws in it; another mode keeps the current
     * theme.
     */
    @Test
    fun aModeUsesItsOwnTheme() = withTheme({ it.copy(base = KeyboardTheme.LIGHT, colors = mapOf(ColorRole.KEY to RED)) }) {
        val before = library.state.value
        runBlocking { library.update { it.copy(modes = mapOf(Mode.ENGLISH to "builtin.kyoto")) } }
        try {
            val kyotoKey = 0xFFFFFBF4.toInt()
            inEnglish { assertNear(kyotoKey, keyPixel()) }
            inMode(Mode.CHINESE) { assertNear(RED, keyPixel()) }
        } finally {
            runBlocking { library.update { before } }
        }
    }

    /** Positive: the background picture shows between the keys at its opacity. */
    @Test
    fun theBackgroundPictureShowsBehindTheKeys() {
        val png = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(BLUE) }.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val name = runBlocking { library.addImage(png) }
        withTheme({ it.copy(base = KeyboardTheme.LIGHT, colors = mapOf(ColorRole.BACKGROUND to Color.WHITE), image = name, imageOpacity = 100) }) {
            inEnglish {
                val q = checkNotNull(device.findObject(By.desc("q"))).visibleBounds
                val gap = screenshot().getPixel(q.right + 1, q.centerY())
                assertNear(BLUE, gap)
            }
        }
        File(library.images, name).delete()
    }

    /** Opens a text field in English mode and runs [block] while the keys show. */
    private fun inEnglish(block: () -> Unit) = inMode(Mode.ENGLISH, block)

    private fun inMode(mode: Mode, block: () -> Unit) {
        val memory = KeyboardMemory.get(context)
        memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = mode) } }
        try {
            val intent = Intent(context, FieldTestActivity::class.java)
                .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
            ActivityScenario.launch<FieldTestActivity>(intent).use {
                checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
                Thread.sleep(SETTLE_MS)
                block()
            }
        } finally {
            memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
        }
    }

    /** The color at the middle of the q key, a little below its label. */
    private fun keyPixel(): Int {
        val q = checkNotNull(device.findObject(By.desc("q"))).visibleBounds
        return screenshot().getPixel(q.left + q.width() / 5, q.bottom - q.height() / 5)
    }

    private fun screenshot(): Bitmap {
        val file = File(context.cacheDir, "theme-${System.nanoTime()}.png")
        assertTrue(device.takeScreenshot(file))
        return BitmapFactory.decodeFile(file.path).also { file.delete() }
    }

    /** Runs [block] with the current theme changed by [change], and puts it back. */
    private fun withTheme(change: (Theme) -> Theme, block: () -> Unit) {
        val before = store.themeFlow.value ?: Theme(store.current.theme, store.current.keyRadiusDp)
        runBlocking { store.updateTheme(change) }
        Thread.sleep(SETTLE_MS)
        try {
            block()
        } finally {
            runBlocking { store.updateTheme { before } }
        }
    }

    private fun assertNear(expected: Int, actual: Int) {
        val d = abs(Color.red(expected) - Color.red(actual)) + abs(Color.green(expected) - Color.green(actual)) + abs(Color.blue(expected) - Color.blue(actual))
        assertEquals("expected %08X, drawn %08X".format(expected, actual), true, d <= TOLERANCE)
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val RED = 0xFFD02020.toInt()
        const val BLUE = 0xFF2040D0.toInt()
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 1_500L
        const val TOLERANCE = 24
    }
}
