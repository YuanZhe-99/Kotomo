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
import com.yuanzhe.kotomo.fieldtest.FakeAutofillService
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T14-autofill (PLAN §10.10): with a (fake) autofill service, Kotomo asks for inline suggestions
 * and shows them over the idle toolbar, where a tap fills the field; with the switch off it asks
 * for none and the bar's start is the More tool.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class InlineAutofillTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val store = SettingsStore.get(context)
    private var previous = ""

    @Before
    fun useFakeService() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        previous = device.executeShellCommand("settings get secure autofill_service").trim()
        device.executeShellCommand("settings put secure autofill_service ${context.packageName}/${FakeAutofillService::class.java.name}")
    }

    @After
    fun restore() {
        if (previous.isEmpty() || previous == "null") device.executeShellCommand("settings delete secure autofill_service")
        else device.executeShellCommand("settings put secure autofill_service $previous")
        runBlocking { store.update { it.copy(inlineAutofill = true) } }
    }

    /** Positive: the suggestion appears at the bar's start and fills the field. */
    @Test
    fun suggestionFillsTheField() {
        assertEquals(FakeAutofillService.VALUE, tapBarStart())
    }

    /** Negative: with the switch off no suggestion is asked for; the tap opens More and fills nothing. */
    @Test
    fun switchOffShowsNoSuggestion() {
        runBlocking { store.update { it.copy(inlineAutofill = false) } }
        Thread.sleep(SETTLE_MS)
        assertEquals("", tapBarStart())
    }

    /** Opens the test field, taps the start of the candidate bar and returns the field's text. */
    private fun tapBarStart(): String {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
            // The autofill service answers after the keys appear.
            Thread.sleep(SETTLE_MS * 4)
            val res = context.resources
            val barY = q.visibleBounds.top - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.kb_pad_top) -
                res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_height) / 2
            device.click(res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_pad_start) + res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_pill_height), barY)
            Thread.sleep(SETTLE_MS)
            var text = ""
            scenario.onActivity { text = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
            return text
        }
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val WAIT_MS = 5000L
        const val SETTLE_MS = 1000L
    }
}
