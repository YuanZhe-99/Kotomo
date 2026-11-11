package com.yuanzhe.kotomo.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.InputType
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.ime.clipboard.ClipboardHistory
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
    }

    @After
    fun cleanUp() = runBlocking {
        settings.update { it.copy(clipboard = it.clipboard.copy(excludedApps = emptySet())) }
        history.items.value.filter { it.text.startsWith(PREFIX) }.forEach { history.delete(it.id) }
        history.flush()
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
            assertFalse("copied while typing in an excluded app: not kept", copyAndWait(scenario, "$PREFIX excluded ${System.nanoTime()}"))
            runBlocking { settings.update { it.copy(clipboard = it.clipboard.copy(excludedApps = emptySet())) } }
            assertTrue("copied in an app that is not excluded: kept", copyAndWait(scenario, "$PREFIX kept ${System.nanoTime()}"))
        }
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val PREFIX = "Kotomo clipboard test"
        const val WAIT_MS = 5000L
        const val POLL_MS = 200L
    }
}
