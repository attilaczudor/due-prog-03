package hu.due.rewear.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cuts a bundled SQL file into the statements it is made of.
 *
 * <p>It used to be {@code sql.split(";")}, which works for exactly as long as no semicolon appears anywhere
 * except at the end of a statement. That is true of the schema today and stops being true the first time
 * somebody writes a default of {@code 'a; b'}, a comment with a semicolon in it, or a trigger — whose whole
 * body sits between {@code BEGIN} and {@code END} and is full of them. The failure would not be a clear one
 * either: the file would be cut in the middle of a statement and the half of it that parsed would run.
 *
 * <p>So the semicolons that end statements are told apart from the ones that do not: those inside a quoted
 * string, inside a quoted identifier, inside a comment, and inside a trigger body.
 */
final class SqlScript {

    private SqlScript() {
    }

    static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int triggerDepth = 0;
        boolean inTrigger = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            // A line comment runs to the end of the line, semicolons and all.
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? sql.length() : end;
                current.append(sql, i, end);
                i = end - 1;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? sql.length() : end + 2;
                current.append(sql, i, end);
                i = end - 1;
                continue;
            }
            // A quoted string or identifier. Inside it, a quote doubled is the quote itself.
            if (c == '\'' || c == '"' || c == '`') {
                int end = closingQuote(sql, i, c);
                current.append(sql, i, end);
                i = end - 1;
                continue;
            }
            if (c == '[') {
                int end = sql.indexOf(']', i);
                end = end < 0 ? sql.length() : end + 1;
                current.append(sql, i, end);
                i = end - 1;
                continue;
            }

            current.append(c);

            if (isWordEnd(sql, i)) {
                String word = lastWord(current);
                if ("TRIGGER".equals(word) && startsWithCreate(current)) {
                    inTrigger = true;
                } else if (inTrigger && "BEGIN".equals(word)) {
                    triggerDepth++;
                } else if (inTrigger && "END".equals(word) && triggerDepth > 0) {
                    triggerDepth--;
                }
            }

            if (c == ';' && triggerDepth == 0) {
                add(statements, current);
                current.setLength(0);
                inTrigger = false;
            }
        }
        add(statements, current);
        return List.copyOf(statements);
    }

    private static int closingQuote(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    /** True when the character just written ends a word, so the word can be looked at. */
    private static boolean isWordEnd(String sql, int index) {
        if (!Character.isLetter(sql.charAt(index))) {
            return false;
        }
        int next = index + 1;
        return next >= sql.length() || !Character.isLetterOrDigit(sql.charAt(next)) && sql.charAt(next) != '_';
    }

    private static String lastWord(StringBuilder text) {
        int end = text.length();
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '_')) {
            start--;
        }
        return text.substring(start, end).toUpperCase(Locale.ROOT);
    }

    private static boolean startsWithCreate(StringBuilder statement) {
        String head = statement.toString().stripLeading();
        return head.toUpperCase(Locale.ROOT).startsWith("CREATE");
    }

    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (!statement.isEmpty() && !isOnlyComments(statement)) {
            statements.add(statement);
        }
    }

    /** A tail of the file that is nothing but comments is not a statement to run. */
    private static boolean isOnlyComments(String statement) {
        for (String line : statement.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                return false;
            }
        }
        return true;
    }
}
