import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * Builds the English binary dictionary {@code main_en_us.dict} (spike S4) from a pinned word list
 * with AOSP LatinIME's dicttool. Run from the repository root:
 * {@code java tool/BuildEnglishDict.java [output]}; the default output is
 * {@code build/lexicon/english/main_en_us.dict}.
 *
 * <p>Notes: every input is pinned and verified — the word list and the two compile-time jars by
 * SHA-256, the LatinIME tree by its git commit — and kept under {@code .deps/}, one directory each.
 * The pins match {@code build-lock.json}. The dictionary is build output and is never committed.
 * Needs git on the PATH and a JDK (javac). Exit status: 0 on success, 1 on any failure.
 */
public final class BuildEnglishDict {
    private static final Path DEPS = Path.of(".deps");
    private static final String WORDLIST_COMMIT = "ff0cd52be8d9530200fefe8b296c53d4f662091e";
    private static final String WORDLIST_URL = "https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/"
            + WORDLIST_COMMIT + "/wordlists/main_en_US__experimental.combined";
    private static final String WORDLIST_SHA256 =
            "466c2eb4737fa1a9453e5f4a33337db8dd703f75012b70c2534490fd189c9487";
    private static final String LATINIME_URL =
            "https://android.googlesource.com/platform/packages/inputmethods/LatinIME";
    private static final String LATINIME_COMMIT = "127336e9f29d69607eab55982324b210279ae8c5";
    private static final String MAVEN = "https://repo1.maven.org/maven2/";
    private static final String JSR305 = "com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar";
    private static final String JSR305_SHA256 =
            "766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7";
    private static final String JUNIT = "junit/junit/4.13.2/junit-4.13.2.jar";
    private static final String JUNIT_SHA256 =
            "8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3";

    /**
     * Fetches the inputs, compiles dicttool and converts the word list into a version 2 binary
     * dictionary.
     * Inputs: args[0] — optional output path.
     * Side effects: downloads into .deps/ when an input is missing or wrong; writes the output.
     */
    public static void main(String[] args) {
        Path out = Path.of(args.length > 0 ? args[0] : "build/lexicon/english/main_en_us.dict");
        try {
            Path wordlist = download(WORDLIST_URL, DEPS.resolve("aosp-dictionaries-" + WORDLIST_COMMIT
                    .substring(0, 12)).resolve("main_en_US__experimental.combined"), WORDLIST_SHA256);
            Path jsr305 = download(MAVEN + JSR305, DEPS.resolve("jsr305-3.0.2/jsr305-3.0.2.jar"),
                    JSR305_SHA256);
            Path junit = download(MAVEN + JUNIT, DEPS.resolve("junit-4.13.2/junit-4.13.2.jar"),
                    JUNIT_SHA256);
            Path latinime = checkout(DEPS.resolve("aosp-latinime"));
            Path classes = compileDicttool(latinime, List.of(jsr305, junit));
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.deleteIfExists(out);
            run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    classes + java.io.File.pathSeparator + jsr305,
                    "com.android.inputmethod.latin.dicttool.Dicttool", "makedict",
                    "-s", wordlist.toString(), "-d", out.toString(), "-2"));
            System.out.println("english dict: " + out + " (" + Files.size(out) + " bytes, sha256 "
                    + sha256(out) + ")");
        } catch (IOException | InterruptedException e) {
            System.err.println("build-english-dict: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Downloads url to target unless target already has the expected hash.
     * Returns: target. Side effects: replaces target; fails when the downloaded hash differs.
     */
    private static Path download(String url, Path target, String sha256)
            throws IOException, InterruptedException {
        if (Files.exists(target) && sha256(target).equals(sha256)) return target;
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
            Files.delete(part);
            throw new IOException(url + ": sha256 " + got + ", expected " + sha256);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * Makes dir a shallow checkout of LatinIME at the pinned commit.
     * Returns: dir. Side effects: runs git init/fetch/checkout in dir when HEAD differs.
     */
    private static Path checkout(Path dir) throws IOException, InterruptedException {
        if (Files.isDirectory(dir.resolve(".git")) && head(dir).equals(LATINIME_COMMIT)) return dir;
        Files.createDirectories(dir);
        run(List.of("git", "-C", dir.toString(), "init", "-q"));
        run(List.of("git", "-C", dir.toString(), "fetch", "-q", "--depth", "1", LATINIME_URL,
                LATINIME_COMMIT));
        run(List.of("git", "-C", dir.toString(), "checkout", "-q", "--detach", "FETCH_HEAD"));
        if (!head(dir).equals(LATINIME_COMMIT)) throw new IOException(dir + ": wrong commit");
        return dir;
    }

    /** Returns the commit checked out in dir, or an empty string. */
    private static String head(Path dir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "-C", dir.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes()).trim();
        return p.waitFor() == 0 ? text : "";
    }

    /**
     * Compiles dicttool with the sources its Android.bp names (dicttool_aosp and dicttool_deps).
     * Returns: the class output directory. Side effects: writes .deps/dicttool-classes/.
     */
    private static Path compileDicttool(Path latinime, List<Path> classpath) throws IOException {
        List<String> sources = new ArrayList<>();
        for (String dir : List.of("tools/dicttool/src", "tools/dicttool/compat", "tools/dicttool/tests",
                "java/src/com/android/inputmethod/latin/makedict",
                "tests/src/com/android/inputmethod/latin/makedict", "common/src")) {
            try (Stream<Path> s = Files.walk(latinime.resolve(dir))) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString()));
            }
        }
        for (String file : List.of("BinaryDictionary", "DicTraverseSession", "Dictionary",
                "NgramContext", "SuggestedWords", "settings/SettingsValuesForSuggestion",
                "utils/BinaryDictionaryUtils", "utils/CombinedFormatUtils", "utils/JniUtils",
                "define/DebugFlags", "define/DecoderSpecificConstants")) {
            sources.add(latinime.resolve("java/src/com/android/inputmethod/latin/" + file + ".java")
                    .toString());
        }
        sources.add(latinime.resolve("tests/src/com/android/inputmethod/latin/utils/ByteArrayDictBuffer.java")
                .toString());
        Path classes = DEPS.resolve("dicttool-classes");
        List<String> options = new ArrayList<>(List.of("-nowarn", "-encoding", "UTF-8", "-d",
                classes.toString(), "-cp", String.join(java.io.File.pathSeparator,
                        classpath.stream().map(Path::toString).toList())));
        options.addAll(sources);
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new)) != 0) {
            throw new IOException("dicttool did not compile");
        }
        return classes;
    }

    /** Runs a command with inherited output; fails on a non-zero exit. */
    private static void run(List<String> command) throws IOException, InterruptedException {
        if (new ProcessBuilder(command).inheritIO().start().waitFor() != 0) {
            throw new IOException(String.join(" ", command) + ": failed");
        }
    }

    /** Returns the lower-case hex SHA-256 of a file. */
    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
