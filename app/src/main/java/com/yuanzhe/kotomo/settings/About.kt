package com.yuanzhe.kotomo.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.yuanzhe.kotomo.R

/**
 * Describes one component or data source on the licenses and sources pages.
 * Inputs: name, version and license are shown as their owners write them; use says what Kotomo
 * uses it for; url is the project page; licenseUrl, when known, opens the license itself; holder
 * credits the copyright holder where the license asks for it; notes add required statements.
 * Notes: mirrors the inventory in `doc/en-us/third-party.md`; the two change together.
 */
internal data class Credit(
    val name: String,
    val version: String,
    val license: String,
    @StringRes val use: Int,
    val url: String,
    val licenseUrl: String? = null,
    val holder: String? = null,
    val notes: List<Int> = emptyList(),
)

/** Lists the code Kotomo is built from, as in `doc/en-us/third-party.md`. */
internal val CODE = listOf(
    Credit("librime", "1.17.0", "BSD-3-Clause (darts-clone BSD-2-Clause, utf8-cpp BSL-1.0)", R.string.use_librime, "https://github.com/rime/librime"),
    Credit("LevelDB", "1.23", "BSD-3-Clause", R.string.use_leveldb, "https://github.com/google/leveldb"),
    Credit("marisa-trie", "0.3.1", "BSD-2-Clause", R.string.use_marisa, "https://github.com/s-yata/marisa-trie"),
    Credit("yaml-cpp", "0.9.0", "MIT", R.string.use_yaml, "https://github.com/jbeder/yaml-cpp"),
    Credit("OpenCC", "1.4.2", "Apache-2.0 (rapidjson MIT)", R.string.use_opencc, "https://github.com/BYVoid/OpenCC"),
    Credit("Boost (headers)", "1.92.0", "BSL-1.0", R.string.use_boost, "https://www.boost.org/", "https://www.boost.org/LICENSE_1_0.txt"),
    Credit("AOSP LatinIME suggestion core, through HeliBoard", "HeliBoard 4.2", "Apache-2.0", R.string.use_latinime, "https://github.com/HeliBorg/HeliBoard", "https://www.apache.org/licenses/LICENSE-2.0"),
    Credit("AOSP LatinIME dicttool", "127336e9", "Apache-2.0", R.string.use_dicttool, "https://android.googlesource.com/platform/packages/inputmethods/LatinIME", "https://www.apache.org/licenses/LICENSE-2.0"),
    Credit("Mozc", "3.35.6354", "BSD-3-Clause", R.string.use_mozc, "https://github.com/google/mozc", "https://github.com/google/mozc/blob/master/LICENSE"),
    Credit("japanese-usage-dictionary", "2025-01-25", "BSD-2-Clause", R.string.use_mozc_deps, "https://github.com/hiroyuki-komatsu/japanese-usage-dictionary"),
    Credit("Abseil", "20260107.1", "Apache-2.0", R.string.use_mozc_deps, "https://github.com/abseil/abseil-cpp"),
    Credit("Protocol Buffers", "34.1", "BSD-3-Clause", R.string.use_mozc_deps, "https://github.com/protocolbuffers/protobuf"),
    Credit("AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, okio", "", "Apache-2.0", R.string.use_androidx, "https://developer.android.com/jetpack/androidx", "https://www.apache.org/licenses/LICENSE-2.0"),
    Credit("Google ML Kit: GenAI proofreading and rewriting, on-device translation, digital ink recognition", "1.0.0-beta1; translate 17.0.3; digital ink 19.0.0", "ML Kit Terms of Service", R.string.use_mlkit, "https://developers.google.com/ml-kit", "https://developers.google.com/ml-kit/terms"),
    Credit("MyApps conventions (Kyokai, MyApps-AI)", "", "AGPL-3.0, GPL-3.0", R.string.use_myapps, "https://github.com/YuanZhe-99/Kyokai"),
)

/** Lists the dictionary data Kotomo uses and its authors, as in `doc/en-us/third-party.md`. */
internal val DATA = listOf(
    Credit(
        "Noto Color Emoji", "v2026-09-24-unicode18_0", "SIL OFL-1.1", R.string.use_noto_emoji,
        "https://github.com/googlefonts/noto-emoji", "https://openfontlicense.org/", holder = "© 2013 Google LLC",
    ),
    Credit(
        "rime-luna-pinyin, rime-prelude, rime-essay, rime-stroke", "", "LGPL-3.0", R.string.use_rime_data,
        "https://github.com/rime", "https://www.gnu.org/licenses/lgpl-3.0.html",
    ),
    Credit(
        "Mozc dictionary data (dictionary_oss)", "3.35.6354", "NAIST IPAdic licence with the ICOT terms; Okinawa dictionary; Google additions",
        R.string.use_mozc_dict, "https://github.com/google/mozc/tree/master/src/data/dictionary_oss",
        "https://github.com/google/mozc/blob/master/src/data/dictionary_oss/README.txt", notes = listOf(R.string.note_build_input),
    ),
    Credit(
        "Unihan", "Unicode 18.0.0", "Unicode License v3", R.string.use_unihan, "https://www.unicode.org/charts/unihan.html",
        "https://www.unicode.org/license.txt", holder = "© Unicode, Inc.", notes = listOf(R.string.note_build_input),
    ),
    Credit(
        "CC-CEDICT", "2026-11-11", "CC BY-SA 4.0", R.string.use_cedict, "https://www.mdbg.net/chinese/dictionary?page=cc-cedict",
        "https://creativecommons.org/licenses/by-sa/4.0/", holder = "MDBG; based on CEDICT by Paul Andrew Denisowski",
        notes = listOf(R.string.note_build_input),
    ),
    Credit(
        "OpenCC Japanese Shinjitai tables", "1.4.2", "Apache-2.0", R.string.use_shinjitai, "https://github.com/BYVoid/OpenCC",
        "https://www.apache.org/licenses/LICENSE-2.0", notes = listOf(R.string.note_build_input),
    ),
    Credit(
        "JMdict (through jmdict-simplified 3.6.2)", "2026-11-09", "CC BY-SA 4.0 (EDRDG licence)", R.string.use_jmdict,
        "https://www.edrdg.org/wiki/index.php/JMdict-EDICT_Dictionary_Project", "https://www.edrdg.org/edrdg/licence.html",
        holder = "© Electronic Dictionary Research and Development Group", notes = listOf(R.string.note_build_input),
    ),
    Credit(
        "English word list (Leipzig Corpora Collection, through HeliBoard's aosp-dictionaries)", "ff0cd52b", "CC BY 4.0",
        R.string.use_wordlist, "https://codeberg.org/Helium314/aosp-dictionaries", "https://creativecommons.org/licenses/by/4.0/",
        holder = "Leipzig Corpora Collection, Universität Leipzig", notes = listOf(R.string.note_converted),
    ),
    // The dictionaries the settings download from their sources (dictionary/Sources.kt); none ships in the APK.
    Credit(
        "rime-ice", "latest release", "GPL-3.0", R.string.use_rime_ice, "https://github.com/iDvel/rime-ice",
        "https://www.gnu.org/licenses/gpl-3.0.html", holder = "iDvel and contributors", notes = listOf(R.string.note_download),
    ),
    Credit(
        "THUOCL: Tsinghua Open Chinese Lexicon", "master", "MIT", R.string.use_thuocl, "https://github.com/thunlp/THUOCL",
        "https://github.com/thunlp/THUOCL/blob/master/LICENSE", holder = "THUNLP", notes = listOf(R.string.note_download, R.string.note_thuocl_cite),
    ),
    Credit(
        "fcitx5-pinyin-zhwiki (web slang from Chinese Wikipedia)", "latest release", "CC BY-SA 4.0", R.string.use_zhwiki,
        "https://github.com/felixonmars/fcitx5-pinyin-zhwiki", "https://creativecommons.org/licenses/by-sa/4.0/",
        holder = "Wikipedia contributors; converted by Felix Yan", notes = listOf(R.string.note_download),
    ),
)

/**
 * Describes one design reference: its name and what Kotomo learned from it, with its official
 * page.
 */
private data class Inspiration(@StringRes val name: Int, @StringRes val desc: Int, val url: String)

/** Lists the products whose interaction design inspired Kotomo (PLAN §15.2); Madamada first. */
private val DESIGN = listOf(
    Inspiration(R.string.design_madamada, R.string.design_madamada_desc, "https://madamada.catchingnow.com/"),
    Inspiration(R.string.design_gboard, R.string.design_gboard_desc, "https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin"),
    Inspiration(R.string.design_mspinyin, R.string.design_mspinyin_desc, "https://support.microsoft.com/windows/microsoft-simplified-chinese-ime-9b962a3b-2fa4-4f37-811c-b1886320dd72"),
    Inspiration(R.string.design_sogou, R.string.design_sogou_desc, "https://shurufa.sogou.com/"),
    Inspiration(R.string.design_swiftkey, R.string.design_swiftkey_desc, "https://www.microsoft.com/swiftkey"),
    Inspiration(R.string.design_gji, R.string.design_gji_desc, "https://www.google.co.jp/ime/"),
)

/**
 * Draws the About page: the version (read from the package), Kotomo's license and the links to
 * the licenses, data sources and design acknowledgements.
 */
@Composable
internal fun AboutPage(open: (Page) -> Unit) {
    val context = LocalContext.current
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    Section(stringResource(R.string.app_name)) {
        Inset {
            Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium)
            Note(stringResource(R.string.about_license))
        }
    }
    Section(null) {
        AboutLink(Page.LICENSES, R.string.about_licenses_desc, open)
        RowDivider()
        AboutLink(Page.SOURCES, R.string.about_sources_desc, open)
        RowDivider()
        AboutLink(Page.DESIGN, R.string.about_design_desc, open)
    }
}

/** Draws one link row of the About page. */
@Composable
private fun AboutLink(page: Page, @StringRes desc: Int, open: (Page) -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.settings_row_min_small)).clickable { open(page) }
            .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
    ) {
        Text(stringResource(page.title), style = MaterialTheme.typography.bodyLarge)
        Note(stringResource(desc))
    }
}

/** Draws the open-source licenses page: every code component with its license and links. */
@Composable
internal fun LicensesPage() {
    Note(stringResource(R.string.licenses_note))
    Section(null) { Credits(CODE) }
}

/**
 * Draws the data sources page: every dictionary source with its license, copyright holder and
 * required statements.
 * Notes: this is the separate sources page reached from a menu that the EDRDG licence asks for.
 */
@Composable
internal fun SourcesPage() {
    Note(stringResource(R.string.sources_note))
    Section(null) { Credits(DATA) }
}

/** Draws the design acknowledgements page: the references, credited as inspiration only. */
@Composable
internal fun DesignPage() {
    val uri = LocalUriHandler.current
    Note(stringResource(R.string.design_note))
    Section(null) {
        DESIGN.forEachIndexed { i, item ->
            if (i > 0) RowDivider()
            Column(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { uri.openUri(item.url) }
                    .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_chip_gap)),
            ) {
                if (i == 0) Text(stringResource(R.string.design_special), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                LinkTitle(stringResource(item.name))
                Note(stringResource(item.desc))
            }
        }
    }
}

/** Draws a list of credits; a tap opens the project page, the license link opens the license. */
@Composable
private fun Credits(credits: List<Credit>) {
    val uri = LocalUriHandler.current
    credits.forEachIndexed { i, c ->
        if (i > 0) RowDivider()
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { uri.openUri(c.url) }
                .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_chip_gap)),
        ) {
            LinkTitle(if (c.version.isEmpty()) c.name else "${c.name} · ${c.version}")
            Text(c.license, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            c.holder?.let { Note(it) }
            Note(stringResource(c.use))
            c.notes.forEach { Note(stringResource(it)) }
            c.licenseUrl?.let { url ->
                Text(
                    stringResource(R.string.license_open),
                    Modifier.clickable(role = Role.Button) { uri.openUri(url) },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Draws a credit's title with the icon that marks it as a web link. */
@Composable
private fun LinkTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_chip_gap))) {
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Icon(
            painterResource(R.drawable.ic_settings_link), null, Modifier.size(dimensionResource(R.dimen.settings_chevron)),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
