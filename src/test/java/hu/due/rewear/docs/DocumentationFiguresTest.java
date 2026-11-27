package hu.due.rewear.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The figures the documents quote about this application have to be the figures the tree actually has.
 *
 * <p>Pass 29 went through five documents by hand and found the program design still claiming 220 sources
 * and 277 tests, the project plan a fifty-five table schema and 917 translation keys, and the stock-taking
 * document the figures of four passes earlier. Every one of those was true when it was written. That is
 * what makes a number in prose different from a sentence in prose: it has an expiry date nobody can see,
 * and a reader has no way to tell a current figure from a stale one.
 *
 * <p>So the numbers are asserted rather than proof-read. A document that says "60 tables" is making a claim
 * this test checks against {@code schema.sql}; if somebody adds a table and does not say so, the build says
 * so instead.
 *
 * <p>Two things are deliberately outside this. The optimization plan is a history — "131 tests" in the
 * record of pass 6 was true in pass 6 and must stay as it is — so it is not read at all. And a line in a
 * current document that is genuinely about the past is marked with an HTML comment, which renders as
 * nothing and reads as an exemption:
 *
 * <pre>- Brought the reports up to date (42 tables, 131 tests). &lt;!-- figures:historical --&gt;</pre>
 */
class DocumentationFiguresTest {

    /** Documents that describe the application as it is now. The pass history is not one of them. */
    private static final List<String> CURRENT_DOCUMENTS = List.of(
            "README.md",
            "docs/HANDOVER.md",
            "docs/State-of-the-Application.md",
            "docs/Projektterv.md",
            "docs/Programterv.md",
            "docs/Adatbazisterv.md",
            "docs/TASKS.md");

    /** A line saying so is about the past on purpose, and is left alone. */
    private static final String HISTORICAL = "<!-- figures:historical -->";

    /**
     * What a figure is called, in both languages, and which count it claims.
     *
     * <p>The phrasings are the ones the documents actually use. A new way of saying the same thing is not
     * checked until it is added here, which is the honest limit of a test like this one.
     */
    private static final Map<String, String> PHRASES = new LinkedHashMap<>();

    static {
        PHRASES.put("tables", "tables");
        PHRASES.put("táblás", "tables");
        PHRASES.put("alkalmazástábla", "tables");
        PHRASES.put("indexes", "indexes");
        PHRASES.put("deklarált index", "indexes");
        PHRASES.put("név szerint deklarált index", "indexes");
        PHRASES.put("tests", "tests");
        PHRASES.put("teszt", "tests");
        PHRASES.put("automatizált egység- és integrációs teszt", "tests");
        PHRASES.put("test classes", "testClasses");
        PHRASES.put("tesztosztály", "testClasses");
        PHRASES.put("tesztosztályban", "testClasses");
        PHRASES.put("main sources", "mainSources");
        PHRASES.put("main classes", "mainSources");
        PHRASES.put("fő java forrásfájl", "mainSources");
        PHRASES.put("translation keys", "translationKeys");
        PHRASES.put("kulcs", "translationKeys");
        PHRASES.put("en/hu kulcs", "translationKeys");
    }

    private static final Pattern FIGURE = Pattern.compile(
            "(?<![\\w.])(\\d[\\d,. ]{0,7}?)\\s*\\**\\s*(" + String.join("|", PHRASES.keySet()) + ")\\b",
            Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- what is actually there

    private static long count(Path file, String needle) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).split(Pattern.quote(needle), -1).length - 1;
    }

    /**
     * Test methods in one file.
     *
     * <p>Counted as the annotation on its own line rather than as the word anywhere, because this file
     * mentions the word while counting it, and a test that cannot count itself correctly is not in a
     * position to correct anybody else's arithmetic.
     */
    private static final Pattern TEST_ANNOTATION = Pattern.compile("(?m)^\\s*@Test\\s*$");

    private static long testMethods(Path file) throws IOException {
        return TEST_ANNOTATION.matcher(Files.readString(file, StandardCharsets.UTF_8)).results().count();
    }

    private static Map<String, Long> truth() throws IOException {
        Map<String, Long> truth = new LinkedHashMap<>();
        Path schema = Path.of("src/main/resources/db/schema.sql");
        truth.put("tables", count(schema, "CREATE TABLE"));
        truth.put("indexes", count(schema, "CREATE INDEX"));

        long tests = 0;
        long testClasses = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                long inThisFile = testMethods(file);
                tests += inThisFile;
                if (inThisFile > 0) {
                    testClasses++;
                }
            }
        }
        truth.put("tests", tests);
        truth.put("testClasses", testClasses);

        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            truth.put("mainSources", files.filter(f -> f.toString().endsWith(".java")).count());
        }

        List<String> lines = Files.readAllLines(Path.of("src/main/resources/i18n/translations.csv"),
                StandardCharsets.UTF_8);
        truth.put("translationKeys", lines.stream().filter(l -> !l.isBlank()).count() - 1);
        return truth;
    }

    // ---------------------------------------------------------------- the check

    @Test
    void everyFigureTheDocumentsQuoteIsTheFigureTheTreeHas() throws IOException {
        Map<String, Long> truth = truth();
        List<String> wrong = new ArrayList<>();
        int checked = 0;

        for (String name : CURRENT_DOCUMENTS) {
            Path document = Path.of(name);
            assertTrue(Files.exists(document), name + " is listed as a current document and is not there");
            String[] lines = Files.readString(document, StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.contains(HISTORICAL)) {
                    continue;
                }
                Matcher matcher = FIGURE.matcher(line);
                while (matcher.find()) {
                    // "JUnit 5 teszt" is a version followed by a word, not five tests.
                    String before = line.substring(0, matcher.start()).stripTrailing();
                    if (before.toLowerCase(java.util.Locale.ROOT).endsWith("junit")) {
                        continue;
                    }
                    String phrase = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
                    long claimed;
                    try {
                        claimed = Long.parseLong(matcher.group(1).replaceAll("[,. ]", ""));
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    Long actual = truth.get(PHRASES.get(phrase));
                    if (actual == null) {
                        wrong.add(name + ":" + (i + 1) + "  matched the phrase \"" + matcher.group(2)
                                + "\", which this test knows no figure for");
                        continue;
                    }
                    checked++;
                    if (claimed != actual) {
                        wrong.add(name + ":" + (i + 1) + "  says " + claimed + " " + matcher.group(2)
                                + ", the tree has " + actual + "\n      " + line.strip());
                    }
                }
            }
        }

        assertTrue(checked > 15, "Expected the documents to be full of figures, found " + checked);
        assertTrue(wrong.isEmpty(), wrong.size() + " figure(s) in the documents no longer match the tree."
                + " Correct the document, or mark the line " + HISTORICAL + " if it is about the past:\n  "
                + String.join("\n  ", wrong));
    }

    /**
     * Every link between documents resolves.
     *
     * <p>A dangling link is the other way a document rots: it survives a file being renamed and points at
     * nothing, which a reader only discovers at the moment they need the thing it promised.
     */
    @Test
    void everyLinkBetweenDocumentsLeadsSomewhere() throws IOException {
        Pattern link = Pattern.compile("\\[[^\\]]+\\]\\(([^)\\s]+)\\)");
        List<String> broken = new ArrayList<>();
        // The agent instructions are checked for links too: they exist to point at the other documents,
        // and a pointer that has rotted is worse than no pointer. Their figures are not checked — "four
        // display-dependent test classes" is a sentence about a subset, not a claim about the suite.
        List<Path> documents = new ArrayList<>(List.of(
                Path.of("README.md"), Path.of("AGENTS.md"), Path.of("CLAUDE.md")));
        try (Stream<Path> files = Files.list(Path.of("docs"))) {
            documents.addAll(files.filter(f -> f.toString().endsWith(".md")).sorted().toList());
        }

        for (Path document : documents) {
            String[] lines = Files.readString(document, StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = link.matcher(lines[i]);
                while (matcher.find()) {
                    String target = matcher.group(1).split("#")[0];
                    if (target.isEmpty() || target.startsWith("http") || target.startsWith("mailto:")) {
                        continue;
                    }
                    Path resolved = document.getParent() == null
                            ? Path.of(decode(target))
                            : document.getParent().resolve(decode(target));
                    if (!Files.exists(resolved)) {
                        broken.add(document + ":" + (i + 1) + "  → " + target);
                    }
                }
            }
        }

        assertTrue(broken.isEmpty(), "Links that lead nowhere:\n  " + String.join("\n  ", broken));
    }

    private static String decode(String target) {
        return java.net.URLDecoder.decode(target, StandardCharsets.UTF_8);
    }

    /**
     * The documents a new session is told to read first have to exist and say something.
     *
     * <p>Cheap, and it has a point: the handover is referenced from the README, the task board and the
     * stock-taking document, and a handover that quietly became an empty file would be discovered by the
     * one person least able to do anything about it.
     */
    @Test
    void theDocumentsANewSessionStartsFromAreThere() throws IOException {
        for (String name : List.of("docs/HANDOVER.md", "docs/TASKS.md", "docs/State-of-the-Application.md",
                "docs/Optimization-Plan.md", "README.md", "AGENTS.md")) {
            Path document = Path.of(name);
            assertTrue(Files.exists(document), name + " is missing");
            assertTrue(Files.readString(document, StandardCharsets.UTF_8).strip().length() > 500,
                    name + " is too short to be the document it claims to be");
        }
        assertTrue(Files.readString(Path.of("README.md"), StandardCharsets.UTF_8)
                        .contains("docs/HANDOVER.md"),
                "The README points a newcomer at the handover");
    }
}
