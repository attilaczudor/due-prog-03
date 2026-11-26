package hu.due.rewear.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural guard: every translation key the code asks for by name exists in the bundle.
 * <p>
 * A missing key is not an exception — {@code I18nManager} renders it as {@code [the.key]} and the screen comes
 * up with a header reading <em>[role.FORKLIFT]</em>. Nothing fails, so nobody finds out until somebody looks
 * at that screen, which is how two of these survived several passes. Keys assembled at runtime (a prefix plus
 * an enum name) cannot be checked this way and are skipped.
 */
class TranslationKeyUsageTest {

    private static final Pattern LOOKUP = Pattern.compile("\\bt\\(\\s*\"([A-Za-z0-9_][A-Za-z0-9_.]*)\"");
    /** Any string literal shaped like a key, wherever it appears — most are handed to a label helper. */
    private static final Pattern DOTTED_LITERAL =
            Pattern.compile("\"([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)\"");
    /**
     * Dotted literals that only look like keys: a navigation target, an async task name and a file name. A
     * new one belongs here, with a word on what it is — the point of the list is that it stays short.
     */
    private static final Set<String> NOT_KEYS =
            Set.of("admin.translations", "inventory.load", "app.properties");

    private static Set<String> bundleKeys() throws IOException {
        Set<String> known = new HashSet<>();
        for (String line : Files.readAllLines(Path.of("src/main/resources/i18n/translations.csv"),
                StandardCharsets.UTF_8)) {
            int comma = line.indexOf(',');
            if (comma > 0) {
                known.add(line.substring(0, comma).strip());
            }
        }
        assertTrue(known.size() > 500, "The bundle itself did not load: " + known.size());
        return known;
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void everyTranslationKeyAskedForByNameExists() throws IOException {
        Set<String> known = bundleKeys();
        List<String> missing = new ArrayList<>();
        for (Path file : sources()) {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = LOOKUP.matcher(lines[i]);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    // A key that ends in a dot is a prefix the code completes at runtime, and one with no
                    // dot at all is some other single-argument t(...) rather than a lookup.
                    if (key.endsWith(".") || !key.contains(".") || known.contains(key)) {
                        continue;
                    }
                    missing.add(file + ":" + (i + 1) + "  " + key);
                }
            }
        }

        assertTrue(missing.isEmpty(), "Translation keys the bundle does not have, which render as [the.key]"
                + " on screen:\n" + String.join("\n", missing));
    }

    /**
     * The defect that prompted this test was not a {@code t("...")} call at all: the key was handed to a
     * table-header helper as a plain string, so no lookup appeared in that line. This checks every literal
     * shaped like a key whose namespace the bundle knows, wherever it is written.
     */
    @Test
    void noLiteralInANamespaceTheBundleKnowsIsAnUnknownKey() throws IOException {
        Set<String> known = bundleKeys();
        Set<String> namespaces = new HashSet<>();
        for (String key : known) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                namespaces.add(key.substring(0, dot + 1));
            }
        }

        List<String> suspects = new ArrayList<>();
        for (Path file : sources()) {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                Matcher matcher = DOTTED_LITERAL.matcher(lines[i]);
                while (matcher.find()) {
                    String literal = matcher.group(1);
                    if (known.contains(literal) || NOT_KEYS.contains(literal)) {
                        continue;
                    }
                    if (namespaces.stream().anyMatch(literal::startsWith)) {
                        suspects.add(file + ":" + (i + 1) + "  " + literal);
                    }
                }
            }
        }

        assertTrue(suspects.isEmpty(), "These read like translation keys in a namespace the bundle has, but"
                + " the bundle has no such key; add the key, or list the literal in NOT_KEYS with a word on"
                + " what it is:\n" + String.join("\n", suspects));
    }
}
