package com.yuanzhe.kotomo.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ime.KeyboardColors
import com.yuanzhe.kotomo.ime.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** What the theme preview shows (PLAN §11.4: the keyboard, Chinese and Japanese candidates, English, emoji, landscape). */
private enum class PreviewTab(val label: Int) {
    CHINESE(R.string.theme_preview_chinese),
    JAPANESE(R.string.theme_preview_japanese),
    ENGLISH(R.string.theme_preview_english),
    EMOJI(R.string.theme_preview_emoji),
    LANDSCAPE(R.string.theme_preview_landscape),
}

/**
 * Draws the Themes page, the theme editor (PLAN §11.4): a live preview, the built-in themes by
 * group, the base colors, the person's own colors, a background picture with its opacity, the key
 * corners and pressed highlight, the saved themes, import and export, and the theme each input
 * mode uses.
 * Side effects: every change writes the current theme file (or the library) at once and the
 * keyboard repaints; pictures and theme files are read only when the person picks them.
 * Notes: an imported file is untrusted: it is read with a size cap, its colors and values
 * checked by [ThemeFormat], its picture decoded and re-encoded, and it is previewed before use.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemesPage(settings: Settings, store: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { ThemeLibrary.get(context) }
    val stored by store.themeFlow.collectAsState()
    val lib by library.state.collectAsState()
    val theme = stored ?: Theme(base = settings.theme, keyRadiusDp = settings.keyRadiusDp)
    var tab by remember { mutableStateOf(PreviewTab.CHINESE) }
    var editing by remember { mutableStateOf<ColorRole?>(null) }
    var naming by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<Pair<Theme, ByteArray?>?>(null) }
    val gap = dimensionResource(R.dimen.settings_card_gap)

    fun change(transform: (Theme) -> Theme) = scope.launch {
        store.updateTheme(transform)
        library.dropUnused(store.themeFlow.value ?: Theme())
    }
    fun toast(id: Int) = Toast.makeText(context, id, Toast.LENGTH_SHORT).show()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val png = withContext(Dispatchers.IO) { readPicture(context, uri) }
            if (png == null) return@launch toast(R.string.theme_image_bad)
            val name = library.addImage(png)
            change { it.copy(image = name) }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val image = library.image(theme.image)?.readBytes()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(ThemeLibraryFormat.export(theme, image, System.currentTimeMillis()).toByteArray()) } != null
                }.getOrDefault(false)
            }
            toast(if (ok) R.string.theme_exported else R.string.theme_export_failed)
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) { readTheme(context, uri) }
            if (read == null) toast(R.string.theme_import_bad) else imported = read
        }
    }

    Section(null) {
        Inset {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                PreviewTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(stringResource(t.label)) }) }
            }
            ThemePreview(theme, library.image(theme.image), tab)
            Note(stringResource(R.string.theme_preview_note))
        }
    }

    Section(stringResource(R.string.theme_builtin)) {
        Inset {
            PresetGroup.entries.forEach { group ->
                Label(stringResource(if (group == PresetGroup.BASIC) R.string.theme_group_basic else R.string.theme_group_city))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                    ThemePresets.all.filter { it.group == group }.forEach { p ->
                        if (p.theme.base == KeyboardTheme.DYNAMIC && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return@forEach
                        FilterChip(
                            selected = theme.looksLike(p.theme.copy(keyRadiusDp = theme.keyRadiusDp, image = theme.image, imageOpacity = theme.imageOpacity, pressHighlight = theme.pressHighlight)),
                            onClick = { change { it.copy(base = p.theme.base, colors = p.theme.colors, name = p.theme.name) } },
                            label = { Text(presetName(p)) },
                            leadingIcon = { Swatch(KeyboardColors.palette(context, p.theme).getValue(R.color.kb_accent), Modifier.size(16.dp)) },
                        )
                    }
                }
            }
        }
    }

    Section(stringResource(R.string.theme_title)) {
        Inset {
            val options = buildList {
                add(KeyboardTheme.SYSTEM to stringResource(R.string.theme_system))
                add(KeyboardTheme.LIGHT to stringResource(R.string.theme_light))
                add(KeyboardTheme.DARK to stringResource(R.string.theme_dark))
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) add(KeyboardTheme.DYNAMIC to stringResource(R.string.theme_dynamic))
            }
            Choice(options.map { it.second }, options.indexOfFirst { it.first == theme.base }.coerceAtLeast(0)) { i ->
                change { it.copy(base = options[i].first) }
            }
            Note(stringResource(R.string.theme_colors_note))
            val palette = KeyboardColors.palette(context, theme)
            ColorRole.entries.forEach { role ->
                Row(Modifier.fillMaxWidth().clickable { editing = role }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(role.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (role in theme.colors) Text(stringResource(R.string.theme_color_own), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Swatch(palette.getValue(KeyboardColors.roles.getValue(role)), Modifier.padding(start = 8.dp).size(28.dp))
                }
            }
            if (theme.colors.isNotEmpty()) OutlinedButton(onClick = { change { it.copy(colors = emptyMap()) } }) { Text(stringResource(R.string.theme_colors_reset)) }
        }
    }

    Section(stringResource(R.string.theme_image)) {
        Inset {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                OutlinedButton(onClick = { pickImage.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.theme_image_pick)) }
                if (theme.image != null) TextButton(onClick = { change { it.copy(image = null) } }) { Text(stringResource(R.string.theme_image_remove)) }
            }
            if (theme.image != null) {
                SliderRow(
                    stringResource(R.string.theme_image_opacity), theme.imageOpacity, Theme.OPACITY_RANGE, OPACITY_STEP,
                    label = { stringResource(R.string.value_percent, it) },
                ) { v -> change { it.copy(imageOpacity = v) } }
            }
            SliderRow(
                stringResource(R.string.theme_radius), theme.keyRadiusDp, Settings.RADIUS_RANGE, RADIUS_STEP,
                label = { stringResource(R.string.value_dp, it) },
            ) { v -> change { it.copy(keyRadiusDp = v) } }
        }
        SwitchRow(R.string.theme_press, R.string.theme_press_desc, theme.pressHighlight) { on -> change { it.copy(pressHighlight = on) } }
    }

    Section(stringResource(R.string.theme_saved)) {
        Inset {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                OutlinedButton(onClick = { naming = true }) { Text(stringResource(R.string.theme_save)) }
                OutlinedButton(onClick = { export.launch(EXPORT_NAME) }) { Text(stringResource(R.string.theme_export)) }
                OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.theme_import)) }
            }
            if (lib.saved.isEmpty()) Note(stringResource(R.string.theme_saved_none))
            lib.saved.forEach { saved ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(KeyboardColors.palette(context, saved.theme).getValue(R.color.kb_accent), Modifier.size(20.dp))
                    Text(saved.theme.name.ifEmpty { stringResource(R.string.theme_unnamed) }, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { change { saved.theme } }) { Text(stringResource(R.string.theme_apply)) }
                    TextButton(onClick = { scope.launch { library.delete(saved.id); library.dropUnused(theme) } }) { Text(stringResource(R.string.theme_delete)) }
                }
            }
        }
    }

    // The emoji style is its own setting, apart from the colors (PLAN §11.5).
    EmojiStyleSection()

    Section(stringResource(R.string.theme_per_mode)) {
        Inset { Note(stringResource(R.string.theme_per_mode_note)) }
        listOf(Mode.MIXED, Mode.CHINESE, Mode.JAPANESE, Mode.ENGLISH).forEach { mode ->
            ModeTheme(mode, lib) { id -> scope.launch { library.update { l -> l.copy(modes = if (id == null) l.modes - mode else l.modes + (mode to id)) } } }
        }
    }

    editing?.let { role ->
        ColorDialog(
            role, theme.colors[role] ?: KeyboardColors.palette(context, theme).getValue(KeyboardColors.roles.getValue(role)),
            onPick = { color -> change { it.copy(colors = if (color == null) it.colors - role else it.colors + (role to color)) } },
            onDismiss = { editing = null },
        )
    }
    if (naming) {
        NameDialog(theme.name, onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch {
                library.save(theme.copy(name = name))
                toast(R.string.theme_saved_done)
            }
        }
    }
    imported?.let { (t, bytes) ->
        val image = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        AlertDialog(
            onDismissRequest = { imported = null },
            title = { Text(t.name.ifEmpty { stringResource(R.string.theme_unnamed) }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    Text(stringResource(R.string.theme_import_preview))
                    ThemePreview(t, null, PreviewTab.CHINESE, picture = image)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    imported = null
                    scope.launch {
                        val name = bytes?.let { library.addImage(it) }
                        change { t.copy(image = name) }
                    }
                }) { Text(stringResource(R.string.theme_apply)) }
            },
            dismissButton = { TextButton(onClick = { imported = null }) { Text(stringResource(R.string.ai_cancel)) } },
        )
    }
}

/** One input mode's theme: the current one, or a built-in or saved one picked from a menu. */
@Composable
private fun ModeTheme(mode: Mode, lib: ThemeLibraryData, pick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val id = lib.modes[mode]
    val current = id?.let { i -> lib.saved.firstOrNull { it.id == i }?.theme?.name?.ifEmpty { null } ?: ThemePresets.all.firstOrNull { it.id == i }?.let { presetName(it) } }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }
            .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(mode.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(current ?: stringResource(R.string.theme_per_mode_same), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.theme_per_mode_same)) }, onClick = { open = false; pick(null) })
            ThemePresets.all.forEach { p -> DropdownMenuItem(text = { Text(presetName(p)) }, onClick = { open = false; pick(p.id) }) }
            lib.saved.forEach { s -> DropdownMenuItem(text = { Text(s.theme.name.ifEmpty { stringResource(R.string.theme_unnamed) }) }, onClick = { open = false; pick(s.id) }) }
        }
    }
}

/**
 * Draws a small keyboard in [theme]'s colors: the background (with [image] or an already decoded
 * [picture]), the candidate bar of [tab]'s language and the keys (emoji on the Emoji tab; a wide,
 * low keyboard on the Landscape tab).
 * Notes: a drawing, not the keyboard itself, so editing never repaints the keyboard in use until
 * the change is saved.
 */
@Composable
private fun ThemePreview(theme: Theme, image: java.io.File?, tab: PreviewTab, picture: Bitmap? = null) {
    val context = LocalContext.current
    val palette = remember(theme) { KeyboardColors.palette(context, theme) }
    fun c(id: Int) = Color(palette.getValue(id))
    val bitmap by produceState(picture, image) { if (picture == null && image != null) value = withContext(Dispatchers.IO) { KeyboardColors.decode(image, PREVIEW_PX) } }
    val radius = theme.keyRadiusDp.dp / 2
    val candidates = stringResource(
        when (tab) {
            PreviewTab.CHINESE -> R.string.theme_sample_chinese
            PreviewTab.JAPANESE -> R.string.theme_sample_japanese
            PreviewTab.ENGLISH, PreviewTab.LANDSCAPE -> R.string.theme_sample_english
            PreviewTab.EMOJI -> R.string.theme_sample_emoji
        },
    ).split(' ')
    val rows = if (tab == PreviewTab.EMOJI) EMOJI_ROWS else LETTER_ROWS
    val description = stringResource(R.string.theme_preview_desc)
    Box(
        Modifier.fillMaxWidth().aspectRatio(if (tab == PreviewTab.LANDSCAPE) 3.2f else 1.6f).clip(RoundedCornerShape(12.dp)).background(c(R.color.kb_background))
            .semantics { contentDescription = description },
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = theme.imageOpacity / 100f)
        }
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                candidates.forEachIndexed { i, word ->
                    Text(
                        word,
                        Modifier.clip(RoundedCornerShape(50)).background(if (i == 0) c(R.color.kb_accent_container) else Color.Transparent).padding(horizontal = 8.dp, vertical = 2.dp),
                        color = if (i == 0) c(R.color.kb_on_accent_container) else c(R.color.kb_ink), fontSize = 13.sp,
                    )
                }
            }
            rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEachIndexed { k, key ->
                        val function = tab != PreviewTab.EMOJI && (r == rows.lastIndex && (k == 0 || k == row.lastIndex))
                        val pressed = r == 1 && k == 2
                        val bg = when {
                            pressed -> c(R.color.kb_key_pressed)
                            function -> c(R.color.kb_key_function)
                            else -> c(R.color.kb_key)
                        }
                        Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(radius)).background(bg), contentAlignment = Alignment.Center) {
                            Text(key, color = c(R.color.kb_ink), fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.weight(1.5f).fillMaxSize().clip(RoundedCornerShape(radius)).background(c(R.color.kb_key_function)))
                Box(Modifier.weight(5f).fillMaxSize().clip(RoundedCornerShape(radius)).background(c(R.color.kb_key)))
                Box(Modifier.weight(1.5f).fillMaxSize().clip(RoundedCornerShape(radius)).background(c(R.color.kb_accent)), contentAlignment = Alignment.Center) {
                    Text("↵", color = c(R.color.kb_on_accent), fontSize = 12.sp)
                }
            }
        }
    }
}

/** Picks a color for [role]: a swatch grid and a hex field, or back to the base's color. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(role: ColorRole, initial: Int, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(ThemeFormat.formatColor(initial)) }
    val parsed = ThemeFormat.parseColor(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(role.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SWATCHES.forEach { argb ->
                        Swatch(argb.toInt(), Modifier.size(32.dp).clickable { text = ThemeFormat.formatColor(argb.toInt()) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.theme_color_hex)) }, isError = parsed == null)
                    Swatch(parsed ?: initial, Modifier.padding(start = 8.dp).size(40.dp))
                }
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { onPick(parsed); onDismiss() }) { Text(stringResource(R.string.theme_color_use)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null); onDismiss() }) { Text(stringResource(R.string.theme_color_default)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_cancel)) }
            }
        },
    )
}

/** Asks for the name a theme is saved under. */
@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_save)) },
        text = { OutlinedTextField(name, { name = it.take(ThemeFormat.NAME_MAX) }, singleLine = true, label = { Text(stringResource(R.string.theme_name)) }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text(stringResource(R.string.theme_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_cancel)) } },
    )
}

/** A round swatch of [argb] with a thin outline. */
@Composable
private fun Swatch(argb: Int, modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(Color(argb)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
}

/** The name a built-in theme shows. */
@Composable
private fun presetName(p: Preset): String = stringResource(
    when (p.id) {
        "builtin.light" -> R.string.theme_light
        "builtin.dark" -> R.string.theme_dark
        "builtin.dynamic" -> R.string.theme_dynamic_short
        "builtin.tokyo_night" -> R.string.theme_city_tokyo
        "builtin.kyoto" -> R.string.theme_city_kyoto
        "builtin.shanghai" -> R.string.theme_city_shanghai
        "builtin.taipei" -> R.string.theme_city_taipei
        else -> R.string.theme_city_sapporo
    },
)

/** The label of a theme color. */
private val ColorRole.label: Int
    get() = when (this) {
        ColorRole.BACKGROUND -> R.string.theme_role_background
        ColorRole.KEY -> R.string.theme_role_key
        ColorRole.FUNCTION_KEY -> R.string.theme_role_function
        ColorRole.PRESSED_KEY -> R.string.theme_role_pressed
        ColorRole.TEXT -> R.string.theme_role_text
        ColorRole.SECONDARY_TEXT -> R.string.theme_role_secondary
        ColorRole.ACCENT -> R.string.theme_role_accent
        ColorRole.ON_ACCENT -> R.string.theme_role_on_accent
        ColorRole.CANDIDATE -> R.string.theme_role_candidate
        ColorRole.CANDIDATE_TEXT -> R.string.theme_role_candidate_text
    }

/**
 * Reads a picture the person picked, at most [PICTURE_MAX] bytes, scaled to at most
 * [PICTURE_PX] pixels on its long side and re-encoded as PNG.
 * Returns: the PNG bytes, or null when it is too big or not an image.
 * Notes: IO thread; the file is untrusted (true boundary).
 */
private fun readPicture(context: android.content.Context, uri: Uri): ByteArray? = try {
    val bytes = context.contentResolver.openInputStream(uri)?.use { readCapped(it, PICTURE_MAX) } ?: return null
    reencode(bytes)
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

/** Decodes [bytes] as an image no larger than [PICTURE_PX] on its long side and encodes it as PNG; null when it is not an image. */
internal fun reencode(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PICTURE_PX) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
}

/**
 * Reads a theme file the person picked (at most [ThemeLibraryFormat.EXPORT_MAX] bytes).
 * Returns: the theme and its re-encoded picture, or null when it is not a theme.
 */
private fun readTheme(context: android.content.Context, uri: Uri): Pair<Theme, ByteArray?>? = try {
    val bytes = context.contentResolver.openInputStream(uri)?.use { readCapped(it, ThemeLibraryFormat.EXPORT_MAX) } ?: return null
    val text = String(bytes, Charsets.UTF_8)
    ThemeLibraryFormat.import(text)?.let { (t, image) -> t to image?.let(::reencode) }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

/** Reads [input] up to [max] bytes. Returns: the bytes, or null when there are more. */
internal fun readCapped(input: java.io.InputStream, max: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(BUFFER)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > max) return null
        out.write(buf, 0, n)
    }
}

private const val OPACITY_STEP = 5
private const val RADIUS_STEP = 2
private const val PREVIEW_PX = 800
private const val PICTURE_PX = 2048
private const val PICTURE_MAX = 20L shl 20
private const val BUFFER = 64 * 1024
private const val EXPORT_NAME = "kotomo-theme.json"

/** The preview's letter rows (the bottom row is drawn apart). */
private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "⇧zxcvbnm⌫").map { row -> row.map { it.toString() } }

/** The preview's emoji rows. */
private val EMOJI_ROWS = listOf("😀😂🥰😎🤔😴🥳😭", "👍👏🙏💪🎉❤️🔥✨", "🍣🍜🍙☕🌸🗼🚃⛩️").map { row ->
    Regex("\\X").findAll(row).map { it.value }.toList()
}

/** The color dialog's swatches: Kotomo's own picks across hues, light to dark. */
private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFFF3F4F6, 0xFFD1D5DB, 0xFF6B7280, 0xFF374151, 0xFF111827, 0xFF000000,
    0xFFFDE2E4, 0xFFF4A6B7, 0xFFE0457B, 0xFFB3243F, 0xFFFFE8CC, 0xFFF7B267, 0xFFE07A1F,
    0xFFFFF4BF, 0xFFF2D04B, 0xFFE1F3DC, 0xFF7CC47F, 0xFF2E7D4F, 0xFFD7EEF7, 0xFF62B6D9,
    0xFF1F6FA8, 0xFFE2E4FB, 0xFF8B93E8, 0xFF3451C7, 0xFFEADCF7, 0xFFA77BD8, 0xFF5B2A86,
)
