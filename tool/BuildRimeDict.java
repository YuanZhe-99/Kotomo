import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges modern Chinese vocabulary from pinned {@code rime-ice} word lists ({@code 8105},
 * {@code base}, {@code ext}) into a staged {@code luna_pinyin.dict.yaml} before prebuilding
 * librime's binary tables ({@code scripts/prebuild-rime.sh}). Run from the repository root:
 * {@code java tool/BuildRimeDict.java [staged-rime-dir]}; the default directory is
 * {@code build/rime-prebuilt}.
 *
 * <p>Notes: URLs and SHA-256 checksums come from {@code build-lock.json} ({@code dataSources.rime-ice})
 * and cached files live under {@code .deps/rime-ice/}. Existing headwords already present in
 * {@code luna_pinyin.dict.yaml} or {@code essay.txt} (mapped via OpenCC Traditional-to-Simplified
 * tables) are skipped unchanged so established rankings, regional conversion and weights stay
 * intact. Merged entry weights are scaled down by {@link #WEIGHT_SCALE} and floored at
 * {@link #MIN_MERGED_WEIGHT} to align with {@code essay.txt}'s frequency scale so new phrases
 * do not crowd out established corrections or homophones. Exit status: 0 on success, 1 on any failure.
 */
public final class BuildRimeDict {
    private static final Path DEPS_DIR = Path.of(".deps", "rime-ice");
    private static final Path LOCK_FILE = Path.of("build-lock.json");
    private static final List<String> ICE_KEYS = List.of(
            "cn_dicts/8105.dict.yaml",
            "cn_dicts/base.dict.yaml",
            "cn_dicts/ext.dict.yaml"
    );
    private static final int MAX_WORD_CODEPOINTS = 16;
    private static final double WEIGHT_SCALE = 25.0;
    private static final long MIN_MERGED_WEIGHT = 15L;

    /**
     * Verifies or fetches the pinned {@code rime-ice} lists and appends missing Han words with valid
     * {@code luna_pinyin} syllables into {@code <stagedDir>/luna_pinyin.dict.yaml}.
     * Inputs: {@code args[0]} — optional staged Rime directory.
     * Side effects: downloads missing inputs into {@code .deps/rime-ice/}; appends to
     * {@code <stagedDir>/luna_pinyin.dict.yaml}.
     */
    public static void main(String[] args) {
        Path stagedDir = Path.of(args.length > 0 ? args[0] : "build/rime-prebuilt");
        try {
            Path dictPath = stagedDir.resolve("luna_pinyin.dict.yaml");
            Path essayPath = stagedDir.resolve("essay.txt");
            Path openccDir = stagedDir.resolve("opencc");
            if (!Files.isRegularFile(dictPath) || !Files.isRegularFile(essayPath)) {
                throw new IOException("staged Rime data missing in " + stagedDir);
            }

            List<Path> iceFiles = resolveIceFiles();
            Map<Integer, String> t2sChars = loadCharMap(openccDir.resolve("TSCharacters.txt"));
            Map<String, String> t2sPhrases = loadPhraseMap(openccDir.resolve("TSPhrases.txt"));

            Set<String> syllabary = new HashSet<>();
            Set<String> existingWords = new HashSet<>();
            Set<String> existingPairs = new HashSet<>();
            boolean endsWithNewline = readBaseDictionary(dictPath, t2sChars, t2sPhrases, syllabary, existingWords, existingPairs);
            readEssay(essayPath, t2sChars, t2sPhrases, existingWords);

            long added = 0;
            try (BufferedWriter out = Files.newBufferedWriter(dictPath, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                if (!endsWithNewline) out.write('\n');
                for (Path iceFile : iceFiles) {
                    added += appendNewEntries(iceFile, syllabary, existingWords, existingPairs, out);
                }
            }
            System.out.println("rime dict: merged " + added + " entries from rime-ice (" + existingPairs.size() + " total)");
        } catch (IOException | InterruptedException e) {
            System.err.println("build-rime-dict: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Resolves and verifies the pinned rime-ice files against {@code build-lock.json}. */
    @SuppressWarnings("unchecked")
    private static List<Path> resolveIceFiles() throws IOException, InterruptedException {
        Map<String, Object> lock = (Map<String, Object>) Json.parse(Files.readString(LOCK_FILE, StandardCharsets.UTF_8));
        Map<String, Object> dataSources = (Map<String, Object>) lock.get("dataSources");
        Map<String, Object> ice = (Map<String, Object>) dataSources.get("rime-ice");
        String base = (String) ice.get("base");
        Map<String, Object> hashes = (Map<String, Object>) ice.get("sha256");

        List<Path> paths = new ArrayList<>(ICE_KEYS.size());
        for (String key : ICE_KEYS) {
            String expectedSha = (String) hashes.get(key);
            if (expectedSha == null) throw new IOException("missing sha256 pin for " + key);
            String fileName = Path.of(key).getFileName().toString();
            paths.add(ensureFile(base + key, DEPS_DIR.resolve(fileName), expectedSha));
        }
        return paths;
    }

    /** Returns target if present with expected SHA-256, otherwise downloads and verifies it. */
    private static Path ensureFile(String url, Path target, String sha256)
            throws IOException, InterruptedException {
        if (Files.isRegularFile(target) && sha256(target).equals(sha256)) return target;
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<InputStream> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(HttpRequest.newBuilder(URI.create(url)).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException(url + ": HTTP " + response.statusCode());
        try (InputStream in = response.body()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }
        String got = sha256(part);
        if (!got.equals(sha256)) {
            Files.deleteIfExists(part);
            throw new IOException(url + ": sha256 " + got + ", expected " + sha256);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Loads single-character Traditional-to-Simplified mappings from OpenCC's TSCharacters.txt. */
    private static Map<Integer, String> loadCharMap(Path file) throws IOException {
        Map<Integer, String> map = new HashMap<>();
        if (!Files.isRegularFile(file)) return map;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String key = line.substring(0, tab);
                if (key.codePointCount(0, key.length()) != 1) continue;
                String val = line.substring(tab + 1).trim();
                int space = val.indexOf(' ');
                map.put(key.codePointAt(0), space > 0 ? val.substring(0, space) : val);
            }
        }
        return map;
    }

    /** Loads phrase-level Traditional-to-Simplified mappings from OpenCC's TSPhrases.txt. */
    private static Map<String, String> loadPhraseMap(Path file) throws IOException {
        Map<String, String> map = new HashMap<>();
        if (!Files.isRegularFile(file)) return map;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String key = line.substring(0, tab);
                String val = line.substring(tab + 1).trim();
                int space = val.indexOf(' ');
                map.put(key, space > 0 ? val.substring(0, space) : val);
            }
        }
        return map;
    }

    /** Maps Traditional headwords to Simplified using OpenCC phrase and character tables. */
    private static String toSimplified(String text, Map<Integer, String> chars, Map<String, String> phrases) {
        String phrase = phrases.get(text);
        if (phrase != null) return phrase;
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            String rep = chars.get(cp);
            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                    sb.append(text, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(text, i, i + len);
            }
            i += len;
        }
        return sb != null ? sb.toString() : text;
    }

    /** Reads existing luna_pinyin entries, building the allowed syllabary and word/pair sets. */
    private static boolean readBaseDictionary(
            Path dictPath,
            Map<Integer, String> t2sChars,
            Map<String, String> t2sPhrases,
            Set<String> syllabary,
            Set<String> existingWords,
            Set<String> existingPairs
    ) throws IOException {
        byte[] tail = new byte[1];
        long size = Files.size(dictPath);
        boolean endsWithNewline = size == 0;
        if (size > 0) {
            try (InputStream in = Files.newInputStream(dictPath)) {
                in.skipNBytes(size - 1);
                if (in.read(tail) == 1) endsWithNewline = tail[0] == '\n';
            }
        }
        try (BufferedReader r = Files.newBufferedReader(dictPath, StandardCharsets.UTF_8)) {
            boolean body = false;
            String line;
            while ((line = r.readLine()) != null) {
                if (!body) {
                    body = "...".equals(line.trim());
                    continue;
                }
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab1 = line.indexOf('\t');
                if (tab1 <= 0) continue;
                int tab2 = line.indexOf('\t', tab1 + 1);
                String word = line.substring(0, tab1);
                String code = (tab2 > 0 ? line.substring(tab1 + 1, tab2) : line.substring(tab1 + 1)).trim();
                for (String s : code.split(" ")) {
                    if (!s.isEmpty()) syllabary.add(s);
                }
                existingWords.add(toSimplified(word, t2sChars, t2sPhrases));
                existingPairs.add(word + "\t" + code);
            }
        }
        return endsWithNewline;
    }

    /** Records Simplified forms of every preset vocabulary headword in essay.txt. */
    private static void readEssay(
            Path essayPath,
            Map<Integer, String> t2sChars,
            Map<String, String> t2sPhrases,
            Set<String> existingWords
    ) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(essayPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) existingWords.add(toSimplified(line.substring(0, tab), t2sChars, t2sPhrases));
            }
        }
    }

    /** Appends valid, unseen entries from one rime-ice dictionary file to {@code out}. */
    private static long appendNewEntries(
            Path iceFile,
            Set<String> syllabary,
            Set<String> existingWords,
            Set<String> existingPairs,
            BufferedWriter out
    ) throws IOException {
        long added = 0;
        try (BufferedReader r = Files.newBufferedReader(iceFile, StandardCharsets.UTF_8)) {
            boolean body = false;
            String line;
            while ((line = r.readLine()) != null) {
                if (!body) {
                    body = "...".equals(line.trim());
                    continue;
                }
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab1 = line.indexOf('\t');
                if (tab1 <= 0) continue;
                int tab2 = line.indexOf('\t', tab1 + 1);
                String word = line.substring(0, tab1);
                if (!isHanWord(word) || existingWords.contains(word)) continue;

                String rawCode = (tab2 > 0 ? line.substring(tab1 + 1, tab2) : line.substring(tab1 + 1)).trim();
                String normCode = validCode(rawCode, word.codePointCount(0, word.length()), syllabary);
                if (normCode == null) continue;

                String pair = word + "\t" + normCode;
                if (!existingPairs.add(pair)) continue;

                long weight = parseWeight(tab2 > 0 ? line.substring(tab2 + 1) : "");
                out.write(word);
                out.write('\t');
                out.write(normCode);
                out.write('\t');
                out.write(Long.toString(weight));
                out.write('\n');
                added++;
            }
        }
        return added;
    }

    /** Returns true if every code point in {@code word} is a Han ideograph and length is in bounds. */
    private static boolean isHanWord(String word) {
        int cpCount = word.codePointCount(0, word.length());
        if (cpCount < 1 || cpCount > MAX_WORD_CODEPOINTS) return false;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    /** Validates and space-normalizes {@code code} against the expected syllable count and syllabary. */
    private static String validCode(String code, int expectedCount, Set<String> syllabary) {
        if (code.isEmpty()) return null;
        String[] parts = code.split(" ");
        List<String> out = new ArrayList<>(expectedCount);
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!syllabary.contains(p)) return null;
            out.add(p);
        }
        return out.size() == expectedCount ? String.join(" ", out) : null;
    }

    /**
     * Parses a numeric weight, scales it by {@link #WEIGHT_SCALE} into {@code essay.txt}'s
     * frequency range, and floors it at {@link #MIN_MERGED_WEIGHT}.
     */
    private static long parseWeight(String raw) {
        String s = raw.trim();
        if (s.isEmpty() || s.endsWith("%")) return MIN_MERGED_WEIGHT;
        try {
            double v = Double.parseDouble(s);
            if (!Double.isFinite(v) || v < 0) return MIN_MERGED_WEIGHT;
            return Math.max(MIN_MERGED_WEIGHT, Math.round(v / WEIGHT_SCALE));
        } catch (NumberFormatException e) {
            return MIN_MERGED_WEIGHT;
        }
    }

    /** Computes the lower-case hex SHA-256 digest of a file by streaming. */
    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal JSON reader for {@code build-lock.json}. */
    private static final class Json {
        private final String s;
        private int p;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) throws IOException {
            Json j = new Json(s);
            Object v = j.value();
            j.ws();
            if (j.p != j.s.length()) throw new IOException("trailing JSON content");
            return v;
        }

        private void ws() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        private char next() throws IOException {
            ws();
            if (p >= s.length()) throw new IOException("unexpected end of JSON");
            return s.charAt(p);
        }

        private Object value() throws IOException {
            return switch (next()) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        }

        private Map<String, Object> object() throws IOException {
            p++;
            Map<String, Object> m = new LinkedHashMap<>();
            if (next() == '}') {
                p++;
                return m;
            }
            while (true) {
                String k = string();
                if (next() != ':') throw new IOException("expected ':' in JSON object");
                p++;
                m.put(k, value());
                char c = next();
                if (c == '}') {
                    p++;
                    return m;
                }
                if (c != ',') throw new IOException("expected ',' or '}' in JSON object");
                p++;
            }
        }

        private List<Object> array() throws IOException {
            p++;
            List<Object> list = new ArrayList<>();
            if (next() == ']') {
                p++;
                return list;
            }
            while (true) {
                list.add(value());
                char c = next();
                if (c == ']') {
                    p++;
                    return list;
                }
                if (c != ',') throw new IOException("expected ',' or ']' in JSON array");
                p++;
            }
        }

        private String string() throws IOException {
            if (next() != '"') throw new IOException("expected '\"' in JSON string");
            p++;
            StringBuilder out = new StringBuilder();
            while (p < s.length()) {
                char c = s.charAt(p++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (p >= s.length()) throw new IOException("unterminated escape in JSON string");
                    char e = s.charAt(p++);
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                            p += 4;
                        }
                        default -> throw new IOException("bad JSON escape: \\" + e);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IOException("unterminated JSON string");
        }

        private Boolean bool() throws IOException {
            if (s.startsWith("true", p)) {
                p += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", p)) {
                p += 5;
                return Boolean.FALSE;
            }
            throw new IOException("invalid JSON boolean");
        }

        private Object nul() throws IOException {
            if (!s.startsWith("null", p)) throw new IOException("invalid JSON null");
            p += 4;
            return null;
        }

        private Number number() throws IOException {
            int start = p;
            while (p < s.length() && "-0123456789.eE+".indexOf(s.charAt(p)) >= 0) p++;
            String num = s.substring(start, p);
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                try {
                    return Double.parseDouble(num);
                } catch (NumberFormatException ex) {
                    throw new IOException("invalid JSON number: " + num);
                }
            }
        }
    }
}
