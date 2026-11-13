package com.yuanzhe.kotomo.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.settings.ColorRole
import com.yuanzhe.kotomo.settings.KeyboardTheme
import com.yuanzhe.kotomo.settings.Theme
import java.io.File

/**
 * Holds the colors every part of the keyboard draws with (keys, candidate bar, popups, Compose
 * panels), for the theme in use: its base (following the system, always light, always dark, or
 * Material You, the system's dynamic colors on Android 12 and later), the person's own colors over
 * it (PLAN §11.4), and its background picture.
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

    /** The color resource each theme color replaces. */
    val roles: Map<ColorRole, Int> = mapOf(
        ColorRole.BACKGROUND to R.color.kb_background,
        ColorRole.KEY to R.color.kb_key,
        ColorRole.FUNCTION_KEY to R.color.kb_key_function,
        ColorRole.PRESSED_KEY to R.color.kb_key_pressed,
        ColorRole.TEXT to R.color.kb_ink,
        ColorRole.SECONDARY_TEXT to R.color.kb_ink_secondary,
        ColorRole.ACCENT to R.color.kb_accent,
        ColorRole.ON_ACCENT to R.color.kb_on_accent,
        ColorRole.CANDIDATE to R.color.kb_accent_container,
        ColorRole.CANDIDATE_TEXT to R.color.kb_on_accent_container,
    )

    private var palette: Map<Int, Int> = emptyMap()
    private var picture: Pair<File, Bitmap>? = null

    /** The background picture of the theme in use and its opacity (0–255), or null. */
    var background: Pair<Bitmap, Int>? = null
        private set

    /**
     * Computes the palette and loads the picture for [theme].
     * Inputs: [image] is the theme's picture file, or null.
     * Side effects: decodes the picture once per file (scaled to the screen width at most).
     */
    fun apply(context: Context, theme: Theme, image: File?) {
        palette = palette(context, theme)
        val bitmap = image?.let { f ->
            picture?.takeIf { it.first == f }?.second ?: decode(f, context.resources.displayMetrics.widthPixels)?.also { picture = f to it }
        }
        background = bitmap?.let { it to theme.imageOpacity * 255 / 100 }
    }

    /**
     * Returns the colors of [theme]: its base's, with the theme's own colors over them.
     * Notes: Light and Dark read the resources through a configuration with that night mode;
     * Material You maps the system's tonal palettes onto the same roles and falls back to the
     * system theme before Android 12. With the press highlight off, pressed keys keep the key color.
     */
    fun palette(context: Context, theme: Theme): Map<Int, Int> {
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = when (theme.base) {
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
        val withBase = if (theme.base == KeyboardTheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) base + dynamic(context, dark) else base
        val own = withBase + theme.colors.mapKeys { (role, _) -> roles.getValue(role) }
        return if (theme.pressHighlight) own else own + (R.color.kb_key_pressed to own.getValue(R.color.kb_key))
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

    /**
     * Decodes picture [file] no wider than about [maxWidth] pixels.
     * Returns: the bitmap, or null when it is not an image. Notes: the file is the app's own.
     */
    fun decode(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Returns color [id] in the current theme (views). */
    fun color(context: Context, id: Int): Int = palette[id] ?: context.getColor(id)
}

/**
 * Draws the keyboard's background: the theme's background color, then its picture scaled to
 * cover the area (centred, cropped) at the theme's opacity.
 */
class ThemeBackground(private val color: Int, private val bitmap: Bitmap, private val alpha: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = this@ThemeBackground.alpha }
    private val src = Rect()

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(color)
        if (b.isEmpty) return
        val scale = maxOf(b.width().toFloat() / bitmap.width, b.height().toFloat() / bitmap.height)
        val w = (b.width() / scale).toInt()
        val h = (b.height() / scale).toInt()
        val x = (bitmap.width - w) / 2
        val y = (bitmap.height - h) / 2
        src.set(x, y, x + w, y + h)
        canvas.drawBitmap(bitmap, src, b, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** Returns color [id] in the current keyboard theme (Compose panels). */
@Composable
fun kbColor(id: Int): Color = Color(KeyboardColors.color(LocalContext.current, id))
