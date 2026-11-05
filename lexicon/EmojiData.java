import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds the emoji asset of P1 (PLAN.md §4.1, §8.6, §10.6): every fully-qualified emoji of the
 * latest Unicode Emoji release in keyboard order, grouped, with its skin-tone variants, and a
 * keyword index in English, Chinese (plus pinyin) and Japanese (plus kana and romaji) from the
 * CLDR annotations. Run from the repository root:
 * {@code java lexicon/EmojiData.java [--offline] [output.tsv]}.
 *
 * <p>Inputs: the pinned sources in {@link #SOURCES}, kept under {@code .deps/<source>/}; missing
 * files are downloaded unless {@code --offline} is given, and every file must match its pinned
 * SHA-256. Output (build output, never committed): {@code build/emoji/emoji.tsv} unless another
 * path is given. Its lines, tab-separated:
 * <ul>
 *   <li>{@code #kotomo-emoji 1 <emoji version> <cldr version>}: format header;</li>
 *   <li>{@code g <group>}: starts a group (smileys, people, animals, food, travel, activities,
 *       objects, symbols, flags; Unicode's "Component" group is left out);</li>
 *   <li>{@code e <emoji> [<variant> ...]}: one base emoji of the current group, with its
 *       skin-tone variants separated by spaces; emoji are numbered from 0 in file order;</li>
 *   <li>{@code k <key> <id>,<id>,...}: one lookup key and the emoji it finds, best first; keys
 *       are sorted by {@link String#compareTo} so the reader can binary-search them.</li>
 * </ul>
 *
 * <p>Notes: exit status 0 on success, 1 when a source is missing, cannot be fetched or does not
 * match its pin, 2 on a read or write error. Pinyin: a keyword is split greedily into the longest
 * CC-CEDICT words (word readings settle polyphones: 银行 yinhang, 快乐 kuaile); a single character
 * outside any word takes its first Unihan kMandarin reading; tones are dropped and ü is v. Japanese
 * readings: kana stays as it is; kanji runs take the lowest-cost reading of the longest Mozc
 * dictionary surface; romaji is Hepburn without long-vowel marks (ー is dropped).
 */
public final class EmojiData {
    private static final Path DEPS = Path.of(".deps");
    private static final Path DEFAULT_OUT = Path.of("build", "emoji", "emoji.tsv");

    /** One downloadable file: where it comes from, its name under .deps/<source>/, its pin. */
    private record Remote(String url, String file, String sha256) {}

    /** One pinned data source and the files the pipeline reads from it. */
    private record Source(String id, String version, List<Remote> files) {}

    private static final String EMOJI_VERSION = "18.0";
    private static final String CLDR_VERSION = "49.0.0";
    private static final String CLDR_RAW = "https://raw.githubusercontent.com/unicode-org/cldr-json/a4b67247b446fd51b475991017a2481911210c60/cldr-json/";
    private static final String MOZC_RAW = "https://raw.githubusercontent.com/google/mozc/3.35.6354/src/data/dictionary_oss/";

    /** The CLDR locales read, in the order their keys are added. */
    private static final List<String> LOCALES = List.of("en", "zh", "zh-Hant", "ja");

    /**
     * The pinned sources. Notes: CLDR is pinned to the commit of cldr-json's 49.0.0 tag; Unihan,
     * CC-CEDICT and Mozc share the pins of lexicon/CrossLexicon.java.
     */
    private static final List<Source> SOURCES = List.of(
            new Source("unicode-emoji", EMOJI_VERSION, List.of(
                    new Remote("https://www.unicode.org/Public/18.0.0/emoji/emoji-test.txt", "emoji-test.txt",
                            "8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a"))),
            new Source("cldr", CLDR_VERSION, List.of(
                    new Remote(CLDR_RAW + "cldr-annotations-full/annotations/en/annotations.json", "annotations-en.json",
                            "6b6b6e0e5f211bc31cf3fa39f385d80dea6cd19ee19bbc6db9071fce3ae5d360"),
                    new Remote(CLDR_RAW + "cldr-annotations-full/annotations/zh/annotations.json", "annotations-zh.json",
                            "7d49968f977021fefe6ceea2613e0313de5ea416d8f3c0a8fe3032c9b7f2ebfb"),
                    new Remote(CLDR_RAW + "cldr-annotations-full/annotations/zh-Hant/annotations.json", "annotations-zh-Hant.json",
                            "38d8fc6cf4589cb55d24b0aa8becea2023ab83f3836b4c7a94ec8cacc66123e5"),
                    new Remote(CLDR_RAW + "cldr-annotations-full/annotations/ja/annotations.json", "annotations-ja.json",
                            "e42dacabf2eee0aab36c168c995efeaf8add896f8940c5ee86a809d85c2816e7"),
                    new Remote(CLDR_RAW + "cldr-annotations-derived-full/annotationsDerived/en/annotations.json", "annotationsDerived-en.json",
                            "7aad034f689cb6553f8d7366bfee8c4ea2818c7776907bc5118aab575223dea3"),
                    new Remote(CLDR_RAW + "cldr-annotations-derived-full/annotationsDerived/zh/annotations.json", "annotationsDerived-zh.json",
                            "1b86f6dd0b295e64efddaf6028151b6c5c95616b8c98c15f5727cff2b2429d48"),
                    new Remote(CLDR_RAW + "cldr-annotations-derived-full/annotationsDerived/zh-Hant/annotations.json", "annotationsDerived-zh-Hant.json",
                            "57c0deca0cd81c4551c0724c586539a6d6f5c8dfcc61f4d798197ffc868a9551"),
                    new Remote(CLDR_RAW + "cldr-annotations-derived-full/annotationsDerived/ja/annotations.json", "annotationsDerived-ja.json",
                            "3d83cf7e4093f7fe77055dc21b1a3a65cd8f48a0249127552d58d3b058dbe4cd"))),
            new Source("unihan", "18.0.0", List.of(
                    new Remote("https://www.unicode.org/Public/18.0.0/ucd/Unihan.zip", "Unihan.zip",
                            "4c93ea9c1f636451729a840978f1667a53886af37ba854fdcce109721c63d43e"))),
            new Source("cc-cedict", "2026-11-04T07:19:40Z", List.of(
                    new Remote("https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.zip", "cedict_1_0_ts_utf-8_mdbg.zip",
                            "594d6414c969dc143953bdcc2ae151069ae20354a20aacc1ac8b0edfcf273187"))),
            new Source("mozc", "3.35.6354", List.of(
                    new Remote(MOZC_RAW + "dictionary00.txt", "dictionary00.txt", "9e07ce292932c4fff327ed8cb06274c09acca556bd8bdf7c7ef1f6173eae3b61"),
                    new Remote(MOZC_RAW + "dictionary01.txt", "dictionary01.txt", "836fd5fd8399510de0d8ef7b33be913cca6bbaee7b712cc8045fba03f4c75a89"),
                    new Remote(MOZC_RAW + "dictionary02.txt", "dictionary02.txt", "93943858d0cfd092b849a78def6901eaf228177e1ac5663544e865a3a1e45a9e"),
                    new Remote(MOZC_RAW + "dictionary03.txt", "dictionary03.txt", "cb9f1b0b4a7550d682d86244a021cd17e0b15366328819e69135b5f183a1a24b"),
                    new Remote(MOZC_RAW + "dictionary04.txt", "dictionary04.txt", "9fdc4e3948d03463de13ab5782adffbc11dd06e934cb80138c5920029cd3d2f6"),
                    new Remote(MOZC_RAW + "dictionary05.txt", "dictionary05.txt", "199e3b0f050b691823ae7ba943090d437f51af6436b27c5f3dc69cafa12000e4"),
                    new Remote(MOZC_RAW + "dictionary06.txt", "dictionary06.txt", "b95b14f7770ae2a824bc8e9fad8b03c1bc45c504f93a36554b8b56daa0368d2a"),
                    new Remote(MOZC_RAW + "dictionary07.txt", "dictionary07.txt", "7f53649b529d4c8c495d3a8dd9c4d489dfe7696080f7423107ff01331174be48"),
                    new Remote(MOZC_RAW + "dictionary08.txt", "dictionary08.txt", "50a31bb5fc1646721b00be371a5695dffab1292bda143e1e59cc70cbbdd8643f"),
                    new Remote(MOZC_RAW + "dictionary09.txt", "dictionary09.txt", "4e5fcd4cbfbf11395166a37b506f547fc54d20edc0884f5184ecd3fc705f0d9a"))));

    /** Group ids by Unicode group name; Component is absent on purpose. */
    private static final Map<String, String> GROUPS = Map.of(
            "Smileys & Emotion", "smileys", "People & Body", "people", "Animals & Nature", "animals",
            "Food & Drink", "food", "Travel & Places", "travel", "Activities", "activities",
            "Objects", "objects", "Symbols", "symbols", "Flags", "flags");

    /** Key tiers: a whole CLDR keyword, a whole CLDR name, one word of a multi-word keyword or name. */
    private static final int KEYWORD = 0;
    private static final int NAME = 1;
    private static final int WORD = 2;

    /** English words too common to find an emoji by. */
    private static final Set<String> STOP = Set.of("a", "an", "and", "as", "at", "by", "for", "in", "of", "on",
            "or", "the", "to", "with", "without");

    /** Longest CC-CEDICT word or Mozc surface tried while segmenting a keyword. */
    private static final int MAX_WORD = 8;

    /** One base emoji: its sequence, group, the order it appears in, and its tone variants. */
    private record Emoji(String text, String group, List<String> variants) {}

    private final List<Emoji> emoji = new ArrayList<>();
    /** key → (emoji id → best tier). */
    private final Map<String, Map<Integer, Integer>> keys = new TreeMap<>();
    private final Map<Integer, String> mandarin = new HashMap<>();
    private final Map<String, String> cedict = new HashMap<>();
    private final Map<String, String> kanaOf = new HashMap<>();
    private final Map<String, Integer> kanaCost = new HashMap<>();
    private int unreadZh;
    private int unreadJa;

    /**
     * Fetches or verifies the sources, then writes the asset.
     * Side effects: may download into .deps/; writes the output file; exits with the status in the
     * class comment.
     */
    public static void main(String[] args) {
        boolean offline = List.of(args).contains("--offline");
        Path out = List.of(args).stream().filter(a -> !a.startsWith("--")).findFirst().map(Path::of).orElse(DEFAULT_OUT);
        List<String> problems = new ArrayList<>();
        for (Source s : SOURCES) {
            for (Remote r : s.files()) {
                ensure(s, r, offline, problems);
            }
        }
        if (!problems.isEmpty()) {
            problems.forEach(System.err::println);
            System.exit(1);
        }
        try {
            new EmojiData().build(out);
        } catch (IOException | UncheckedIOException e) {
            System.err.println("emoji: " + e);
            System.exit(2);
        }
    }

    /**
     * Makes sure one pinned file is present under .deps/ and matches its hash.
     * Inputs: offline forbids downloading; problems receives one line per failure.
     * Side effects: downloads to a .part file and moves it into place only when the hash matches.
     */
    private static void ensure(Source s, Remote r, boolean offline, List<String> problems) {
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
            }
        } catch (IOException e) {
            problems.add(slash(file) + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problems.add(slash(file) + ": interrupted");
        }
    }

    /**
     * Runs the pipeline over verified sources.
     * Side effects: writes the asset to out and prints its counts.
     */
    private void build(Path out) throws IOException {
        readEmojiTest(DEPS.resolve("unicode-emoji/emoji-test.txt"));
        readUnihan(DEPS.resolve("unihan/Unihan.zip"));
        readCedict(DEPS.resolve("cc-cedict/cedict_1_0_ts_utf-8_mdbg.zip"));
        Map<String, Map<String, Annotation>> cldr = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            Map<String, Annotation> merged = new HashMap<>();
            readAnnotations(DEPS.resolve("cldr/annotationsDerived-" + locale + ".json"), merged);
            readAnnotations(DEPS.resolve("cldr/annotations-" + locale + ".json"), merged);
            cldr.put(locale, merged);
        }
        readMozc(wantedJapanese(cldr.get("ja")));

        int missing = 0;
        for (int id = 0; id < emoji.size(); id++) {
            String e = emoji.get(id).text();
            boolean any = false;
            for (var entry : cldr.entrySet()) {
                Annotation a = entry.getValue().get(e);
                if (a == null) {
                    a = entry.getValue().get(e.replace("️", ""));
                }
                if (a == null) {
                    continue;
                }
                any = true;
                for (String k : a.keywords()) {
                    index(entry.getKey(), k, id, KEYWORD);
                }
                for (String n : a.names()) {
                    index(entry.getKey(), n, id, NAME);
                }
            }
            if (!any) {
                missing++;
            }
        }
        write(out);
        System.out.println("emoji: " + emoji.size() + " base emoji, "
                + emoji.stream().mapToInt(x -> x.variants().size()).sum() + " tone variants, "
                + keys.size() + " keys; " + missing + " emoji without annotations, "
                + unreadZh + " Chinese and " + unreadJa + " Japanese keywords without a reading");
    }

    // ---------------------------------------------------------------- emoji-test.txt

    private static final java.util.regex.Pattern TONE = java.util.regex.Pattern.compile("[\\x{1F3FB}-\\x{1F3FF}]");

    /**
     * Reads every fully-qualified emoji in order; a sequence with a skin-tone modifier
     * (U+1F3FB..U+1F3FF) becomes a variant of the base emoji before it.
     * Side effects: fills emoji.
     * Notes: emoji-test.txt lists each base emoji directly followed by all its toned forms; mixed
     * pairs have no untoned form of their own (👩🏻‍🤝‍👨🏼 belongs to 👫, 🫱🏻‍🫲🏼 to 🤝).
     */
    private void readEmojiTest(Path file) throws IOException {
        String group = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("# group: ")) {
                group = GROUPS.get(line.substring(9).trim());
                continue;
            }
            if (group == null || line.startsWith("#") || line.isBlank() || !line.contains("; fully-qualified")) {
                continue;
            }
            StringBuilder b = new StringBuilder();
            for (String cp : line.substring(0, line.indexOf(';')).trim().split(" +")) {
                b.appendCodePoint(Integer.parseInt(cp, 16));
            }
            String text = b.toString();
            if (TONE.matcher(text).find()) {
                emoji.getLast().variants().add(text);
            } else {
                emoji.add(new Emoji(text, group, new ArrayList<>()));
            }
        }
    }

    // ---------------------------------------------------------------- CLDR

    /** The keywords ("default") and names ("tts") of one emoji in one locale. */
    private record Annotation(List<String> keywords, List<String> names) {}

    /**
     * Reads one cldr-json annotations file into map, keyed by the emoji sequence; a later file
     * replaces an earlier one's entry for the same sequence.
     */
    @SuppressWarnings("unchecked")
    private static void readAnnotations(Path file, Map<String, Annotation> map) throws IOException {
        Map<String, Object> root = (Map<String, Object>) new Json(Files.readString(file, StandardCharsets.UTF_8)).value();
        Map<String, Object> top = (Map<String, Object>) root.values().iterator().next();
        Map<String, Object> all = (Map<String, Object>) top.get("annotations");
        for (var e : all.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            List<String> keywords = (List<String>) v.getOrDefault("default", List.of());
            List<String> names = (List<String>) v.getOrDefault("tts", List.of());
            map.put(e.getKey(), new Annotation(keywords, names));
        }
    }

    /**
     * Adds the keys one keyword or name yields in its locale.
     * Notes: English gives the whole phrase without spaces and each word that is not a stop word;
     * Chinese gives the text and its pinyin; Japanese gives the text, its hiragana and its romaji.
     */
    private void index(String locale, String text, int id, int tier) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase().trim();
        switch (locale) {
            case "en" -> {
                String[] words = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                        .split("[^a-z0-9]+");
                StringBuilder whole = new StringBuilder();
                List<String> real = new ArrayList<>();
                for (String w : words) {
                    if (!w.isEmpty()) {
                        whole.append(w);
                        real.add(w);
                    }
                }
                add(whole.toString(), id, tier);
                if (real.size() > 1) {
                    for (String w : real) {
                        if (w.length() > 1 && !STOP.contains(w)) {
                            add(w, id, WORD);
                        }
                    }
                }
            }
            case "zh", "zh-Hant" -> {
                add(t.replace(" ", ""), id, tier);
                String py = pinyin(t);
                if (py == null) {
                    unreadZh++;
                } else {
                    add(py, id, tier);
                }
            }
            case "ja" -> {
                add(t.replace(" ", ""), id, tier);
                String kana = kana(t);
                if (kana == null) {
                    unreadJa++;
                    return;
                }
                add(kana, id, tier);
                String r = romaji(kana);
                if (r != null) {
                    add(r, id, tier);
                }
            }
            default -> throw new IllegalStateException(locale);
        }
    }

    /** Records that key finds emoji id at tier, keeping the best tier. Notes: blank keys are dropped. */
    private void add(String key, int id, int tier) {
        if (key.isBlank() || key.contains("\t")) {
            return;
        }
        keys.computeIfAbsent(key, k -> new HashMap<>()).merge(id, tier, Math::min);
    }

    // ---------------------------------------------------------------- pinyin

    /**
     * Reads the first kMandarin reading of every character from Unihan.
     * Side effects: fills mandarin with toneless readings.
     */
    private void readUnihan(Path zip) throws IOException {
        eachZipLine(zip, line -> {
            if (line.startsWith("U+") && line.contains("\tkMandarin\t")) {
                String[] f = line.split("\t", 3);
                mandarin.put(Integer.parseInt(f[0].substring(2), 16), toneless(f[2].split(" ")[0]));
            }
        });
    }

    /**
     * Reads CC-CEDICT words of two or more characters, simplified and traditional, keeping the
     * first common-word reading (lower-case pinyin, not a name) of each.
     * Side effects: fills cedict with toneless, space-free readings.
     */
    private void readCedict(Path zip) throws IOException {
        eachZipLine(zip, line -> {
            if (line.startsWith("#")) {
                return;
            }
            int open = line.indexOf('['), close = line.indexOf(']');
            if (open < 0 || close < open) {
                return;
            }
            String[] forms = line.substring(0, open).trim().split(" ");
            String py = line.substring(open + 1, close);
            if (forms.length != 2 || py.isEmpty() || Character.isUpperCase(py.charAt(0))) {
                return;
            }
            StringBuilder b = new StringBuilder();
            for (String syl : py.split(" ")) {
                String s = syl.replaceAll("[0-9]", "").replace("u:", "v").toLowerCase();
                if (!s.matches("[a-z]+")) {
                    return;
                }
                b.append(s);
            }
            for (String form : forms) {
                if (form.codePointCount(0, form.length()) > 1) {
                    cedict.putIfAbsent(form, b.toString());
                }
            }
        });
    }

    /**
     * Converts a Chinese keyword to toneless pinyin. Returns: null when a Han character has no
     * reading. Notes: Latin letters and digits stay; other symbols are skipped.
     */
    private String pinyin(String text) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int len = longest(text, i, cedict);
            if (len > 0) {
                b.append(cedict.get(text.substring(i, i + len)));
                i += len;
                continue;
            }
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                String r = mandarin.get(cp);
                if (r == null) {
                    return null;
                }
                b.append(r);
            } else if ((cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) {
                b.appendCodePoint(cp);
            }
        }
        return b.isEmpty() ? null : b.toString();
    }

    /** Strips tone marks from a pinyin syllable ("lǜ" → "lv"). */
    private static String toneless(String syllable) {
        String d = Normalizer.normalize(syllable.toLowerCase(), Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            char ch = d.charAt(i);
            if (ch == '̈' && !b.isEmpty() && b.charAt(b.length() - 1) == 'u') {
                b.setCharAt(b.length() - 1, 'v');
            } else if (ch >= 'a' && ch <= 'z') {
                b.append(ch);
            }
        }
        return b.toString();
    }

    /**
     * Returns the length (in chars) of the longest word of dict starting at i, at most MAX_WORD
     * code points, or 0.
     */
    private static int longest(String text, int i, Map<String, String> dict) {
        int best = 0;
        int end = i;
        for (int n = 0; n < MAX_WORD && end < text.length(); n++) {
            end += Character.charCount(text.codePointAt(end));
            if (n > 0 && dict.containsKey(text.substring(i, end))) {
                best = end - i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- Japanese

    /** Collects every substring (up to MAX_WORD) of the Japanese keywords and names that holds a kanji. */
    private static Set<String> wantedJapanese(Map<String, Annotation> ja) {
        Set<String> wanted = new HashSet<>();
        for (Annotation a : ja.values()) {
            List<String> all = new ArrayList<>(a.keywords());
            all.addAll(a.names());
            for (String raw : all) {
                String t = Normalizer.normalize(raw, Normalizer.Form.NFKC);
                for (int i = 0; i < t.length(); i++) {
                    for (int j = i + 1; j <= Math.min(t.length(), i + MAX_WORD); j++) {
                        String s = t.substring(i, j);
                        if (s.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                            wanted.add(s);
                        }
                    }
                }
            }
        }
        return wanted;
    }

    /**
     * Streams the Mozc dictionary and keeps the lowest-cost reading of every wanted surface.
     * Side effects: fills kanaOf and kanaCost.
     */
    private void readMozc(Set<String> wanted) throws IOException {
        for (int n = 0; n < 10; n++) {
            try (BufferedReader in = Files.newBufferedReader(DEPS.resolve("mozc/dictionary0" + n + ".txt"), StandardCharsets.UTF_8)) {
                for (String line; (line = in.readLine()) != null; ) {
                    String[] f = line.split("\t");
                    if (f.length < 5 || !wanted.contains(f[4])) {
                        continue;
                    }
                    int cost = Integer.parseInt(f[3]);
                    Integer old = kanaCost.get(f[4]);
                    if (old == null || cost < old) {
                        kanaCost.put(f[4], cost);
                        kanaOf.put(f[4], f[0]);
                    }
                }
            }
        }
    }

    /**
     * Converts a Japanese keyword to hiragana. Returns: null when a kanji run has no reading.
     * Notes: katakana becomes hiragana; Latin letters and digits stay; other symbols are skipped.
     */
    private String kana(String text) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int len = longest(text, i, kanaOf);
            int first = text.codePointAt(i);
            if (len == 0 && kanaOf.containsKey(new String(Character.toChars(first)))) {
                len = Character.charCount(first);
            }
            if (len > 0) {
                b.append(kanaOf.get(text.substring(i, i + len)));
                i += len;
                continue;
            }
            i += Character.charCount(first);
            if (first >= 0x30A1 && first <= 0x30F6) {
                b.appendCodePoint(first - 0x60);
            } else if ((first >= 0x3041 && first <= 0x3096) || first == 0x30FC) {
                b.appendCodePoint(first);
            } else if (Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN) {
                return null;
            } else if ((first >= 'a' && first <= 'z') || (first >= '0' && first <= '9')) {
                b.appendCodePoint(first);
            }
        }
        return b.isEmpty() ? null : b.toString();
    }

    /** Hepburn romaji of each hiragana syllable; two-character entries are the yōon. */
    private static final Map<String, String> ROMAJI = new HashMap<>();

    static {
        String[] table = {
                "あa", "いi", "うu", "えe", "おo", "かka", "きki", "くku", "けke", "こko", "さsa", "しshi", "すsu", "せse", "そso",
                "たta", "ちchi", "つtsu", "てte", "とto", "なna", "にni", "ぬnu", "ねne", "のno", "はha", "ひhi", "ふfu", "へhe", "ほho",
                "まma", "みmi", "むmu", "めme", "もmo", "やya", "ゆyu", "よyo", "らra", "りri", "るru", "れre", "ろro",
                "わwa", "ゐi", "ゑe", "をo", "んn", "がga", "ぎgi", "ぐgu", "げge", "ごgo", "ざza", "じji", "ずzu", "ぜze", "ぞzo",
                "だda", "ぢji", "づzu", "でde", "どdo", "ばba", "びbi", "ぶbu", "べbe", "ぼbo", "ぱpa", "ぴpi", "ぷpu", "ぺpe", "ぽpo",
                "ぁa", "ぃi", "ぅu", "ぇe", "ぉo", "ゃya", "ゅyu", "ょyo", "ゎwa", "ゔvu", "ゕka", "ゖke",
                "きゃkya", "きゅkyu", "きょkyo", "しゃsha", "しゅshu", "しょsho", "ちゃcha", "ちゅchu", "ちょcho",
                "にゃnya", "にゅnyu", "にょnyo", "ひゃhya", "ひゅhyu", "ひょhyo", "みゃmya", "みゅmyu", "みょmyo",
                "りゃrya", "りゅryu", "りょryo", "ぎゃgya", "ぎゅgyu", "ぎょgyo", "じゃja", "じゅju", "じょjo",
                "ぢゃja", "ぢゅju", "ぢょjo", "びゃbya", "びゅbyu", "びょbyo", "ぴゃpya", "ぴゅpyu", "ぴょpyo",
                "しぇshe", "ちぇche", "じぇje", "てぃti", "でぃdi", "とぅtu", "どぅdu", "ふぁfa", "ふぃfi", "ふぇfe", "ふぉfo",
                "うぃwi", "うぇwe", "うぉwo", "ゔぁva", "ゔぃvi", "ゔぇve", "ゔぉvo", "つぁtsa", "つぃtsi", "つぇtse", "つぉtso",
        };
        for (String s : table) {
            int split = Character.isLetter(s.charAt(1)) && s.charAt(1) < 0x80 ? 1 : 2;
            ROMAJI.put(s.substring(0, split), s.substring(split));
        }
    }

    /**
     * Converts hiragana to Hepburn romaji. Returns: null for an unknown kana.
     * Notes: っ doubles the next consonant (ch → tch); ー is dropped; ASCII stays.
     */
    static String romaji(String kana) {
        StringBuilder b = new StringBuilder();
        boolean geminate = false;
        int i = 0;
        while (i < kana.length()) {
            char c = kana.charAt(i);
            if (c == 'っ') {
                geminate = true;
                i++;
                continue;
            }
            if (c == 'ー') {
                i++;
                continue;
            }
            if (c < 0x80) {
                b.append(c);
                i++;
                continue;
            }
            String r = i + 1 < kana.length() ? ROMAJI.get(kana.substring(i, i + 2)) : null;
            int len = 2;
            if (r == null) {
                r = ROMAJI.get(String.valueOf(c));
                len = 1;
            }
            if (r == null) {
                return null;
            }
            if (geminate && Character.isLetter(r.charAt(0)) && "aiueon".indexOf(r.charAt(0)) < 0) {
                b.append(r.startsWith("ch") ? 't' : r.charAt(0));
            }
            geminate = false;
            b.append(r);
            i += len;
        }
        return b.isEmpty() ? null : b.toString();
    }

    // ---------------------------------------------------------------- output

    /**
     * Writes the asset: header, groups with their emoji, then the keys with their emoji sorted by
     * tier and then by keyboard order.
     */
    private void write(Path out) throws IOException {
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("#kotomo-emoji\t1\temoji " + EMOJI_VERSION + "\tcldr " + CLDR_VERSION + "\n");
            String group = null;
            for (Emoji e : emoji) {
                if (!e.group().equals(group)) {
                    group = e.group();
                    w.write("g\t" + group + "\n");
                }
                w.write("e\t" + e.text() + (e.variants().isEmpty() ? "" : "\t" + String.join(" ", e.variants())) + "\n");
            }
            for (var k : keys.entrySet()) {
                StringBuilder ids = new StringBuilder();
                k.getValue().entrySet().stream()
                        .sorted((a, b) -> a.getValue().equals(b.getValue()) ? Integer.compare(a.getKey(), b.getKey())
                                : Integer.compare(a.getValue(), b.getValue()))
                        .forEach(x -> ids.append(ids.isEmpty() ? "" : ",").append(x.getKey()));
                w.write("k\t" + k.getKey() + "\t" + ids + "\n");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Feeds every line of every file in a zip archive to sink, as UTF-8. */
    private static void eachZipLine(Path zip, java.util.function.Consumer<String> sink) throws IOException {
        try (InputStream raw = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(raw)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                BufferedReader in = new BufferedReader(new InputStreamReader(z, StandardCharsets.UTF_8));
                for (String line; (line = in.readLine()) != null; ) {
                    sink.accept(line);
                }
            }
        }
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
     * A minimal JSON reader for the cldr-json files: objects become LinkedHashMap, arrays
     * ArrayList, strings String; numbers, booleans and null are read as their text.
     * Notes: input is trusted only as far as its pinned hash, so malformed JSON fails loudly.
     */
    private static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        /** Parses the value at the cursor. */
        Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                skip();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    skip();
                    String k = string();
                    skip();
                    expect(':');
                    m.put(k, value());
                    skip();
                    if (s.charAt(i++) == '}') {
                        return m;
                    }
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++;
                skip();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    skip();
                    if (s.charAt(i++) == ']') {
                        return l;
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            int start = i;
            while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            return s.substring(start, i);
        }

        /** Parses a string literal at the cursor. */
        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
        }

        private void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalStateException("JSON: expected '" + c + "' at " + i);
            }
            i++;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
