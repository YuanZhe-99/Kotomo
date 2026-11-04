import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks that every language tree under {@code doc/} mirrors {@code doc/en-us} and that local
 * Markdown links resolve. Run from the repository root: {@code java tool/CheckDocs.java}.
 *
 * <p>Notes: mirrored files must have the same heading levels in the same order, the same number
 * of table rows and byte-identical fenced code blocks; their wording is free. Links are checked in
 * the top-level Markdown files and in every file under {@code doc/}. Exit status: 0 when clean, 1
 * when problems were found, 2 when the documentation could not be read.
 */
public final class CheckDocs {
    private static final Path DOC = Path.of("doc");
    private static final Path SOURCE = DOC.resolve("en-us");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    /** The structure two mirrored documents share, plus the link targets found outside code. */
    private record Shape(List<Integer> headings, int tableRows, List<String> code, List<String> links) {}

    /**
     * Runs the mirror and link checks and prints every problem found.
     * Side effects: reads the documentation; ends the process with the exit status above.
     */
    public static void main(String[] args) {
        List<String> problems = new ArrayList<>();
        try {
            checkMirrors(problems);
            checkLinks(problems);
        } catch (IOException e) {
            System.err.println("checkdocs: " + e);
            System.exit(2);
        }
        if (!problems.isEmpty()) {
            problems.forEach(System.err::println);
            System.exit(1);
        }
        System.out.println("docs: ok");
    }

    /**
     * Compares each language directory under doc/ with doc/en-us, file by file.
     * Inputs: problems receives one line per difference.
     * Side effects: reads every Markdown file under doc/.
     */
    private static void checkMirrors(List<String> problems) throws IOException {
        TreeSet<String> want = markdown(SOURCE);
        List<Path> langs;
        try (Stream<Path> s = Files.list(DOC)) {
            langs = s.filter(Files::isDirectory).filter(d -> !d.equals(SOURCE)).sorted().toList();
        }
        for (Path dir : langs) {
            String name = slash(dir);
            TreeSet<String> got = markdown(dir);
            for (String f : got) {
                if (!want.contains(f)) {
                    problems.add(name + "/" + f + ": no doc/en-us counterpart");
                }
            }
            for (String f : want) {
                if (!got.contains(f)) {
                    problems.add(name + ": missing " + f);
                    continue;
                }
                Shape a = read(SOURCE.resolve(f));
                Shape b = read(dir.resolve(f));
                if (!a.headings().equals(b.headings())) {
                    problems.add(name + "/" + f + ": heading levels " + b.headings() + ", doc/en-us has " + a.headings());
                }
                if (a.tableRows() != b.tableRows()) {
                    problems.add(name + "/" + f + ": " + b.tableRows() + " table rows, doc/en-us has " + a.tableRows());
                }
                if (!a.code().equals(b.code())) {
                    problems.add(name + "/" + f + ": code blocks differ from doc/en-us");
                }
            }
        }
    }

    /**
     * Checks that every relative link target exists.
     * Inputs: problems receives one line per broken link.
     * Side effects: reads the top-level Markdown files and every Markdown file under doc/.
     * Notes: URLs with a scheme and pure anchors are skipped; anchors inside files are not checked.
     */
    private static void checkLinks(List<String> problems) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> top = Files.list(Path.of("."))) {
            top.filter(p -> p.toString().endsWith(".md")).sorted().forEach(files::add);
        }
        for (String f : markdown(DOC)) {
            files.add(DOC.resolve(f));
        }
        for (Path file : files) {
            for (String link : read(file).links()) {
                String target = link.split("#", 2)[0];
                if (target.isEmpty() || SCHEME.matcher(target).find()) {
                    continue;
                }
                if (!Files.exists(file.toAbsolutePath().getParent().resolve(target))) {
                    problems.add(slash(file.normalize()) + ": broken link " + link);
                }
            }
        }
    }

    /**
     * Lists the Markdown files under dir.
     * Returns: their paths relative to dir, with '/' separators, sorted.
     */
    private static TreeSet<String> markdown(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> slash(dir.relativize(p)))
                    .collect(TreeSet::new, TreeSet::add, TreeSet::addAll);
        }
    }

    /**
     * Reads a document's shape.
     * Returns: heading levels and table rows outside code, each fenced block verbatim (fences
     * included), and the link targets outside code.
     */
    private static Shape read(Path path) throws IOException {
        List<Integer> headings = new ArrayList<>();
        List<String> code = new ArrayList<>();
        List<String> links = new ArrayList<>();
        int tableRows = 0;
        StringBuilder block = null;
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith("```")) {
                if (block == null) {
                    block = new StringBuilder(line);
                } else {
                    code.add(block.append('\n').append(line).toString());
                    block = null;
                }
                continue;
            }
            if (block != null) {
                block.append('\n').append(line);
                continue;
            }
            Matcher h = HEADING.matcher(line);
            if (h.find()) {
                headings.add(h.group(1).length());
            } else if (line.startsWith("|")) {
                tableRows++;
            }
            Matcher l = LINK.matcher(line);
            while (l.find()) {
                links.add(l.group(1));
            }
        }
        return new Shape(headings, tableRows, code, links);
    }

    /** Formats a path with '/' separators, so messages and comparisons match on every OS. */
    private static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }
}
