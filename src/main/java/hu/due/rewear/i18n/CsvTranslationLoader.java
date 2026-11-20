package hu.due.rewear.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RFC-4180 compliant UTF-8 CSV translation loader.
 * <p>
 * Expected header format: {@code key,en,hu[,<any_future_lang>...]}
 * Adding a new column to the CSV automatically makes that language available to the application.
 */
public final class CsvTranslationLoader {

    public record TranslationBundle(
            List<String> languages,
            Map<String, Map<String, String>> entries
    ) {
        public TranslationBundle {
            languages = List.copyOf(languages);
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> e : entries.entrySet()) {
                copy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
            }
            entries = Collections.unmodifiableMap(copy);
        }
    }

    public TranslationBundle load(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(reader);
        }
    }

    public TranslationBundle load(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return load(reader);
        }
    }

    public TranslationBundle load(Reader reader) throws IOException {
        Reader buffered = reader.markSupported() ? reader : new BufferedReader(reader);
        List<List<String>> rows = parseCsvRows(buffered);
        List<String> languages = new ArrayList<>();
        Map<String, Map<String, String>> entries = new LinkedHashMap<>();

        boolean headerParsed = false;
        for (List<String> row : rows) {
            if (isBlankOrComment(row)) {
                continue;
            }
            if (!headerParsed) {
                if (row.size() < 2) {
                    throw new IOException("Translation CSV header must contain 'key' and at least one language column.");
                }
                String firstCol = stripBom(row.get(0)).trim().toLowerCase(Locale.ROOT);
                if (!"key".equals(firstCol)) {
                    throw new IOException("Translation CSV first header column must be 'key', found: " + firstCol);
                }
                for (int i = 1; i < row.size(); i++) {
                    String lang = row.get(i).trim().toLowerCase(Locale.ROOT);
                    if (!lang.isEmpty() && !languages.contains(lang)) {
                        languages.add(lang);
                    }
                }
                if (languages.isEmpty()) {
                    throw new IOException("Translation CSV must declare at least one non-empty language column.");
                }
                headerParsed = true;
                continue;
            }

            String key = row.get(0).trim();
            if (key.isEmpty()) {
                continue;
            }
            Map<String, String> langMap = entries.computeIfAbsent(key, k -> new LinkedHashMap<>());
            for (int i = 0; i < languages.size(); i++) {
                int colIndex = i + 1;
                String value = colIndex < row.size() ? row.get(colIndex) : "";
                if (!value.isEmpty()) {
                    langMap.put(languages.get(i), value);
                }
            }
        }

        if (!headerParsed) {
            throw new IOException("Translation CSV is empty or missing header row.");
        }
        return new TranslationBundle(languages, entries);
    }

    private static boolean isBlankOrComment(List<String> row) {
        if (row.isEmpty()) {
            return true;
        }
        if (row.size() == 1) {
            String cell = stripBom(row.get(0)).trim();
            return cell.isEmpty() || cell.startsWith("#");
        }
        String first = stripBom(row.get(0)).trim();
        return first.startsWith("#");
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    /**
     * Parses RFC-4180 CSV content supporting quoted fields, embedded commas, newlines inside quotes,
     * and doubled double-quotes ({@code ""}).
     */
    static List<List<String>> parseCsvRows(Reader reader) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();

        boolean inQuotes = false;
        boolean fieldStarted = false;
        int ch;
        while ((ch = reader.read()) != -1) {
            char c = (char) ch;
            if (inQuotes) {
                if (c == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        inQuotes = false;
                        if (next != -1) {
                            reader.reset();
                        }
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"' && !fieldStarted) {
                    inQuotes = true;
                    fieldStarted = true;
                } else if (c == ',') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                } else if (c == '\r') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.reset();
                    }
                    currentRow.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                } else if (c == '\n') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                } else {
                    field.append(c);
                    fieldStarted = true;
                }
            }
        }

        if (fieldStarted || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow);
        }
        return rows;
    }

    public static String escapeCsvField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needsQuotes) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
