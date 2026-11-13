package com.yuanzhe.kotomo.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuanzhe.kotomo.R

/**
 * Hosts the Kotomo settings app: the home with keyboard setup and a field to try typing, and one
 * page per group of settings; on wide windows a navigation list beside the page.
 *
 * Notes: settings come from the process's [SettingsStore], which the keyboard shares.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = SettingsStore.get(this)
        // The keyboard's Dictionaries and Themes tools open their page (PLAN §10.10).
        val start = intent.getStringExtra(EXTRA_PAGE)?.let { name -> Page.entries.firstOrNull { it.name == name && it.root == it } }
        setContent { KotomoTheme { SettingsApp(store, start) } }
    }

    companion object {
        /** The intent extra naming the page to open on (a [Page] name). */
        const val EXTRA_PAGE = "page"
        /** [EXTRA_PAGE] for the Dictionaries page. */
        const val PAGE_DICTIONARIES = "DICTIONARIES"
        /** [EXTRA_PAGE] for the Themes page. */
        const val PAGE_THEMES = "THEMES"
    }
}

/**
 * Names every settings page and the title it shows.
 * Notes: [LICENSES], [SOURCES] and [DESIGN] open from [ABOUT]; [SETUP] is a page only in the
 * two-pane layout, where the home's setup card has no home to sit on.
 */
internal enum class Page(@StringRes val title: Int) {
    SETUP(R.string.page_setup),
    MODES(R.string.page_modes),
    MIXED(R.string.page_mixed),
    CHINESE(R.string.page_chinese),
    JAPANESE(R.string.page_japanese),
    ENGLISH(R.string.page_english),
    LAYOUT(R.string.page_layout),
    KEYS(R.string.page_keys),
    EMOJI(R.string.page_emoji),
    THEMES(R.string.page_themes),
    DICTIONARIES(R.string.page_dictionaries),
    AI(R.string.page_ai),
    PRIVACY(R.string.page_privacy),
    CLIPBOARD(R.string.page_clipboard),
    LEARNED(R.string.page_learned),
    DIAGNOSTICS(R.string.diag_title),
    ABOUT(R.string.page_about),
    LICENSES(R.string.page_licenses),
    SOURCES(R.string.page_sources),
    DESIGN(R.string.page_design),
}

/**
 * Lays out the settings app: one pane with a back stack on phones, the navigation list and the
 * page side by side from `settings_two_pane_min` on.
 * Inputs: [start] is the page to open on (the keyboard's tools), or null for the home.
 * Side effects: system back pops the page stack before leaving the app.
 */
@Composable
private fun SettingsApp(store: SettingsStore, start: Page?) {
    val settings by store.flow.collectAsStateWithLifecycle()
    val stack = rememberSaveable(saver = listSaver(save = { it.map(Page::name) }, restore = { it.map(Page::valueOf).toMutableStateList() })) {
        mutableStateListOf<Page>().apply { if (start != null) add(start) }
    }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    val open: (Page) -> Unit = { stack.add(it) }
    val back: () -> Unit = { stack.removeAt(stack.lastIndex) }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth >= dimensionResource(R.dimen.settings_two_pane_min)) {
            val page = stack.lastOrNull() ?: Page.SETUP
            Row(Modifier.fillMaxSize()) {
                NavList(page.root) { stack.clear(); if (it != Page.SETUP) stack.add(it) }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(horizontal = dimensionResource(R.dimen.settings_detail_pad), vertical = dimensionResource(R.dimen.settings_nav_pad)),
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_section_gap)),
                ) {
                    // A sub-page (a licenses list) keeps its back button; a top-level page has none.
                    PageHeader(page.title, if (page.root != page) back else null)
                    PageContent(page, settings, store, open)
                }
            }
        } else {
            val page = stack.lastOrNull()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = dimensionResource(R.dimen.settings_gutter))
                    .padding(bottom = dimensionResource(R.dimen.settings_nav_pad)),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_section_gap)),
            ) {
                if (page == null) {
                    HomePage(settings, store, open)
                } else {
                    Spacer(Modifier)
                    PageHeader(page.title, back)
                    PageContent(page, settings, store, open)
                }
            }
        }
    }
}

/** Returns the top-level page a sub-page belongs to, for the navigation list's highlight. */
private val Page.root get() = when (this) {
    Page.LICENSES, Page.SOURCES, Page.DESIGN -> Page.ABOUT
    Page.CLIPBOARD -> Page.PRIVACY
    Page.LEARNED -> Page.PRIVACY
    else -> this
}

/** Draws a page's content by its [Page]. */
@Composable
private fun PageContent(page: Page, settings: Settings, store: SettingsStore, open: (Page) -> Unit) {
    when (page) {
        Page.SETUP -> SetupCard()
        Page.MODES -> ModesPage(settings, store)
        Page.MIXED -> MixedPage(settings, store)
        Page.CHINESE -> ChinesePage(settings, store)
        Page.JAPANESE -> JapanesePage(settings, store)
        Page.ENGLISH -> EnglishPage(settings, store)
        Page.LAYOUT -> LayoutPage(settings, store)
        Page.KEYS -> KeysPage(settings, store)
        Page.EMOJI -> EmojiPage(settings, store)
        Page.THEMES -> ThemesPage(settings, store)
        Page.DICTIONARIES -> DictionariesPage()
        Page.AI -> AiPage(settings, store)
        Page.PRIVACY -> PrivacyPage(settings, store, open)
        Page.CLIPBOARD -> ClipboardPage(settings, store)
        Page.LEARNED -> LearnedPage()
        Page.DIAGNOSTICS -> DiagnosticsPage()
        Page.ABOUT -> AboutPage(open)
        Page.LICENSES -> LicensesPage()
        Page.SOURCES -> SourcesPage()
        Page.DESIGN -> DesignPage()
    }
}

/**
 * Draws the two-pane navigation list (every top-level page) with [current] highlighted.
 * Inputs: select opens a top-level page in the detail pane.
 */
@Composable
private fun NavList(current: Page, select: (Page) -> Unit) {
    Column(
        Modifier.width(dimensionResource(R.dimen.settings_nav_width)).fillMaxHeight().verticalScroll(rememberScrollState())
            .padding(horizontal = dimensionResource(R.dimen.settings_gutter), vertical = dimensionResource(R.dimen.settings_nav_pad)),
    ) {
        Text(
            stringResource(R.string.app_name),
            Modifier.padding(horizontal = dimensionResource(R.dimen.settings_row_pad)).padding(bottom = dimensionResource(R.dimen.settings_gutter)),
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
        )
        Page.entries.filter { it.root == it }.forEach { page ->
            val on = page == current
            Row(
                Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.settings_nav_item))
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                    .clickable { select(page) }
                    .padding(horizontal = dimensionResource(R.dimen.settings_gutter)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(page.title),
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Draws the phone home: the title, the setup card, the grouped links to every page and the
 * incognito switch, as in SettingsHome.
 */
@Composable
private fun HomePage(settings: Settings, store: SettingsStore, open: (Page) -> Unit) {
    Text(
        stringResource(R.string.app_name),
        Modifier.padding(top = dimensionResource(R.dimen.settings_home_top), start = dimensionResource(R.dimen.settings_group_title_pad)),
        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
    )
    SetupCard()
    Group(stringResource(R.string.section_input)) {
        LinkRow(stringResource(R.string.badge_modes), Page.MODES, stringResource(R.string.page_modes_desc), open)
        LinkRow(stringResource(R.string.mode_badge_mixed), Page.MIXED, stringResource(R.string.page_mixed_desc, stringResource(settings.mixed.level.label)), open)
        LinkRow(stringResource(R.string.mode_badge_chinese), Page.CHINESE, stringResource(R.string.page_chinese_desc), open)
        LinkRow(stringResource(R.string.mode_badge_japanese), Page.JAPANESE, stringResource(R.string.page_japanese_desc), open)
        LinkRow(stringResource(R.string.mode_badge_english), Page.ENGLISH, stringResource(R.string.page_english_desc), open, last = true)
    }
    Group(stringResource(R.string.section_keyboard)) {
        LinkRow(stringResource(R.string.badge_layout), Page.LAYOUT, stringResource(R.string.page_layout_desc), open)
        LinkRow(stringResource(R.string.badge_keys), Page.KEYS, stringResource(R.string.page_keys_desc), open)
        LinkRow(stringResource(R.string.badge_emoji), Page.EMOJI, stringResource(R.string.page_emoji_desc), open)
        LinkRow(stringResource(R.string.badge_themes), Page.THEMES, stringResource(R.string.page_themes_desc), open, last = true)
    }
    Group(stringResource(R.string.section_data)) {
        LinkRow(stringResource(R.string.badge_dictionaries), Page.DICTIONARIES, stringResource(R.string.page_dictionaries_desc), open)
        LinkRow(stringResource(R.string.badge_ai), Page.AI, stringResource(if (settings.ai.enabled) R.string.page_ai_desc_on else R.string.page_ai_desc_off), open)
        LinkRow(stringResource(R.string.badge_diagnostics), Page.DIAGNOSTICS, stringResource(R.string.page_diagnostics_desc), open, last = true)
    }
    Group(stringResource(R.string.section_privacy)) {
        SwitchRow(R.string.incognito_title, R.string.incognito_desc, settings.incognito) { on -> store.set { it.copy(incognito = on) } }
        RowDivider()
        LinkRow(stringResource(R.string.badge_privacy), Page.PRIVACY, stringResource(R.string.page_privacy_desc), open, last = true)
    }
    TextButton(onClick = { open(Page.ABOUT) }) { Text(stringResource(R.string.page_about)) }
}

/**
 * Draws the setup card: the two setup steps until Kotomo is enabled and selected, then the
 * "Kotomo is on" note; below either, the fields to try typing.
 * Notes: the setup state is re-read whenever the screen resumes, since it changes in system
 * settings and the input method picker.
 */
@Composable
private fun SetupCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
        selected = SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("${context.packageName}/") == true
        onPauseOrDispose {}
    }
    val pad = dimensionResource(R.dimen.settings_hero_pad)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(dimensionResource(R.dimen.settings_card_radius)))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(pad),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_card_gap)),
    ) {
        val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
        if (enabled && selected) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_card_gap))) {
                Icon(
                    painterResource(R.drawable.ic_settings_check), null,
                    Modifier.size(dimensionResource(R.dimen.settings_badge)).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary).padding((dimensionResource(R.dimen.settings_badge) - dimensionResource(R.dimen.settings_icon)) / 2),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Column {
                    Text(stringResource(R.string.home_ready), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = onContainer)
                    Text(stringResource(R.string.home_ready_note), style = MaterialTheme.typography.bodySmall, color = onContainer)
                }
            }
        } else {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = onContainer)
            Step(stringResource(R.string.setup_enable), enabled, stringResource(R.string.setup_enable_action)) {
                context.startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS))
            }
            Step(stringResource(R.string.setup_select), selected, stringResource(R.string.setup_select_action)) {
                context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        }
        var text by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        val shape = RoundedCornerShape(dimensionResource(R.dimen.settings_field_radius))
        val colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        )
        Text(stringResource(R.string.try_title), style = MaterialTheme.typography.bodySmall, color = onContainer)
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), shape = shape, colors = colors,
            placeholder = { Text(stringResource(R.string.try_example)) },
        )
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), shape = shape, colors = colors,
            placeholder = { Text(stringResource(R.string.try_password_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
    }
}

/** Draws one setup step: its text, then "Done" or the button that does it. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun Step(text: String, done: Boolean, action: String, onClick: () -> Unit) {
    // A flow row: with a large font the button moves under the text instead of squeezing it.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_card_gap)),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_card_gap)),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f).widthIn(min = dimensionResource(R.dimen.settings_step_text_min)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (done) {
            Text(stringResource(R.string.setup_done), color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = onClick) { Text(action) }
        }
    }
}

/** Draws a page's title row, with a back button when [onBack] is given. */
@Composable
private fun PageHeader(@StringRes title: Int, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_group_gap))) {
        if (onBack != null) {
            Icon(
                painterResource(R.drawable.ic_settings_back), stringResource(R.string.nav_back),
                Modifier.size(dimensionResource(R.dimen.settings_touch)).clip(CircleShape).clickable(onClick = onBack)
                    .padding((dimensionResource(R.dimen.settings_touch) - dimensionResource(R.dimen.settings_icon)) / 2),
            )
        }
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

/**
 * Draws one link row of the home: a badge, the page's title and a description, and a chevron.
 * Inputs: last leaves out the divider under the group's final row.
 */
@Composable
private fun LinkRow(badge: String, page: Page, desc: String, open: (Page) -> Unit, last: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.settings_row_min)).clickable { open(page) }
            .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_row_gap)),
    ) {
        Badge(badge)
        Column(Modifier.weight(1f)) {
            Text(stringResource(page.title), style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            painterResource(R.drawable.ic_settings_chevron), null, Modifier.size(dimensionResource(R.dimen.settings_chevron)),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!last) RowDivider()
}

/**
 * Applies Kotomo's colors to Material 3, light or dark: the keyboard's accent tokens and the
 * settings surfaces.
 */
@Composable
internal fun KotomoTheme(content: @Composable () -> Unit) {
    val accent = colorResource(R.color.kb_accent)
    val onAccent = colorResource(R.color.kb_on_accent)
    val container = colorResource(R.color.kb_accent_container)
    val onContainer = colorResource(R.color.kb_on_accent_container)
    val background = colorResource(R.color.settings_background)
    val card = colorResource(R.color.settings_card)
    val badge = colorResource(R.color.settings_badge)
    val divider = colorResource(R.color.settings_divider)
    val ink = colorResource(R.color.kb_ink)
    val inkSecondary = colorResource(R.color.kb_ink_secondary)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = container, onPrimaryContainer = onContainer,
            secondaryContainer = container, onSecondaryContainer = onContainer,
            background = background, onBackground = ink, surface = card, onSurface = ink,
            surfaceVariant = badge, onSurfaceVariant = inkSecondary, outlineVariant = divider,
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = container, onPrimaryContainer = onContainer,
            secondaryContainer = container, onSecondaryContainer = onContainer,
            background = background, onBackground = ink, surface = card, onSurface = ink,
            surfaceVariant = badge, onSurfaceVariant = inkSecondary, outlineVariant = divider,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
