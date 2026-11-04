import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the cross-language lexicon against the hand-reviewed samples of spike S5. Run from the
 * repository root after {@code java lexicon/CrossLexicon.java}:
 * {@code java lexicon/CheckSamples.java [samples.tsv]} (default {@code tests/lexicon/samples.tsv}).
 *
 * <p>Notes: prints PASS or FAIL per sample line, then the coverage (positive lines found within
 * their rank) and the error rate (negative lines violated). The column meanings are in the header
 * of samples.tsv. Exit status: 0 when every line passes, 1 when one fails, 2 when the lexicon or
 * the samples cannot be read.
 */
public final class CheckSamples {
    private static final Path LEXICON = Path.of("build", "lexicon");

    /** One lexicon row as the check needs it, in cost order within its key. */
    private record Entry(String surface, String reading) {}

    /**
     * Loads both tables, checks every sample line and prints the results.
     * Side effects: reads build/lexicon/ and the samples; ends the process with the status above.
     */
    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        Path samples = Path.of(args.length > 0 ? args[0] : "tests/lexicon/samples.tsv");
        Map<String, Map<String, List<Entry>>> tables = new HashMap<>();
        List<String[]> lines = new ArrayList<>();
        try {
            tables.put("pinyin", load(LEXICON.resolve("pinyin-ja.tsv")));
            tables.put("en", load(LEXICON.resolve("en-ja.tsv")));
            for (String line : Files.readAllLines(samples)) {
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("id\t")) {
                    continue;
                }
                lines.add(line.split("\t", -1));
            }
        } catch (IOException e) {
            System.err.println("checksamples: " + e);
            System.exit(2);
        }
        int positives = 0;
        int found = 0;
        int negatives = 0;
        int violated = 0;
        for (String[] f : lines) {
            if (f.length < 7 || !tables.containsKey(f[1])) {
                System.err.println("checksamples: bad line " + String.join("\t", f));
                System.exit(2);
            }
            Map<String, List<Entry>> table = tables.get(f[1]);
            boolean negative = f[3].startsWith("!");
            String surface = negative ? f[3].substring(1) : f[3];
            int limit = Integer.parseInt(f[5]);
            String where;
            boolean pass;
            if (f[2].equals("*")) {
                String hit = null;
                for (Map.Entry<String, List<Entry>> e : table.entrySet()) {
                    if (rank(e.getValue(), surface, f[4]) > 0) {
                        hit = e.getKey();
                        break;
                    }
                }
                pass = negative == (hit == null);
                where = hit == null ? "absent from every key" : "found under " + hit;
            } else {
                List<Entry> rows = table.getOrDefault(f[2], List.of());
                int r = rank(rows, surface, f[4]);
                boolean within = r > 0 && (limit == 0 || r <= limit);
                pass = negative != within;
                where = r > 0 ? "rank " + r + " of " + distinct(rows) : "absent (" + distinct(rows) + " surfaces)";
            }
            if (negative) {
                negatives++;
                violated += pass ? 0 : 1;
            } else {
                positives++;
                found += pass ? 1 : 0;
            }
            String want = (negative ? "not " : "") + (limit == 0 ? "anywhere" : "top " + limit);
            System.out.printf("%s %s %s %s → %s%s: %s (want %s)%n", pass ? "PASS" : "FAIL", f[0], f[1], f[2],
                    f[3], f[4].isEmpty() ? "" : " /" + f[4], where, want);
        }
        System.out.printf("coverage %d/%d (%.1f%%), error rate %d/%d (%.1f%%)%n", found, positives,
                100.0 * found / Math.max(positives, 1), violated, negatives, 100.0 * violated / Math.max(negatives, 1));
        System.exit(found == positives && violated == 0 ? 0 : 1);
    }

    /**
     * Reads a lexicon TSV (key, detail, surface, reading, ...) into key → rows in file order.
     * Notes: the builder writes rows sorted by key and cost, so file order is rank order.
     */
    private static Map<String, List<Entry>> load(Path file) throws IOException {
        Map<String, List<Entry>> map = new HashMap<>();
        try (BufferedReader in = Files.newBufferedReader(file)) {
            in.readLine();
            for (String line; (line = in.readLine()) != null; ) {
                String[] f = line.split("\t", 5);
                map.computeIfAbsent(f[0], k -> new ArrayList<>(2)).add(new Entry(f[2], f[3]));
            }
        }
        return map;
    }

    /**
     * Ranks a surface within one key.
     * Returns: its 1-based position among the key's distinct surfaces, counting the first row
     * with that surface (and that reading, when one is given); 0 when absent.
     */
    private static int rank(List<Entry> rows, String surface, String reading) {
        Set<String> seen = new HashSet<>();
        for (Entry e : rows) {
            seen.add(e.surface());
            if (e.surface().equals(surface) && (reading.isEmpty() || e.reading().equals(reading))) {
                return seen.size();
            }
        }
        return 0;
    }

    /** Counts the distinct surfaces of a key. */
    private static int distinct(List<Entry> rows) {
        return (int) rows.stream().map(Entry::surface).distinct().count();
    }
}
