package hu.due.rewear.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cutting a SQL file into statements, for the semicolons that end one and the ones that do not.
 *
 * <p>This was {@code sql.split(";")} for twenty-five passes, and it worked, because nothing in the schema
 * had ever contained a semicolon anywhere else. It is the kind of thing that keeps working until the day
 * somebody adds a trigger — and then fails by cutting a statement in half and running the half that parses,
 * which is a far worse way to fail than refusing to start.
 */
class SqlScriptTest {

    @Test
    void theBundledSchemaIsCutIntoWholeStatements() throws IOException {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"), StandardCharsets.UTF_8);

        List<String> statements = SqlScript.statements(schema);

        assertTrue(statements.size() > 80, "The schema is made of many statements: " + statements.size());
        for (String statement : statements) {
            String head = statement.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                    .findFirst().orElse("");
            assertTrue(head.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE")
                            || head.toUpperCase(java.util.Locale.ROOT).startsWith("INSERT")
                            || head.toUpperCase(java.util.Locale.ROOT).startsWith("PRAGMA"),
                    "Every statement starts with a verb rather than the tail of the one before: " + head);
        }
    }

    @Test
    void aSemicolonInsideAStringDoesNotEndTheStatement() {
        List<String> statements = SqlScript.statements(
                "CREATE TABLE a (note TEXT DEFAULT 'one; two');\nCREATE TABLE b (id INTEGER);");

        assertEquals(2, statements.size(), "Two statements, not three: " + statements);
        assertTrue(statements.get(0).contains("'one; two'"));
    }

    @Test
    void aDoubledQuoteInsideAStringIsPartOfIt() {
        List<String> statements = SqlScript.statements(
                "INSERT INTO a VALUES ('it''s here; really');\nINSERT INTO b VALUES (1);");

        assertEquals(2, statements.size(), statements.toString());
        assertTrue(statements.get(0).contains("it''s here; really"));
    }

    @Test
    void aSemicolonInACommentDoesNotEndTheStatement() {
        List<String> statements = SqlScript.statements("""
                -- the next table; it holds notes
                CREATE TABLE a (id INTEGER);
                /* another one; also fine */
                CREATE TABLE b (id INTEGER);
                """);

        assertEquals(2, statements.size(), statements.toString());
    }

    @Test
    void aTriggerBodyIsOneStatementHoweverManySemicolonsItHas() {
        List<String> statements = SqlScript.statements("""
                CREATE TRIGGER keep_time AFTER UPDATE ON a BEGIN
                    UPDATE a SET changed = 1 WHERE id = NEW.id;
                    UPDATE b SET changed = 1 WHERE id = NEW.id;
                END;
                CREATE TABLE c (id INTEGER);
                """);

        assertEquals(2, statements.size(), "The trigger is one statement: " + statements);
        assertTrue(statements.get(0).contains("UPDATE b SET changed"),
                "Including its last line, which the old split dropped on the floor");
        assertTrue(statements.get(0).trim().endsWith("END"));
    }

    @Test
    void aQuotedIdentifierMayContainAnything() {
        List<String> statements = SqlScript.statements(
                "CREATE TABLE \"odd; name\" (id INTEGER);\nCREATE TABLE b (id INTEGER);");

        assertEquals(2, statements.size(), statements.toString());
    }

    @Test
    void aTrailingCommentIsNotAStatement() {
        List<String> statements = SqlScript.statements("CREATE TABLE a (id INTEGER);\n-- nothing after this\n");

        assertEquals(1, statements.size(), statements.toString());
        assertFalse(statements.get(0).endsWith(";"), "The separator is not part of the statement");
    }
}
