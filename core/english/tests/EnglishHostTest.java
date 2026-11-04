package com.yuanzhe.kotomo.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks the English engine (spike S4, corpus area T02) on the host through the same JNI entry
 * points the app uses. Run by {@code core/english/tests/run-host.sh}.
 *
 * <p>Notes: needs the system properties {@code kotomo.english.lib} (the host build of
 * libkotomo_english.so) and {@code kotomo.english.dict} (the binary dictionary). Every capability
 * has a positive and a negative case. Timings printed here are host timings and never stand in for
 * device latency. Exit status 0 when every case passes, 1 otherwise.
 */
public final class EnglishHostTest {
    private static int failures;

    /**
     * Runs every case and prints PASS or FAIL per case with the suggestions it saw.
     * Side effects: loads the native library and memory-maps the dictionary.
     */
    public static void main(String[] args) {
        System.load(System.getProperty("kotomo.english.lib"));
        long handle = EnglishJni.open(System.getProperty("kotomo.english.dict"), 0, -1);
        check("S4-open", handle != 0, "dictionary opened");
        if (handle == 0) System.exit(1);

        // Spelling correction (positive): a dropped letter and a transposition.
        expectInTop(handle, "S4-helo", "helo", null, null, null, "hello", 3);
        expectInTop(handle, "S4-teh", "teh", null, null, null, "the", 3);
        // Key-proximity correction (positive): r tapped right next to e.
        int[][] touch = touches("hrllo", 'r', 'e');
        expectInTop(handle, "S4-hrllo-touch", "hrllo", touch[0], touch[1], null, "hello", 3);
        // Completion (positive): a prefix completes to a common word.
        expectInTop(handle, "S4-complete", "beautif", null, null, null, "beautiful", 3);
        // Valid words are kept (negative): a correct word is not corrected into another one.
        expectFirst(handle, "S4-keep-hell", "hell", null, "hell");
        expectFirst(handle, "S4-keep-the", "the", null, "the");
        // Each word is offered once (negative): the core's duplicate entries are merged.
        List<String> hell = words(handle, "hell", null, null, null);
        check("S4-unique", hell.size() == hell.stream().distinct().count(), "hell gives " + hell);
        // Next-word prediction (positive): the dictionary's bigrams after "the".
        expectInTop(handle, "S4-predict-the", "", null, null, "the", "first", 18);
        // Prediction needs context (negative): no previous word, nothing typed, nothing predicted.
        List<String> none = words(handle, "", null, null, null);
        check("S4-predict-none", none.isEmpty(), "no context gives " + none);
        // Gibberish (negative): nothing in the dictionary is offered as an exact match.
        List<String> junk = words(handle, "qxzjvk", null, null, null);
        check("S4-gibberish", !junk.contains("qxzjvk"), "qxzjvk gives " + junk);
        // Boundary (negative): input over MAX_WORD_LENGTH is rejected, not truncated.
        check("S4-too-long", EnglishJni.suggest(handle, "a".repeat(49), null, null, null,
                new int[18]) == null, "49 code points rejected");

        timing(handle);
        EnglishJni.close(handle);
        System.out.println(failures == 0 ? "english host test: PASS" : "english host test: FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Asserts that {@code want} is among the first {@code rank} suggestions for the input. */
    private static void expectInTop(long handle, String id, String typed, int[] xs, int[] ys,
            String previous, String want, int rank) {
        List<String> got = words(handle, typed, xs, ys, previous);
        int index = got.indexOf(want);
        check(id, index >= 0 && index < rank, "'" + typed + "' -> " + want + " in top " + rank
                + ": " + got.subList(0, Math.min(5, got.size())));
    }

    /** Asserts that the first suggestion for the input is {@code want}. */
    private static void expectFirst(long handle, String id, String typed, String previous,
            String want) {
        List<String> got = words(handle, typed, null, null, previous);
        check(id, !got.isEmpty() && got.get(0).equals(want), "'" + typed + "' -> " + want
                + " first: " + got.subList(0, Math.min(5, got.size())));
    }

    /** Returns the suggested words for one call, best first. */
    private static List<String> words(long handle, String typed, int[] xs, int[] ys,
            String previous) {
        String[] got = EnglishJni.suggest(handle, typed, xs, ys, previous, new int[18]);
        return got == null ? List.of() : new ArrayList<>(Arrays.asList(got));
    }

    /**
     * Places one touch per letter at its key's centre in the built-in layout, except that the
     * letter {@code shifted} lands near the edge it shares with {@code toward}.
     * Returns: {xs, ys}.
     */
    private static int[][] touches(String word, char shifted, char toward) {
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        int[] offsets = {0, 50, 150};
        int[] xs = new int[word.length()];
        int[] ys = new int[word.length()];
        for (int i = 0; i < word.length(); i++) {
            for (int r = 0; r < rows.length; r++) {
                int k = rows[r].indexOf(word.charAt(i));
                if (k >= 0) {
                    xs[i] = offsets[r] + k * 100 + 50;
                    ys[i] = r * 150 + 75;
                }
            }
            if (word.charAt(i) == shifted) {
                int t = rows[0].indexOf(toward);
                xs[i] += Integer.signum(t * 100 + 50 - xs[i]) * 40;
            }
        }
        return new int[][] {xs, ys};
    }

    /** Prints the mean host time of a typing call and of a prediction call. */
    private static void timing(long handle) {
        int[] scores = new int[18];
        for (int i = 0; i < 50; i++) EnglishJni.suggest(handle, "helo", null, null, null, scores);
        int n = 500;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) EnglishJni.suggest(handle, "beautifl", null, null, "a", scores);
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) EnglishJni.suggest(handle, "", null, null, "the", scores);
        long t2 = System.nanoTime();
        System.out.printf("host timing: typing %.3f ms, prediction %.3f ms per call (host only)%n",
                (t1 - t0) / 1e6 / n, (t2 - t1) / 1e6 / n);
    }

    /** Records and prints one case result. */
    private static void check(String id, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + id + "  " + detail);
    }
}

/** Mirrors the app's EnglishJni natives (app/src/main/java/.../engine/English.kt) for the host. */
final class EnglishJni {
    static native long open(String path, long offset, long length);

    static native String[] suggest(long handle, String typed, int[] xs, int[] ys, String previous,
            int[] outScores);

    static native void close(long handle);
}
