package hu.due.rewear.service;

import hu.due.rewear.config.ChangeLog;
import hu.due.rewear.config.SyncPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule for what happens when two machines have changed the same row.
 *
 * <p>Two classes and not three. The third — "the owner's change wins" — is the one everybody reaches for,
 * and it cannot be applied from what the log records: a machine is not a person, and a sorter's own run
 * edited by the shift leader from the office is not the same case as the same run edited twice by two
 * sorters. Even with the person recorded, which the log now does, who outranks whom is the site's to say.
 *
 * <p>What is left is a question about the data rather than about the company: a table nobody updates cannot
 * be disagreed about. These tests hold that claim to the code rather than to a comment.
 */
class SyncPolicyTest {

    @Test
    void everyLoggedTableSaysHowTwoCopiesOfItComeBackTogether() {
        assertEquals(ChangeLog.LOGGED_TABLES.stream().sorted().toList(),
                SyncPolicy.tables().stream().sorted().toList(),
                "A table worth logging is a table worth saying how to merge, and the other way round");

        for (String table : ChangeLog.LOGGED_TABLES) {
            SyncPolicy.Rule rule = SyncPolicy.rule(table);
            assertFalse(rule.reason() == null || rule.reason().isBlank(),
                    table + " has a rule with no reason, which is a rule nobody can argue with");
            assertTrue(rule.reason().length() > 40,
                    table + "'s reason has to be worth reading when somebody is settling a disagreement");
        }
    }

    @Test
    void aTableNobodyHasARuleForIsRefusedRatherThanGuessedAt() {
        assertThrows(IllegalArgumentException.class, () -> SyncPolicy.rule("invoices"),
                "Invoices are not synced, and quietly treating them as mergeable would be worse than saying so");
    }

    /**
     * The append-only claim is a claim about the application, so it is checked against the application.
     *
     * <p>If somebody adds an {@code UPDATE} against one of these tables tomorrow, the merge rule that says
     * two copies of it can simply be brought together stops being true — silently, and in a way that would
     * only show up as lost work after a sync.
     */
    @Test
    void theInsertOnlyTablesReallyAreInsertOnly() throws IOException {
        List<String> appendOnly = SyncPolicy.tables().stream().filter(SyncPolicy::isAppendOnly).toList();
        assertEquals(3, appendOnly.size(), "Three of the fourteen: " + appendOnly);

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String table : appendOnly) {
                    if (source.contains("UPDATE " + table + " ") || source.contains("UPDATE " + table + "\n")) {
                        offenders.add(file.getFileName() + " updates " + table);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "These tables merge by being brought together, which only holds while nobody edits a row"
                        + " of them:\n" + String.join("\n", offenders));
    }

    @Test
    void everythingElseIsHeldForAPersonRatherThanDecidedByRule() {
        List<String> held = SyncPolicy.tables().stream()
                .filter(table -> !SyncPolicy.isAppendOnly(table)).toList();

        assertEquals(11, held.size(), held.toString());
        assertTrue(held.contains("inventory_packages"), "A package is in one place");
        assertTrue(held.contains("sorting_raw_lots"),
                "What is left of a collection is a running total, and the log carries what changed rather"
                        + " than by how much");
        assertTrue(held.contains("shift_handovers"), "One sheet per shift, and the database says so");
    }
}
