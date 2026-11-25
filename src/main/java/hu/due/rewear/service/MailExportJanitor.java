package hu.due.rewear.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Removes abandoned e-mail attachment staging folders from the exports directory.
 * <p>
 * {@code DocumentPreviewDialog} writes every outgoing document into its own {@code exports/mail-*}
 * folder. That file <b>is</b> the stored attachment: the Sent copy and each recipient's Inbox copy
 * point at it by absolute path, and nothing copies it anywhere more durable. A folder can therefore
 * only be removed once no mail row references it. The usual leftovers are compositions that were
 * cancelled or whose message was later deleted.
 * <p>
 * A folder is removed only when <em>both</em> hold: it is older than the retention period, and no
 * mail row references a file inside it. References are matched by folder name rather than full
 * path, so moving the application directory (which changes the stored absolute prefix) can never
 * make a still-referenced attachment look orphaned. When in doubt the folder is kept.
 */
public final class MailExportJanitor {

    private static final Logger LOG = LoggerFactory.getLogger(MailExportJanitor.class);

    /** Folder-name prefix {@code DocumentPreviewDialog} gives every attachment staging folder. */
    static final String BATCH_PREFIX = "mail-";

    private MailExportJanitor() {
    }

    /**
     * Deletes old, unreferenced {@code mail-*} folders under {@code exportsDir}.
     *
     * @param exportsDir      the exports directory to clean
     * @param referencedPaths attachment paths currently stored on any mail row, in any folder
     * @param retention       minimum age before an unreferenced folder may be removed
     * @param now             the reference instant, injectable for tests
     * @return how many folders were removed
     */
    public static int pruneUnreferenced(Path exportsDir, Set<String> referencedPaths,
                                        Duration retention, Instant now) {
        Set<String> referencedBatches = batchNames(referencedPaths);
        Instant cutoff = now.minus(retention);
        int removed = 0;
        try (Stream<Path> children = Files.list(exportsDir)) {
            for (Path batch : children.filter(Files::isDirectory).toList()) {
                String name = batch.getFileName().toString();
                if (!name.startsWith(BATCH_PREFIX) || referencedBatches.contains(name)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(batch).toInstant().isAfter(cutoff)) {
                        continue;
                    }
                    deleteRecursively(batch);
                    removed++;
                } catch (IOException perBatch) {
                    LOG.debug("Could not prune mail export {}", batch, perBatch);
                }
            }
        } catch (IOException ex) {
            LOG.debug("Could not scan exports directory {}", exportsDir, ex);
        }
        if (removed > 0) {
            LOG.info("Pruned {} unreferenced e-mail export folder(s) older than {} days",
                    removed, retention.toDays());
        }
        return removed;
    }

    /** Names of the {@code mail-*} folders that the given stored attachment paths live in. */
    private static Set<String> batchNames(Set<String> referencedPaths) {
        Set<String> names = new HashSet<>();
        for (String stored : referencedPaths) {
            if (stored == null || stored.isBlank()) {
                continue;
            }
            try {
                Path parent = Path.of(stored).getParent();
                if (parent != null && parent.getFileName() != null) {
                    names.add(parent.getFileName().toString());
                }
            } catch (RuntimeException unparsable) {
                // An unparsable stored path cannot be matched to a folder, so it protects nothing.
                LOG.debug("Ignoring unparsable attachment path '{}'", stored);
            }
        }
        return names;
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> contents = Files.walk(root)) {
            for (Path victim : contents.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(victim);
            }
        }
    }
}
