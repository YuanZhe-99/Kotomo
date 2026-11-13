package com.yuanzhe.kotomo.settings

import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.ime.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the theme editor's formats (PLAN §11.4, T14-theme; the behavior contract's versioned
 * formats): colors, picture and press highlight round trip, untrusted values dropped, version 1
 * files still read, the library with per-mode themes, and theme files shared between people.
 */
class ThemeLibraryTest {
    private val theme = Theme(
        base = KeyboardTheme.DARK, keyRadiusDp = 8, name = "Mine",
        colors = mapOf(ColorRole.KEY to 0xFF112233.toInt(), ColorRole.ACCENT to 0x80FF0000.toInt()),
        image = "0123456789abcdef01234567.png", imageOpacity = 40, pressHighlight = false,
    )

    @Test
    fun themeRoundTripsWithColorsPictureAndPress() { // T14-theme-format
        val read = ThemeFormat.read(ThemeFormat.write(theme, 5L))!!
        assertEquals(theme, read.copy(extra = emptyMap()))
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse(ThemeFormat.write(theme, 5L)) as Map<String, Any?>
        assertEquals(2L, o["format_version"])
        assertEquals(mapOf("key" to "#112233", "accent" to "#80FF0000"), o["colors"])
    }

    @Test
    fun untrustedValuesAreDropped() { // T14-theme-format (negative)
        val t = ThemeFormat.read(
            """{"format_version":2,"base":"light","colors":{"key":"red","text":"#12345","nope":"#FFFFFF","accent":"#00ff00"},
               "background_image":"../../shared_prefs/x.png","background_opacity":500,"press_highlight":"yes"}""",
        )!!
        assertEquals(mapOf(ColorRole.ACCENT to 0xFF00FF00.toInt()), t.colors)
        assertNull(t.image)
        assertEquals(Theme.DEFAULT_OPACITY, t.imageOpacity)
        assertTrue(t.pressHighlight)
    }

    @Test
    fun versionOneFilesReadUnchanged() { // T14-theme-format
        val t = ThemeFormat.read("""{"format_version":1,"updated_utc_ms":1,"base":"dark","key_radius_dp":6}""")!!
        assertEquals(Theme(base = KeyboardTheme.DARK, keyRadiusDp = 6), t.copy(extra = emptyMap()))
    }

    @Test
    fun libraryKeepsSavedThemesModesAndUnknownMembers() { // T14-theme-library
        val id = "saved.12345678-1234-1234-1234-123456789abc"
        val lib = ThemeLibraryData(listOf(SavedTheme(id, theme)), mapOf(Mode.JAPANESE to id, Mode.CHINESE to "builtin.kyoto"), mapOf("future" to "kept"))
        val back = ThemeLibraryFormat.read(ThemeLibraryFormat.write(lib, 9L))!!
        assertEquals(lib.modes, back.modes)
        assertEquals(theme, back.saved.single().theme.copy(extra = emptyMap()))
        assertEquals("kept", back.extra["future"])
        assertEquals(theme, back.forMode(Mode.JAPANESE, Theme()).copy(extra = emptyMap()))
        assertEquals(KeyboardTheme.LIGHT, back.forMode(Mode.CHINESE, Theme()).base)
        assertEquals(Theme(), back.forMode(Mode.ENGLISH, Theme()))
        // A mode whose theme was deleted falls back to the current theme.
        assertEquals(Theme(), back.copy(saved = emptyList()).forMode(Mode.JAPANESE, Theme()))
    }

    @Test
    fun libraryDropsBadIdsAndModes() { // T14-theme-library (negative)
        val lib = ThemeLibraryFormat.read("""{"format_version":1,"themes":[{"id":"../x","base":"dark"},{"base":"light"}],"modes":{"klingon":"builtin.dark"}}""")!!
        assertTrue(lib.saved.isEmpty())
        assertTrue(lib.modes.isEmpty())
    }

    @Test
    fun sharedThemeCarriesItsPictureButNoFileName() { // T14-theme-share
        val png = byteArrayOf(1, 2, 3, 4)
        val (t, bytes) = ThemeLibraryFormat.import(ThemeLibraryFormat.export(theme, png, 1L))!!
        assertNull(t.image)
        assertEquals(png.toList(), bytes!!.toList())
        assertEquals(theme.colors, t.colors)
        assertTrue(ThemeLibraryFormat.IMAGE_DATA !in t.extra)
        assertNull(ThemeLibraryFormat.import("""{"hello":"world"}"""))
        assertNull(ThemeLibraryFormat.import("not json"))
    }

    @Test
    fun colorsParseAndFormat() { // T14-theme-format
        assertEquals(0xFFABCDEF.toInt(), ThemeFormat.parseColor("#abcdef"))
        assertEquals(0x11223344, ThemeFormat.parseColor("#11223344"))
        assertNull(ThemeFormat.parseColor("#GG0000"))
        assertEquals("#ABCDEF", ThemeFormat.formatColor(0xFFABCDEF.toInt()))
        assertEquals("#11223344", ThemeFormat.formatColor(0x11223344))
    }
}
