package com.yuanzhe.kotomo.ime

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.settings.KeyboardTheme

/**
 * Holds the colors every part of the keyboard draws with (keys, candidate bar, popups, Compose
 * panels), for the theme the person chose: following the system, always light, always dark, or
 * Material You (the system's dynamic colors, Android 12 and later).
 *
 * Notes: main thread. The keyboard calls [apply] when the theme changes and rebuilds its views;
 * colors not in the palette (or before [apply]) fall back to the resources, which follow the
 * system's light or dark mode.
 */
object KeyboardColors {
    /** The theme-able color resources; light and dark values live in values/ and values-night/. */
    private val ids = listOf(
        R.color.kb_background, R.color.kb_key, R.color.kb_key_function, R.color.kb_key_pressed,
        R.color.kb_ink, R.color.kb_ink_secondary, R.color.kb_accent, R.color.kb_on_accent,
        R.color.kb_accent_container, R.color.kb_on_accent_container, R.color.kb_key_shadow,
        R.color.clip_tag_phrase, R.color.clip_on_tag_phrase, R.color.clip_tag_neutral, R.color.clip_on_tag_neutral,
    )

    private var palette: Map<Int, Int> = emptyMap()

    /**
     * Computes the palette for [theme].
     * Notes: Light and Dark read the resources through a configuration with that night mode;
     * Material You maps the system's tonal palettes onto the same roles and falls back to the
     * system theme before Android 12.
     */
    fun apply(context: Context, theme: KeyboardTheme) {
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = when (theme) {
            KeyboardTheme.LIGHT -> false
            KeyboardTheme.DARK -> true
            KeyboardTheme.SYSTEM, KeyboardTheme.DYNAMIC -> systemDark
        }
        val night = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            },
        )
        val base = ids.associateWith { night.getColor(it) }
        palette = if (theme == KeyboardTheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base + dynamic(context, dark)
        } else {
            base
        }
    }

    /** Maps the system's dynamic tonal palettes onto the keyboard's color roles. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun dynamic(context: Context, dark: Boolean): Map<Int, Int> {
        fun c(id: Int) = context.getColor(id)
        return if (dark) {
            mapOf(
                R.color.kb_background to c(android.R.color.system_neutral1_900),
                R.color.kb_key to c(android.R.color.system_neutral1_800),
                R.color.kb_key_function to c(android.R.color.system_neutral2_700),
                R.color.kb_key_pressed to c(android.R.color.system_neutral2_600),
                R.color.kb_ink to c(android.R.color.system_neutral1_50),
                R.color.kb_ink_secondary to c(android.R.color.system_neutral2_200),
                R.color.kb_accent to c(android.R.color.system_accent1_200),
                R.color.kb_on_accent to c(android.R.color.system_accent1_800),
                R.color.kb_accent_container to c(android.R.color.system_accent1_700),
                R.color.kb_on_accent_container to c(android.R.color.system_accent1_100),
            )
        } else {
            mapOf(
                R.color.kb_background to c(android.R.color.system_neutral1_50),
                R.color.kb_key to c(android.R.color.system_neutral1_10),
                R.color.kb_key_function to c(android.R.color.system_neutral2_100),
                R.color.kb_key_pressed to c(android.R.color.system_neutral2_200),
                R.color.kb_ink to c(android.R.color.system_neutral1_900),
                R.color.kb_ink_secondary to c(android.R.color.system_neutral2_600),
                R.color.kb_accent to c(android.R.color.system_accent1_600),
                R.color.kb_on_accent to c(android.R.color.system_accent1_0),
                R.color.kb_accent_container to c(android.R.color.system_accent1_100),
                R.color.kb_on_accent_container to c(android.R.color.system_accent1_900),
            )
        }
    }

    /** Returns color [id] in the current theme (views). */
    fun color(context: Context, id: Int): Int = palette[id] ?: context.getColor(id)
}

/** Returns color [id] in the current keyboard theme (Compose panels). */
@Composable
fun kbColor(id: Int): Color = Color(KeyboardColors.color(LocalContext.current, id))
