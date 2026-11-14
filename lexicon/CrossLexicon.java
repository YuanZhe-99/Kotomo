import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds the cross-language lexicon prototype of spike S5 (PLAN.md §5.9, §7): pinyin → Japanese
 * kanji words and English → Japanese words. Run from the repository root:
 * {@code java lexicon/CrossLexicon.java [--offline]}.
 *
 * <p>Inputs: the pinned sources in {@link #SOURCES}, kept under {@code .deps/<source>/}; missing
 * files are downloaded unless {@code --offline} is given, and every file must match its pinned
 * SHA-256. Outputs (build output, never committed): {@code build/lexicon/pinyin-ja.tsv},
 * {@code build/lexicon/en-ja.tsv}, {@code build/lexicon/pinyin-skipped.tsv} and
 * {@code build/lexicon/metadata.json} (source versions,
 * hashes, coverage).
 *
 * <p>Notes: exit status 0 on success, 1 when a source is missing, cannot be fetched or does not
 * match its pin, 2 on a read or write error. Costs follow Mozc: lower is better; each row's cost is
 * the Mozc word cost plus the derivation penalties named below.
 */
public final class CrossLexicon {
    private static final Path DEPS = Path.of(".deps");
    private static final Path OUT = Path.of("build", "lexicon");

    /** One downloadable file: where it comes from, its name under .deps/<source>/, its pin. */
    private record Remote(String url, String file, String sha256) {}

    /** One pinned data source and the files the pipeline reads from it. */
    private record Source(String id, String version, String license, String home, List<Remote> files) {}

    private static final String MOZC_RAW = "https://raw.githubusercontent.com/google/mozc/3.35.6354/src/data/dictionary_oss/";
    private static final String OPENCC_RAW = "https://raw.githubusercontent.com/BYVoid/OpenCC/ver.1.4.2/data/dictionary/";

    /**
     * The pinned sources. Notes: CC-CEDICT is published only as "the current file", so its pin is
     * the header date plus the hash; a newer upload fails the check until the pin is moved.
     */
    private static final List<Source> SOURCES = List.of(
            new Source("mozc", "3.35.6354 (commit 0136c35782536cb43d710575c311cd4185982738)",
                    "BSD-3-Clause; dictionary data under the IPAdic (NAIST/ICOT) and Okinawa dictionary terms in README.txt",
                    "https://github.com/google/mozc/tree/3.35.6354/src/data/dictionary_oss", List.of(
                    new Remote(MOZC_RAW + "dictionary00.txt", "dictionary00.txt", "9e07ce292932c4fff327ed8cb06274c09acca556bd8bdf7c7ef1f6173eae3b61"),
                    new Remote(MOZC_RAW + "dictionary01.txt", "dictionary01.txt", "836fd5fd8399510de0d8ef7b33be913cca6bbaee7b712cc8045fba03f4c75a89"),
                    new Remote(MOZC_RAW + "dictionary02.txt", "dictionary02.txt", "93943858d0cfd092b849a78def6901eaf228177e1ac5663544e865a3a1e45a9e"),
                    new Remote(MOZC_RAW + "dictionary03.txt", "dictionary03.txt", "cb9f1b0b4a7550d682d86244a021cd17e0b15366328819e69135b5f183a1a24b"),
                    new Remote(MOZC_RAW + "dictionary04.txt", "dictionary04.txt", "9fdc4e3948d03463de13ab5782adffbc11dd06e934cb80138c5920029cd3d2f6"),
                    new Remote(MOZC_RAW + "dictionary05.txt", "dictionary05.txt", "199e3b0f050b691823ae7ba943090d437f51af6436b27c5f3dc69cafa12000e4"),
                    new Remote(MOZC_RAW + "dictionary06.txt", "dictionary06.txt", "b95b14f7770ae2a824bc8e9fad8b03c1bc45c504f93a36554b8b56daa0368d2a"),
                    new Remote(MOZC_RAW + "dictionary07.txt", "dictionary07.txt", "7f53649b529d4c8c495d3a8dd9c4d489dfe7696080f7423107ff01331174be48"),
                    new Remote(MOZC_RAW + "dictionary08.txt", "dictionary08.txt", "50a31bb5fc1646721b00be371a5695dffab1292bda143e1e59cc70cbbdd8643f"),
                    new Remote(MOZC_RAW + "dictionary09.txt", "dictionary09.txt", "4e5fcd4cbfbf11395166a37b506f547fc54d20edc0884f5184ecd3fc705f0d9a"),
                    new Remote(MOZC_RAW + "id.def", "id.def", "07a05a268c3783b7e02b36e4aa591555a2dd19a1f3c93891576740761c154f2d"),
                    new Remote(MOZC_RAW + "README.txt", "README.txt", "6b1de66bc6fa30e0b45dc45b8c8f6c57bd78aff923c261a3efffac8eb86f7bac"))),
            new Source("opencc", "ver.1.4.2 (commit 025f371dc76b598d77384fbdab90c937471844d8)", "Apache-2.0",
                    "https://github.com/BYVoid/OpenCC/tree/ver.1.4.2/data/dictionary", List.of(
                    new Remote(OPENCC_RAW + "JPShinjitaiCharacters.txt", "JPShinjitaiCharacters.txt", "12cec7250b873ef52b36d8f92218d4f92c0aaf5d8cd7c58fe42d9785bdcdc43a"),
                    new Remote(OPENCC_RAW + "JPShinjitaiPhrases.txt", "JPShinjitaiPhrases.txt", "799c9d69d78fcf96b35110693866bd34a070e4f50e6be55a4ecf8080cd2832d6"))),
            new Source("unihan", "18.0.0", "Unicode-3.0", "https://www.unicode.org/Public/18.0.0/ucd/", List.of(
                    new Remote("https://www.unicode.org/Public/18.0.0/ucd/Unihan.zip", "Unihan.zip", "4c93ea9c1f636451729a840978f1667a53886af37ba854fdcce109721c63d43e"))),
            new Source("cc-cedict", "2026-11-14T02:25:17Z", "CC-BY-SA-4.0",
                    "https://www.mdbg.net/chinese/dictionary?page=cc-cedict", List.of(
                    new Remote("https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.zip", "cedict_1_0_ts_utf-8_mdbg.zip", "d03cba2ee4d886aed7b55d81a558fc5fe8f1e00d6da7135eb68f4949c543504f"))),
            new Source("jmdict", "jmdict-simplified 3.6.2+20261109214828 (JMdict 2026-11-09)", "CC-BY-SA-4.0 (EDRDG licence)",
                    "https://github.com/scriptin/jmdict-simplified/releases/tag/3.6.2%2B20261109214828", List.of(
                    new Remote("https://github.com/scriptin/jmdict-simplified/releases/download/3.6.2%2B20261109214828/jmdict-eng-3.6.2%2B20261109214828.json.zip",
                            "jmdict-eng-3.6.2+20261109214828.json.zip", "6499b5ff4f6c57878a9e01e4df8d2c33ab1a8a3d649ca64e9b45d3885d645fb6"))));

    // Pinyin derivation penalties, added to the Mozc word cost.
    /** Reading built character by character from Unihan instead of a CC-CEDICT word. */
    private static final int PER_CHAR = 300;
    /** Each character read with a non-primary reading (polyphone expansion). */
    private static final int ALT_READING = 600;
    /** Each character mapped to a non-first old form (弁 → 辯, 瓣 after 辨). */
    private static final int ALT_GLYPH = 400;
    /** Readings kept per Japanese surface. */
    private static final int MAX_READINGS = 4;
    /** Old-form spellings tried per Japanese surface. */
    private static final int MAX_GLYPH_FORMS = 16;

    // English → Japanese penalties.
    /**
     * Cost of a JMdict word that has no Mozc entry with the same surface and reading, and of
     * every hiragana-only surface: a kana string matches unrelated Mozc entries (ぬこ is also the
     * volitional stem of 抜く), so its Mozc cost and POS ids are not taken.
     */
    private static final int NO_MOZC_COST = 9000;
    /** Each later JMdict sense (capped at six): the first sense is the central meaning. */
    private static final int SENSE_STEP = 600;
    /** Each later gloss within a sense (capped at six). */
    private static final int GLOSS_STEP = 200;
    /** A gloss with a parenthesized qualifier ("school (of thought)") is a narrower match. */
    private static final int PAREN_GLOSS = 800;
    /** A word without any JMdict priority tag, or a form not marked common. */
    private static final int NOT_COMMON = 700;
    /** A kanji form of a sense usually written in kana (珈琲 for coffee). */
    private static final int USUALLY_KANA = 1500;
    private static final int ATEJI = 800;
    /** A katakana spelling of a native word (ネコ for 猫), not a loanword. */
    private static final int KANA_OF_KANJI_WORD = 1200;
    /** An archaic, obsolete, rare or abbreviated sense. */
    private static final int ARCHAIC = 2000;
    private static final int ABBREVIATION = 1500;
    /** The transliteration class ranks after every dictionary meaning (§5.9). */
    private static final int TRANSLIT = 4000;

    /** One output row of either table; lid and rid are Mozc POS ids. */
    private record Row(String key, String detail, String surface, String reading, int lid, int rid, int cost, String source) {}

    /** A Mozc POS pair and cost for one surface and reading. */
    private record MozcWord(int lid, int rid, int cost) {}

    /** A candidate Chinese reading: numbered syllables ("chi2 dai4") and its penalty. */
    private record Reading(String syllables, int penalty) {}

    /** Why the pinyin derivation produced nothing for a surface, or the readings it produced. */
    private record Derivation(List<Reading> readings, String source, String unreadable) {}

    private final Map<String, Object> stats = new LinkedHashMap<>();

    // Unihan fields, by code point.
    private final Map<Integer, String> mandarin = new HashMap<>();
    private final Map<Integer, List<String>> dictReadings = new HashMap<>();
    private final Map<Integer, Integer> simplified = new HashMap<>();
    private final Map<Integer, List<Integer>> oldForms = new HashMap<>();
    private final Map<String, String> phrases = new HashMap<>();
    private int longestPhrase;
    private final Map<String, List<String>> cedictTrad = new HashMap<>();
    private final Map<String, List<String>> cedictSimp = new HashMap<>();
    private final Map<String, List<String>> cedictChar = new HashMap<>();
    private final Map<String, Derivation> derived = new HashMap<>();
    private String cedictDate = "";
    private int nounId = -1;

    /**
     * Fetches or verifies the sources, then builds both tables and the metadata.
     * Side effects: may download into .deps/; writes build/lexicon/; exits with the status in the
     * class comment.
     */
    public static void main(String[] args) {
        boolean offline = List.of(args).contains("--offline");
        List<String> problems = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Source s : SOURCES) {
            for (Remote r : s.files()) {
                ensure(s, r, offline, problems, hashes);
            }
        }
        if (!problems.isEmpty()) {
            problems.forEach(System.err::println);
            System.exit(1);
        }
        try {
            new CrossLexicon().build(hashes);
        } catch (IOException | UncheckedIOException e) {
            System.err.println("lexicon: " + e);
            System.exit(2);
        }
    }

    /**
     * Makes sure one pinned file is present under .deps/ and matches its hash.
     * Inputs: offline forbids downloading; problems receives one line per failure; hashes
     * receives the verified hash by path.
     * Side effects: downloads to a .part file and moves it into place only when the hash matches.
     */
    private static void ensure(Source s, Remote r, boolean offline, List<String> problems, Map<String, String> hashes) {
        Path file = DEPS.resolve(s.id()).resolve(r.file());
        try {
            if (!Files.exists(file)) {
                if (offline) {
                    problems.add("missing " + slash(file) + " (offline)");
                    return;
                }
                Files.createDirectories(file.getParent());
                Path part = file.resolveSibling(r.file() + ".part");
                System.out.println("fetch " + r.url());
                HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpResponse<Path> res = http.send(HttpRequest.newBuilder(URI.create(r.url())).build(),
                        HttpResponse.BodyHandlers.ofFile(part));
                if (res.statusCode() != 200) {
                    problems.add("fetch " + r.url() + ": HTTP " + res.statusCode());
                    return;
                }
                String got = sha256(part);
                if (!got.equals(r.sha256())) {
                    problems.add(slash(part) + ": sha256 " + got + ", pinned " + r.sha256() + " (upstream changed; move the pin deliberately)");
                    return;
                }
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            }
            String got = sha256(file);
            if (!got.equals(r.sha256())) {
                problems.add(slash(file) + ": sha256 " + got + ", pinned " + r.sha256());
                return;
            }
            hashes.put(slash(file), got);
        } catch (IOException e) {
            problems.add(slash(file) + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problems.add(slash(file) + ": interrupted");
        }
    }

    /**
     * Runs the whole pipeline over verified sources.
     * Inputs: hashes, the verified files and their SHA-256, recorded in the metadata.
     * Side effects: writes the two tables and metadata.json under build/lexicon/.
     */
    private void build(Map<String, String> hashes) throws IOException {
        loadUnihan(DEPS.resolve("unihan/Unihan.zip"));
        loadOpenCc(DEPS.resolve("opencc"));
        loadCedict(DEPS.resolve("cc-cedict/cedict_1_0_ts_utf-8_mdbg.zip"));
        loadNounId(DEPS.resolve("mozc/id.def"));

        List<Row> en = new ArrayList<>();
        Set<String> wanted = new HashSet<>();
        readJmdict(DEPS.resolve("jmdict/jmdict-eng-3.6.2+20261109214828.json.zip"), en, wanted);

        List<Row> pinyin = new ArrayList<>();
        Map<String, MozcWord> mozc = new HashMap<>();
        readMozc(pinyin, wanted, mozc);
        en = joinMozc(en, mozc);

        Files.createDirectories(OUT);
        write(OUT.resolve("pinyin-ja.tsv"), "key\tsyllables\tsurface\treading\tlid\trid\tcost\tsource", pinyin);
        write(OUT.resolve("en-ja.tsv"), "key\tclass\tsurface\treading\tlid\trid\tcost\tsource", en);
        writeMetadata(hashes);
        System.out.println("pinyin-ja: " + pinyin.size() + " rows, " + stats.get("pinyin.keys") + " keys");
        System.out.println("en-ja: " + en.size() + " rows, " + stats.get("en.keys") + " keys");
        System.out.println("wrote " + slash(OUT));
    }

    // ---------------------------------------------------------------- sources

    /**
     * Reads the Unihan fields §5.9 needs: kMandarin, the dictionary readings (kXHC1983,
     * kTGHZ2013, kHanyuPinyin), kSimplifiedVariant and kJapaneseOldVariant.
     * Side effects: fills the Unihan maps.
     * Notes: dictionary readings both attest a character as Chinese and supply polyphone
     * alternatives; kHanyuPinyin attests only, since it carries many classical readings.
     */
    private void loadUnihan(Path zip) throws IOException {
        Map<Integer, Set<String>> modern = new HashMap<>();
        Set<Integer> attested = new HashSet<>();
        eachZipLine(zip, line -> {
            if (line.startsWith("#") || line.isEmpty()) {
                return;
            }
            String[] f = line.split("\t", 3);
            if (f.length < 3) {
                return;
            }
            int cp = Integer.parseInt(f[0].substring(2), 16);
            switch (f[1]) {
                case "kMandarin" -> mandarin.put(cp, f[2]);
                case "kXHC1983", "kTGHZ2013" -> {
                    attested.add(cp);
                    for (String tok : f[2].split(" ")) {
                        for (String p : tok.substring(tok.indexOf(':') + 1).split(",")) {
                            modern.computeIfAbsent(cp, k -> new LinkedHashSet<>()).add(numbered(p));
                        }
                    }
                }
                case "kHanyuPinyin" -> attested.add(cp);
                case "kSimplifiedVariant" -> simplified.put(cp, codePoint(f[2].split(" ")[0]));
                case "kJapaneseOldVariant" -> {
                    List<Integer> forms = oldVariants(cp, f[2].split(" "));
                    if (!forms.isEmpty()) {
                        oldForms.put(cp, forms);
                    }
                }
                default -> { }
            }
        });
        // Every kMandarin value counts, attested by a Chinese dictionary or not: the user decided
        // (2026-11-04) to follow Unihan for kokuji such as 込 (yū) and 峠 (qiǎ), since Chinese
        // dictionaries have no reading for them at all.
        attested.addAll(mandarin.keySet());
        for (int cp : attested) {
            LinkedHashSet<String> all = new LinkedHashSet<>();
            String m = mandarin.get(cp);
            if (m != null) {
                for (String p : m.split(" ")) {
                    all.add(numbered(p));
                }
            }
            all.addAll(modern.getOrDefault(cp, Set.of()));
            if (!all.isEmpty()) {
                dictReadings.put(cp, List.copyOf(all));
            }
        }
        stats.put("unihan.readable", dictReadings.size());
        stats.put("unihan.japaneseOldVariant", oldForms.size());
    }

    /**
     * Reads OpenCC's JPShinjitai tables: phrases override characters, and character mappings are
     * merged with kJapaneseOldVariant (OpenCC's order first, since it lists the usual form first).
     * Side effects: fills phrases and extends oldForms; records the disagreements in the stats.
     */
    private void loadOpenCc(Path dir) throws IOException {
        for (String line : Files.readAllLines(dir.resolve("JPShinjitaiPhrases.txt"))) {
            String[] f = line.split("\t");
            if (f.length == 2 && !line.startsWith("#")) {
                phrases.put(f[0], f[1].split(" ")[0]);
                longestPhrase = Math.max(longestPhrase, f[0].length());
            }
        }
        List<String> differ = new ArrayList<>();
        int onlyOpenCc = 0;
        for (String line : Files.readAllLines(dir.resolve("JPShinjitaiCharacters.txt"))) {
            String[] f = line.split("\t");
            if (f.length != 2 || line.startsWith("#")) {
                continue;
            }
            int cp = f[0].codePointAt(0);
            LinkedHashSet<Integer> merged = new LinkedHashSet<>(oldVariants(cp, f[1].split(" ")));
            if (merged.isEmpty()) {
                continue;
            }
            List<Integer> unihan = oldForms.get(cp);
            if (unihan == null) {
                onlyOpenCc++;
            } else {
                if (!new HashSet<>(unihan).equals(merged)) {
                    differ.add(f[0] + ":" + f[1].replace(" ", "/") + "≠" + text(unihan));
                }
                merged.addAll(unihan);
            }
            oldForms.put(cp, List.copyOf(merged));
        }
        stats.put("opencc.phrases", phrases.size());
        stats.put("opencc.charsWithoutUnihanOldVariant", onlyOpenCc);
        stats.put("opencc.charsDisagreeingWithUnihan", differ);
    }

    /**
     * Parses old-form values ("U+FA19", "神") of character cp.
     * Returns: the old forms in order, without CJK compatibility ideographs that NFC folds back
     * to cp itself (神 → U+FA19 is a glyph distinction with no separate reading).
     */
    private static List<Integer> oldVariants(int cp, String[] values) {
        List<Integer> out = new ArrayList<>();
        for (String v : values) {
            int c = v.startsWith("U+") ? codePoint(v) : v.codePointAt(0);
            int folded = Normalizer.normalize(Character.toString(c), Normalizer.Form.NFC).codePointAt(0);
            if (folded != cp && !out.contains(folded)) {
                out.add(folded);
            }
        }
        return out;
    }

    /**
     * Reads CC-CEDICT into word → readings maps by traditional and by simplified form.
     * Side effects: fills cedictTrad, cedictSimp, cedictChar and cedictDate.
     * Notes: readings become lower-case numbered syllables with ü as v; entries whose reading has
     * anything else (xx5 placeholders, punctuation in names) are skipped. Entries that are only
     * surnames or variant pointers are left out of the word maps but still attest a single
     * character's reading (渕 "variant of 淵" → yuan1).
     */
    private void loadCedict(Path zip) throws IOException {
        Pattern entry = Pattern.compile("^(\\S+) (\\S+) \\[([^]]*)] /(.*)/$");
        Pattern syllable = Pattern.compile("[a-z]+[1-5]");
        Pattern pointer = Pattern.compile("^(surname |(old |archaic |ancient )?variant of |used in |see )");
        int[] kept = {0};
        eachZipLine(zip, line -> {
            if (line.startsWith("#! date=")) {
                cedictDate = line.substring(8);
            }
            Matcher m = entry.matcher(line);
            if (line.startsWith("#") || !m.matches()) {
                return;
            }
            String syl = m.group(3).toLowerCase().replace("u:", "v");
            for (String s : syl.split(" ")) {
                if (!syllable.matcher(s).matches()) {
                    return;
                }
            }
            if (m.group(1).codePointCount(0, m.group(1).length()) == 1) {
                addReading(cedictChar, m.group(1), syl);
                addReading(cedictChar, m.group(2), syl);
            }
            boolean meaningful = false;
            for (String d : m.group(4).split("/")) {
                meaningful |= !pointer.matcher(d).find();
            }
            if (!meaningful) {
                return;
            }
            addReading(cedictTrad, m.group(1), syl);
            addReading(cedictSimp, m.group(2), syl);
            kept[0]++;
        });
        stats.put("cedict.date", cedictDate);
        stats.put("cedict.entriesUsed", kept[0]);
    }

    /** Appends a reading to a word's list once. */
    private static void addReading(Map<String, List<String>> map, String word, String syl) {
        List<String> list = map.computeIfAbsent(word, k -> new ArrayList<>(1));
        if (!list.contains(syl)) {
            list.add(syl);
        }
    }

    /**
     * Finds the Mozc POS id of the general noun, used for words Mozc does not have (§5.9).
     * Side effects: sets nounId.
     */
    private void loadNounId(Path idDef) throws IOException {
        for (String line : Files.readAllLines(idDef)) {
            if (line.endsWith(" 名詞,一般,*,*,*,*,*")) {
                nounId = Integer.parseInt(line.substring(0, line.indexOf(' ')));
            }
        }
        if (nounId < 0) {
            throw new IOException("id.def has no general noun");
        }
        stats.put("mozc.generalNounId", nounId);
    }

    // ---------------------------------------------------------------- pinyin → Japanese

    /**
     * Streams the Mozc dictionary: derives pinyin rows for every all-kanji entry and remembers
     * the POS ids and cost of the surface/reading pairs the English table wants.
     * Inputs: wanted holds "surface\treading" pairs; mozc receives the cheapest entry of each.
     * Side effects: fills pinyin and mozc; records coverage in the stats; writes
     * build/lexicon/pinyin-skipped.tsv (each skipped surface and its first unreadable character,
     * the worklist for manual mappings).
     */
    private void readMozc(List<Row> pinyin, Set<String> wanted, Map<String, MozcWord> mozc) throws IOException {
        long total = 0;
        long kanji = 0;
        long mapped = 0;
        Map<String, Integer> unreadable = new HashMap<>();
        Map<String, Integer> bySource = new TreeMap<>();
        Map<String, String> skippedSurfaces = new TreeMap<>();
        Set<String> kanjiSurfaces = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            try (BufferedReader in = Files.newBufferedReader(DEPS.resolve("mozc/dictionary0" + i + ".txt"))) {
                for (String line; (line = in.readLine()) != null; ) {
                    String[] f = line.split("\t");
                    if (f.length != 5) {
                        continue;
                    }
                    total++;
                    String reading = f[0];
                    String surface = f[4];
                    int lid = Integer.parseInt(f[1]);
                    int rid = Integer.parseInt(f[2]);
                    int cost = Integer.parseInt(f[3]);
                    String pair = surface + "\t" + reading;
                    if (wanted.contains(pair)) {
                        MozcWord old = mozc.get(pair);
                        if (old == null || cost < old.cost()) {
                            mozc.put(pair, new MozcWord(lid, rid, cost));
                        }
                    }
                    if (!allKanji(surface)) {
                        continue;
                    }
                    kanji++;
                    kanjiSurfaces.add(surface);
                    Derivation d = derived.computeIfAbsent(surface, this::derive);
                    if (d.readings().isEmpty()) {
                        unreadable.merge(d.unreadable(), 1, Integer::sum);
                        skippedSurfaces.put(surface, d.unreadable());
                        continue;
                    }
                    mapped++;
                    bySource.merge(d.source(), 1, Integer::sum);
                    for (Reading r : d.readings()) {
                        pinyin.add(new Row(r.syllables().replaceAll("[1-5 ]", ""), r.syllables(), surface, reading,
                                lid, rid, cost + r.penalty(), d.source()));
                    }
                }
            }
        }
        List<Row> deduped = dedupe(pinyin);
        pinyin.clear();
        pinyin.addAll(deduped);
        stats.put("mozc.entries", total);
        stats.put("pinyin.allKanjiEntries", kanji);
        stats.put("pinyin.mappedEntries", mapped);
        stats.put("pinyin.entryCoverage", ratio(mapped, kanji));
        stats.put("pinyin.allKanjiSurfaces", kanjiSurfaces.size());
        stats.put("pinyin.skippedSurfaces", skippedSurfaces.size());
        Files.createDirectories(OUT);
        try (Writer w = Files.newBufferedWriter(OUT.resolve("pinyin-skipped.tsv"))) {
            w.write("surface\tunreadable\n");
            for (Map.Entry<String, String> e : skippedSurfaces.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue() + "\n");
            }
        }
        stats.put("pinyin.entriesBySource", bySource);
        stats.put("pinyin.unreadableCharsTop", top(unreadable, 30));
        stats.put("pinyin.rows", pinyin.size());
        stats.put("pinyin.keys", pinyin.stream().map(Row::key).distinct().count());
    }

    /** Returns whether every character is a Han ideograph, 々 counting after the first. */
    private static boolean allKanji(String s) {
        if (s.isEmpty() || s.codePointAt(0) == '々') {
            return false;
        }
        return s.codePoints().allMatch(c -> c == '々'
                || (Character.isIdeographic(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN));
    }

    /**
     * Derives the Chinese readings of one all-kanji Japanese surface (§5.9).
     * Returns: up to MAX_READINGS readings with penalties and their source ("cedict" or
     * "unihan", "+glyph" when the surface has old-form spellings), or no readings and the first
     * character that has no attested Chinese reading.
     * Notes: steps are 々 expansion → old forms (OpenCC phrases, then per character the merged
     * OpenCC/kJapaneseOldVariant forms) → simplified forms → CC-CEDICT word readings by
     * traditional, then simplified spelling → otherwise per-character Unihan readings with
     * polyphones expanded at ALT_READING each.
     */
    private Derivation derive(String surface) {
        int[] cps = surface.codePoints().toArray();
        for (int i = 1; i < cps.length; i++) {
            if (cps[i] == '々') {
                cps[i] = cps[i - 1];
            }
        }
        String word = new String(cps, 0, cps.length);
        List<int[]> forms = new ArrayList<>();
        List<Integer> formPenalty = new ArrayList<>();
        oldFormSpellings(word, forms, formPenalty, true);
        boolean glyph = forms.stream().anyMatch(f -> !new String(f, 0, f.length).equals(word));

        Map<String, Integer> found = new LinkedHashMap<>();
        for (int i = 0; i < forms.size(); i++) {
            int[] trad = forms.get(i);
            String t = new String(trad, 0, trad.length);
            List<String> rs = cedictTrad.get(t);
            if (rs == null) {
                rs = cedictSimp.get(simplify(trad));
            }
            if (rs != null) {
                for (String r : rs) {
                    found.merge(r, formPenalty.get(i), Math::min);
                }
            }
        }
        String suffix = glyph ? "+glyph" : "";
        if (!found.isEmpty()) {
            // Several CC-CEDICT readings: the one agreeing with the primary character readings
            // of the first spelling ranks first.
            String primary = primaryReading(forms.get(0));
            List<Reading> out = new ArrayList<>();
            found.forEach((r, p) -> out.add(new Reading(r, p + (r.equals(primary) || found.size() == 1 ? 0 : ALT_READING))));
            // An OpenCC phrase may give a historical spelling with its own reading (世論 → 輿論
            // yúlùn); the reading of the characters as written (shìlùn) is kept as well, one step
            // costlier, so both keys find the word (user decision, 2026-11-04).
            List<int[]> literal = new ArrayList<>();
            oldFormSpellings(word, literal, new ArrayList<>(), false);
            String asWritten = primaryReading(literal.get(0));
            // Only a phrase that brings in another character counts (世 → 輿, 転 → 顛); one that
            // picks among a character's own old forms (花弁 → 花瓣) is no historical spelling.
            int[] spelled = forms.get(0);
            boolean historical = false;
            for (int k = 0; spelled.length == cps.length && k < cps.length; k++) {
                historical |= spelled[k] != cps[k] && !glyphSlot(cps[k]).contains(spelled[k]);
            }
            if (historical && !asWritten.isEmpty() && !found.containsKey(asWritten)) {
                out.add(new Reading(asWritten, ALT_READING));
            }
            return new Derivation(cap(out), "cedict" + suffix, null);
        }
        Map<String, Integer> perChar = new LinkedHashMap<>();
        String unreadable = null;
        for (int i = 0; i < forms.size(); i++) {
            List<Reading> rs = new ArrayList<>(List.of(new Reading("", PER_CHAR + formPenalty.get(i))));
            for (int c : forms.get(i)) {
                List<String> options = charReadings(c);
                if (options.isEmpty()) {
                    unreadable = unreadable == null ? Character.toString(original(word, forms.get(i), c)) : unreadable;
                    rs = List.of();
                    break;
                }
                List<Reading> next = new ArrayList<>();
                for (Reading r : rs) {
                    for (int k = 0; k < options.size(); k++) {
                        next.add(new Reading(r.syllables().isEmpty() ? options.get(k) : r.syllables() + " " + options.get(k),
                                r.penalty() + (k == 0 ? 0 : ALT_READING)));
                    }
                }
                rs = cap(next);
            }
            for (Reading r : rs) {
                perChar.merge(r.syllables(), r.penalty(), Math::min);
            }
        }
        if (perChar.isEmpty()) {
            return new Derivation(List.of(), null, unreadable);
        }
        List<Reading> out = new ArrayList<>();
        perChar.forEach((r, p) -> out.add(new Reading(r, p)));
        return new Derivation(cap(out), "unihan" + suffix, null);
    }

    /**
     * Lists the old-form spellings of a word, cheapest first.
     * Inputs: forms and penalties receive parallel entries; usePhrases false spells each character
     * on its own, ignoring the OpenCC phrases.
     * Notes: within an OpenCC phrase (longest match first) a changed character takes the phrase's
     * form first; every character otherwise expands to each of its old forms that has a Chinese
     * reading, the n-th alternative costing n × ALT_GLYPH; a character without such old forms
     * stays as it is (萌 → 萠 has no reading, so 萌 stays). At most MAX_GLYPH_FORMS spellings are
     * kept.
     */
    private void oldFormSpellings(String word, List<int[]> forms, List<Integer> penalties, boolean usePhrases) {
        List<List<Integer>> slots = new ArrayList<>();
        int i = 0;
        while (i < word.length()) {
            String hit = null;
            int len = usePhrases ? Math.min(longestPhrase, word.length() - i) : 0;
            while (len >= 2 && (hit = phrases.get(word.substring(i, i + len))) == null) {
                len--;
            }
            if (hit != null) {
                String from = word.substring(i, i + len);
                boolean aligned = hit.length() == from.length();
                for (int k = 0; k < hit.length(); k++) {
                    // OpenCC phrases may keep a new form they do not change (回転 → 廻転), so an
                    // unchanged character still gets its own old forms; a changed one keeps its
                    // own old forms as costlier alternatives (転倒 → 顛倒, then 轉倒).
                    int c = hit.charAt(k);
                    List<Integer> slot = new ArrayList<>();
                    if (!aligned || c != from.charAt(k)) {
                        slot.add(c);
                    }
                    if (aligned) {
                        glyphSlot(from.charAt(k)).forEach(o -> {
                            if (!slot.contains(o)) {
                                slot.add(o);
                            }
                        });
                    }
                    slots.add(slot);
                }
                i += len;
                continue;
            }
            int c = word.codePointAt(i);
            slots.add(glyphSlot(c));
            i += Character.charCount(c);
        }
        record Spelling(int[] cps, int penalty) {}
        List<Spelling> acc = List.of(new Spelling(new int[0], 0));
        for (List<Integer> slot : slots) {
            List<Spelling> next = new ArrayList<>();
            for (Spelling a : acc) {
                for (int k = 0; k < slot.size(); k++) {
                    int[] f = Arrays.copyOf(a.cps(), a.cps().length + 1);
                    f[f.length - 1] = slot.get(k);
                    next.add(new Spelling(f, a.penalty() + k * ALT_GLYPH));
                }
            }
            acc = next.stream().sorted(Comparator.comparingInt(Spelling::penalty)).limit(MAX_GLYPH_FORMS).toList();
        }
        for (Spelling s : acc) {
            forms.add(s.cps());
            penalties.add(s.penalty());
        }
    }

    /** Lists a character's old forms that have a Chinese reading, or the character itself. */
    private List<Integer> glyphSlot(int c) {
        List<Integer> readable = oldForms.getOrDefault(c, List.of()).stream()
                .filter(o -> !charReadings(o).isEmpty()).toList();
        return readable.isEmpty() ? List.of(c) : readable;
    }

    /**
     * Lists the attested Chinese readings of one (old-form) character, primary first.
     * Notes: tries Unihan for its simplified form, then for the character itself, then the
     * CC-CEDICT single-character readings of both; empty when none attests a reading.
     */
    private List<String> charReadings(int c) {
        int s = simplified.getOrDefault(c, c);
        List<String> r = dictReadings.get(s);
        if (r == null) {
            r = dictReadings.get(c);
        }
        if (r == null) {
            r = cedictChar.get(Character.toString(s));
        }
        if (r == null) {
            r = cedictChar.get(Character.toString(c));
        }
        return r == null ? List.of() : r;
    }

    /** Returns the per-character primary readings of a spelling, or "" when one is missing. */
    private String primaryReading(int[] form) {
        StringBuilder b = new StringBuilder();
        for (int c : form) {
            List<String> r = charReadings(c);
            if (r.isEmpty()) {
                return "";
            }
            b.append(b.isEmpty() ? "" : " ").append(r.get(0));
        }
        return b.toString();
    }

    /** Maps an old-form spelling to simplified characters with kSimplifiedVariant. */
    private String simplify(int[] form) {
        StringBuilder b = new StringBuilder();
        for (int c : form) {
            b.appendCodePoint(simplified.getOrDefault(c, c));
        }
        return b.toString();
    }

    /** Returns the Japanese character a substituted character came from, for the skip report. */
    private static int original(String word, int[] form, int c) {
        int[] w = word.codePoints().toArray();
        for (int i = 0; i < form.length && i < w.length; i++) {
            if (form[i] == c) {
                return w[i];
            }
        }
        return c;
    }

    /** Keeps the MAX_READINGS cheapest readings. */
    private static List<Reading> cap(List<Reading> rs) {
        return rs.stream().sorted(Comparator.comparingInt(Reading::penalty)).limit(MAX_READINGS).toList();
    }

    /**
     * Converts a tone-marked pinyin syllable ("yún", "lǜ") to the numbered form ("yun2", "lv4").
     * Notes: an unmarked syllable gets tone 5; ü becomes v.
     */
    static String numbered(String syllable) {
        String d = Normalizer.normalize(syllable.toLowerCase(), Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder();
        int tone = 5;
        for (int i = 0; i < d.length(); i++) {
            char ch = d.charAt(i);
            switch (ch) {
                case '̄' -> tone = 1;
                case '́' -> tone = 2;
                case '̌' -> tone = 3;
                case '̀' -> tone = 4;
                case '̈' -> {
                    if (!b.isEmpty() && b.charAt(b.length() - 1) == 'u') {
                        b.setCharAt(b.length() - 1, 'v');
                    }
                }
                default -> {
                    if (ch >= 'a' && ch <= 'z') {
                        b.append(ch);
                    }
                }
            }
        }
        return b.append(tone).toString();
    }

    // ---------------------------------------------------------------- English → Japanese

    /**
     * Streams JMdict (jmdict-simplified JSON) and emits English-key rows in three classes:
     * "gairaigo" (katakana loanwords), "semantic" (kanji or kana words whose gloss is the key,
     * school → 学校) and "translit" (keyed by the loanword's English source word when no gloss
     * says it, ranked last by TRANSLIT).
     * Inputs: en receives rows without Mozc data yet; wanted receives each surface/reading pair.
     * Notes: explanation and literal glosses are skipped; rare, irregular, outdated and
     * search-only forms are skipped; jmdict-simplified folds the priority tags (news1, ichi1,
     * spec1/2, gai1) into one "common" flag, which is what NOT_COMMON reads.
     */
    @SuppressWarnings("unchecked")
    private void readJmdict(Path zip, List<Row> en, Set<String> wanted) throws IOException {
        Set<String> badKanji = Set.of("rK", "sK", "iK", "oK");
        Set<String> badKana = Set.of("rk", "sk", "ik", "ok");
        int[] words = {0};
        int[] covered = {0};
        int[] commonWords = {0};
        int[] commonCovered = {0};
        try (InputStream raw = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(raw)) {
            ZipEntry e = z.getNextEntry();
            if (e == null) {
                throw new IOException(zip + " is empty");
            }
            Json json = new Json(new InputStreamReader(z, StandardCharsets.UTF_8));
            json.eachWord(w -> {
                words[0]++;
                int before = en.size();
                List<Map<String, Object>> kanji = (List<Map<String, Object>>) w.get("kanji");
                List<Map<String, Object>> kana = (List<Map<String, Object>>) w.get("kana");
                List<Map<String, Object>> senses = (List<Map<String, Object>>) w.get("sense");
                boolean common = kanji.stream().anyMatch(k -> Boolean.TRUE.equals(k.get("common")))
                        || kana.stream().anyMatch(k -> Boolean.TRUE.equals(k.get("common")));
                String source = "jmdict:" + w.get("id");
                Set<String> glossKeys = new HashSet<>();
                for (int si = 0; si < senses.size(); si++) {
                    Map<String, Object> s = senses.get(si);
                    List<String> misc = (List<String>) s.get("misc");
                    boolean verb = ((List<String>) s.get("partOfSpeech")).stream().anyMatch(p -> p.startsWith("v"));
                    boolean uk = misc.contains("uk");
                    int sensePenalty = SENSE_STEP * Math.min(si, 6)
                            + (misc.contains("arch") || misc.contains("obs") || misc.contains("rare") ? ARCHAIC : 0)
                            + (misc.contains("abbr") ? ABBREVIATION : 0);
                    List<String[]> surfaces = new ArrayList<>();
                    List<Integer> surfacePenalty = new ArrayList<>();
                    for (Map<String, Object> k : kanji) {
                        List<String> tags = (List<String>) k.get("tags");
                        String kt = (String) k.get("text");
                        if (tags.stream().anyMatch(badKanji::contains) || !applies(s.get("appliesToKanji"), kt)) {
                            continue;
                        }
                        String reading = null;
                        for (Map<String, Object> r : kana) {
                            String rt = (String) r.get("text");
                            if (((List<String>) r.get("tags")).stream().noneMatch(badKana::contains)
                                    && applies(r.get("appliesToKanji"), kt) && applies(s.get("appliesToKana"), rt)) {
                                reading = hiragana(rt);
                                break;
                            }
                        }
                        if (reading == null) {
                            continue;
                        }
                        surfaces.add(new String[] {kt, reading, katakana(kt) ? "gairaigo" : "semantic"});
                        surfacePenalty.add((Boolean.TRUE.equals(k.get("common")) ? 0 : NOT_COMMON)
                                + (uk ? USUALLY_KANA : 0) + (tags.contains("ateji") ? ATEJI : 0));
                    }
                    boolean loan = !((List<Object>) s.get("languageSource")).isEmpty();
                    for (Map<String, Object> r : kana) {
                        String rt = (String) r.get("text");
                        if (((List<String>) r.get("tags")).stream().anyMatch(badKana::contains) || !applies(s.get("appliesToKana"), rt)) {
                            continue;
                        }
                        int p = Boolean.TRUE.equals(r.get("common")) ? 0 : NOT_COMMON;
                        if (katakana(rt)) {
                            boolean gairaigo = kanji.isEmpty() || loan;
                            surfaces.add(new String[] {rt, hiragana(rt), gairaigo ? "gairaigo" : "semantic"});
                            surfacePenalty.add(p + (gairaigo ? 0 : KANA_OF_KANJI_WORD));
                        } else if (kanji.isEmpty() || uk) {
                            surfaces.add(new String[] {rt, rt, "semantic"});
                            surfacePenalty.add(p);
                        }
                    }
                    List<Map<String, Object>> glosses = (List<Map<String, Object>>) s.get("gloss");
                    for (int gi = 0; gi < glosses.size(); gi++) {
                        Map<String, Object> g = glosses.get(gi);
                        Object type = g.get("type");
                        if ("explanation".equals(type) || "literal".equals(type)) {
                            continue;
                        }
                        String text = (String) g.get("text");
                        String key = englishKey(text, verb);
                        if (key == null) {
                            continue;
                        }
                        glossKeys.add(key);
                        int p = sensePenalty + GLOSS_STEP * Math.min(gi, 6) + (text.contains("(") ? PAREN_GLOSS : 0)
                                + (common ? 0 : NOT_COMMON);
                        for (int k = 0; k < surfaces.size(); k++) {
                            String[] sf = surfaces.get(k);
                            en.add(new Row(key, sf[2], sf[0], sf[1], -1, -1, p + surfacePenalty.get(k), source));
                            wanted.add(sf[0] + "\t" + sf[1]);
                        }
                    }
                }
                for (Map<String, Object> s : senses) {
                    for (Map<String, Object> ls : (List<Map<String, Object>>) s.get("languageSource")) {
                        String key = ls.get("text") instanceof String t && "eng".equals(ls.get("lang")) ? englishKey(t, false) : null;
                        if (key == null || glossKeys.contains(key)) {
                            continue;
                        }
                        for (Map<String, Object> r : kana) {
                            String rt = (String) r.get("text");
                            if (katakana(rt) && ((List<String>) r.get("tags")).stream().noneMatch(badKana::contains)) {
                                en.add(new Row(key, "translit", rt, hiragana(rt), -1, -1,
                                        TRANSLIT + (common ? 0 : NOT_COMMON), source));
                                wanted.add(rt + "\t" + hiragana(rt));
                            }
                        }
                    }
                }
                if (en.size() > before) {
                    covered[0]++;
                }
                if (common) {
                    commonWords[0]++;
                    if (en.size() > before) {
                        commonCovered[0]++;
                    }
                }
            });
        }
        stats.put("jmdict.words", words[0]);
        stats.put("en.coveredWords", covered[0]);
        stats.put("en.wordCoverage", ratio(covered[0], words[0]));
        stats.put("en.commonWords", commonWords[0]);
        stats.put("en.commonWordCoverage", ratio(commonCovered[0], commonWords[0]));
    }

    /** Returns whether an appliesTo list ("*" or explicit forms) includes form. */
    @SuppressWarnings("unchecked")
    private static boolean applies(Object list, String form) {
        List<String> l = (List<String>) list;
        return l.contains("*") || l.contains(form);
    }

    private static final Pattern PAREN = Pattern.compile("\\([^)]*\\)");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9' -]*");

    /**
     * Normalizes an English gloss to a lookup key: lower case, parentheses removed, a leading
     * "to " (verbs) or article removed, at most three words, then spaces, hyphens and apostrophes
     * dropped, since keys are typed without them ("credit card" → creditcard).
     * Returns: the key, or null when the gloss is not a short plain-letter phrase.
     */
    static String englishKey(String gloss, boolean verb) {
        String s = PAREN.matcher(gloss.toLowerCase()).replaceAll(" ").trim().replaceAll("\\s+", " ");
        if (verb && s.startsWith("to ")) {
            s = s.substring(3);
        }
        for (String article : List.of("a ", "an ", "the ")) {
            if (s.startsWith(article)) {
                s = s.substring(article.length());
            }
        }
        if (!KEY.matcher(s).matches() || s.split(" ").length > 3) {
            return null;
        }
        String key = s.replaceAll("[ '-]", "");
        return key.isEmpty() ? null : key;
    }

    /** Returns whether s is written in katakana only (with ー and ・), containing a letter. */
    private static boolean katakana(String s) {
        return s.codePoints().allMatch(c -> c >= 0x30A0 && c <= 0x30FF)
                && s.codePoints().anyMatch(c -> c >= 0x30A1 && c <= 0x30FA);
    }

    /** Maps katakana to hiragana, the script of Mozc readings; other characters stay. */
    private static String hiragana(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(c -> b.appendCodePoint(c >= 0x30A1 && c <= 0x30F6 ? c - 0x60 : c));
        return b.toString();
    }

    /**
     * Gives each English row the POS ids and cost of its Mozc entry, or the general noun and
     * NO_MOZC_COST when Mozc lacks the surface/reading pair or the surface is hiragana (§5.9).
     * Returns: the deduplicated rows; records Mozc match and class counts in the stats.
     */
    private List<Row> joinMozc(List<Row> en, Map<String, MozcWord> mozc) {
        List<Row> joined = new ArrayList<>(en.size());
        for (Row r : en) {
            MozcWord m = r.surface().equals(r.reading()) ? null : mozc.get(r.surface() + "\t" + r.reading());
            joined.add(m == null
                    ? new Row(r.key(), r.detail(), r.surface(), r.reading(), nounId, nounId, NO_MOZC_COST + r.cost(), r.source())
                    : new Row(r.key(), r.detail(), r.surface(), r.reading(), m.lid(), m.rid(), m.cost() + r.cost(), r.source() + "+mozc"));
        }
        List<Row> out = dedupe(joined);
        Map<String, Integer> classes = new TreeMap<>();
        long inMozc = out.stream().filter(r -> r.source().endsWith("+mozc")).count();
        out.forEach(r -> classes.merge(r.detail(), 1, Integer::sum));
        stats.put("en.rows", out.size());
        stats.put("en.keys", out.stream().map(Row::key).distinct().count());
        stats.put("en.rowsByClass", classes);
        stats.put("en.rowsInMozc", ratio(inMozc, out.size()));
        return out;
    }

    // ---------------------------------------------------------------- output

    /**
     * Keeps the cheapest row per key, surface and reading, sorted by key, cost and surface,
     * the order a runtime sorted-array index needs.
     */
    private static List<Row> dedupe(List<Row> rows) {
        Map<String, Row> best = new HashMap<>();
        for (Row r : rows) {
            best.merge(r.key() + "\t" + r.surface() + "\t" + r.reading(), r, (a, b) -> b.cost() < a.cost() ? b : a);
        }
        List<Row> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparing(Row::key).thenComparingInt(Row::cost).thenComparing(Row::surface).thenComparing(Row::reading));
        return out;
    }

    /** Writes rows as UTF-8 TSV with the given header. Side effects: replaces the file. */
    private static void write(Path file, String header, List<Row> rows) throws IOException {
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write(header + "\n");
            for (Row r : rows) {
                w.write(r.key() + "\t" + r.detail() + "\t" + r.surface() + "\t" + r.reading() + "\t" + r.lid() + "\t"
                        + r.rid() + "\t" + r.cost() + "\t" + r.source() + "\n");
            }
        }
    }

    /**
     * Writes metadata.json: generator, sources (version, license, files with SHA-256), the
     * licenses of the outputs and the coverage stats.
     */
    private void writeMetadata(Map<String, String> hashes) throws IOException {
        StringBuilder b = new StringBuilder("{\n  \"format\": 1,\n  \"generator\": \"lexicon/CrossLexicon.java\",\n");
        b.append("  \"outputs\": {\n");
        b.append("    \"pinyin-ja.tsv\": {\"license\": \"CC-BY-SA-4.0 (CC-CEDICT readings) with the Mozc dictionary, Unihan and OpenCC notices\"},\n");
        b.append("    \"en-ja.tsv\": {\"license\": \"CC-BY-SA-4.0 (JMdict, EDRDG attribution required) with the Mozc dictionary notices\"}\n  },\n");
        b.append("  \"sources\": [\n");
        for (int i = 0; i < SOURCES.size(); i++) {
            Source s = SOURCES.get(i);
            b.append("    {\"id\": ").append(quote(s.id())).append(", \"version\": ").append(quote(s.version()))
                    .append(", \"license\": ").append(quote(s.license())).append(", \"home\": ").append(quote(s.home()))
                    .append(", \"files\": [");
            for (int k = 0; k < s.files().size(); k++) {
                Remote r = s.files().get(k);
                String path = slash(DEPS.resolve(s.id()).resolve(r.file()));
                b.append(k == 0 ? "\n" : ",\n").append("      {\"url\": ").append(quote(r.url()))
                        .append(", \"sha256\": ").append(quote(hashes.get(path))).append("}");
            }
            b.append("]}").append(i + 1 < SOURCES.size() ? ",\n" : "\n");
        }
        b.append("  ],\n  \"stats\": {\n");
        int n = 0;
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            b.append("    ").append(quote(e.getKey())).append(": ").append(jsonValue(e.getValue()))
                    .append(++n < stats.size() ? ",\n" : "\n");
        }
        b.append("  }\n}\n");
        Files.writeString(OUT.resolve("metadata.json"), b);
    }

    /** Renders a stats value (number, string, list or map) as JSON. */
    private static String jsonValue(Object v) {
        if (v instanceof Number) {
            return v.toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder b = new StringBuilder("[");
            l.forEach(x -> b.append(b.length() > 1 ? ", " : "").append(jsonValue(x)));
            return b.append("]").toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            m.forEach((k, x) -> b.append(b.length() > 1 ? ", " : "").append(quote(k.toString())).append(": ").append(jsonValue(x)));
            return b.append("}").toString();
        }
        return quote(String.valueOf(v));
    }

    /** Quotes a string as a JSON literal. */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Lists the n most frequent keys as "key count" strings. */
    private static List<String> top(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(n)
                .map(e -> e.getKey() + " " + e.getValue()).toList();
    }

    /** Formats part/whole as a fraction with four decimals. */
    private static Double ratio(long part, long whole) {
        return whole == 0 ? 0.0 : Math.round(10000.0 * part / whole) / 10000.0;
    }

    // ---------------------------------------------------------------- helpers

    /** Feeds every line of every file in a zip archive to sink, as UTF-8. */
    private static void eachZipLine(Path zip, Consumer<String> sink) throws IOException {
        try (InputStream raw = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(raw)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                BufferedReader in = new BufferedReader(new InputStreamReader(z, StandardCharsets.UTF_8));
                for (String line; (line = in.readLine()) != null; ) {
                    sink.accept(line);
                }
            }
        }
    }

    /** Parses "U+85DD" (with an optional "<source" suffix) to a code point. */
    private static int codePoint(String v) {
        int end = v.indexOf('<');
        return Integer.parseInt((end < 0 ? v : v.substring(0, end)).substring(2), 16);
    }

    /** Renders code points as a slash-separated string. */
    private static String text(List<Integer> cps) {
        StringBuilder b = new StringBuilder();
        cps.forEach(c -> b.append(b.isEmpty() ? "" : "/").appendCodePoint(c));
        return b.toString();
    }

    /** Hashes a file with SHA-256. Returns: lower-case hex. */
    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Formats a path with '/' separators. */
    private static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    /**
     * A minimal streaming JSON reader for jmdict-simplified: walks the top-level object and hands
     * each element of its "words" array, fully parsed, to a consumer.
     * Notes: objects become LinkedHashMap, arrays ArrayList, numbers Double; input is trusted
     * only as far as its pinned hash, so malformed JSON fails with an IOException.
     */
    private static final class Json {
        private final Reader in;
        private final char[] buf = new char[1 << 16];
        private int pos;
        private int len;

        Json(Reader in) {
            this.in = in;
        }

        /** Streams the "words" array of the top-level object to sink; other members are skipped. */
        void eachWord(Consumer<Map<String, Object>> sink) throws IOException {
            expect('{');
            while (true) {
                String name = string();
                expect(':');
                if (name.equals("words")) {
                    expect('[');
                    if (peek() == ']') {
                        next();
                    } else {
                        while (true) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> w = (Map<String, Object>) value();
                            sink.accept(w);
                            if (sep(']')) {
                                break;
                            }
                        }
                    }
                } else {
                    value();
                }
                if (sep('}')) {
                    return;
                }
            }
        }

        /** Parses any JSON value. */
        private Object value() throws IOException {
            int c = peek();
            switch (c) {
                case '{' -> {
                    next();
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (peek() == '}') {
                        next();
                        return m;
                    }
                    do {
                        String k = string();
                        expect(':');
                        m.put(k, value());
                    } while (!sep('}'));
                    return m;
                }
                case '[' -> {
                    next();
                    List<Object> l = new ArrayList<>();
                    if (peek() == ']') {
                        next();
                        return l;
                    }
                    do {
                        l.add(value());
                    } while (!sep(']'));
                    return l;
                }
                case '"' -> {
                    return string();
                }
                case 't' -> {
                    word("true");
                    return Boolean.TRUE;
                }
                case 'f' -> {
                    word("false");
                    return Boolean.FALSE;
                }
                case 'n' -> {
                    word("null");
                    return null;
                }
                default -> {
                    StringBuilder b = new StringBuilder();
                    while ("+-0123456789.eE".indexOf(peekRaw()) >= 0) {
                        b.append((char) next());
                    }
                    if (b.isEmpty()) {
                        throw new IOException("json: unexpected " + (char) c);
                    }
                    return Double.parseDouble(b.toString());
                }
            }
        }

        /** Parses a string literal, with escapes. */
        private String string() throws IOException {
            expect('"');
            StringBuilder b = new StringBuilder();
            for (int c; (c = next()) != '"'; ) {
                if (c < 0) {
                    throw new IOException("json: unterminated string");
                }
                if (c == '\\') {
                    int e = next();
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            char[] h = new char[4];
                            for (int i = 0; i < 4; i++) {
                                h[i] = (char) next();
                            }
                            b.append((char) Integer.parseInt(new String(h), 16));
                        }
                        default -> b.append((char) e);
                    }
                } else {
                    b.append((char) c);
                }
            }
            return b.toString();
        }

        /** Consumes a ',' (returns false) or the closing character (returns true). */
        private boolean sep(char close) throws IOException {
            int c = peek();
            next();
            if (c == ',') {
                return false;
            }
            if (c == close) {
                return true;
            }
            throw new IOException("json: expected , or " + close + " but got " + (char) c);
        }

        private void word(String w) throws IOException {
            for (int i = 0; i < w.length(); i++) {
                if (next() != w.charAt(i)) {
                    throw new IOException("json: bad literal");
                }
            }
        }

        private void expect(char c) throws IOException {
            if (peek() != c) {
                throw new IOException("json: expected " + c + " but got " + (char) peek());
            }
            next();
        }

        /** Returns the next non-space character without consuming it. */
        private int peek() throws IOException {
            while (Character.isWhitespace(peekRaw())) {
                next();
            }
            return peekRaw();
        }

        private int peekRaw() throws IOException {
            if (pos == len) {
                len = in.read(buf, 0, buf.length);
                pos = 0;
                if (len <= 0) {
                    len = 0;
                    return -1;
                }
            }
            return buf[pos];
        }

        private int next() throws IOException {
            int c = peekRaw();
            if (c >= 0) {
                pos++;
            }
            return c;
        }
    }
}
