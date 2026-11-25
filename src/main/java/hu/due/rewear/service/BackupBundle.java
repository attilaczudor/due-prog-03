package hu.due.rewear.service;

import hu.due.rewear.util.BusinessClock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The on-disk format of a full backup: one ZIP holding the database snapshot, the document folders that
 * live beside it, and a manifest listing every entry with its SHA-256 and size.
 * <pre>
 *   manifest.txt
 *   database.db
 *   files/invoices/...  files/receipts/...  files/mail-attachments/...
 * </pre>
 * Reading is deliberately strict because a bundle is an untrusted input: every entry must be named in the
 * manifest and vice versa, names may not escape the staging folder, and each file is size-capped and
 * hash-checked while it is extracted.
 */
final class BackupBundle {

    static final String MANIFEST = "manifest.txt";
    static final String DATABASE = "database.db";
    static final String FILES_PREFIX = "files/";
    /** Folders under the data directory that hold documents referenced by database rows. */
    static final List<String> FOLDERS = List.of("invoices", "receipts", "mail-attachments");

    private static final String FORMAT_LINE = "rewear-backup-bundle 1";
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int BUFFER = 64 * 1024;

    record Written(int files, long bytes) { }

    record Extracted(Path database, int files) { }

    private BackupBundle() { }

    /** True when the file starts with a ZIP local-header signature. */
    static boolean looksLikeBundle(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        } catch (IOException e) {
            return false;
        }
    }

    static Written write(Path zipFile, Path databaseSnapshot, Path dataDir) throws IOException {
        List<String> manifest = new ArrayList<>();
        manifest.add(FORMAT_LINE);
        manifest.add("created=" + BusinessClock.now());
        int files = 0;
        long bytes = 0;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            bytes += addEntry(zip, DATABASE, databaseSnapshot, manifest);
            for (String folder : FOLDERS) {
                Path root = dataDir.resolve(folder);
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                List<Path> found;
                try (Stream<Path> walk = Files.walk(root)) {
                    found = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .filter(p -> !isScratch(p))
                            .sorted()
                            .toList();
                }
                for (Path file : found) {
                    String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                    bytes += addEntry(zip, FILES_PREFIX + folder + "/" + relative, file, manifest);
                    files++;
                }
            }
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(String.join("\n", manifest).concat("\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new Written(files, bytes);
    }

    /** Verifies the bundle completely and unpacks it into {@code staging}; nothing outside it is touched. */
    static Extracted extract(Path zipFile, Path staging) throws IOException {
        Path root = staging.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Map<String, Declared> declared = readManifest(zip);

            Set<String> present = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || MANIFEST.equals(entry.getName())) {
                    continue;
                }
                if (!present.add(entry.getName())) {
                    throw new IOException("Backup bundle is invalid: duplicate entry " + entry.getName());
                }
                if (!declared.containsKey(entry.getName())) {
                    throw new IOException("Backup bundle is invalid: unlisted entry " + entry.getName());
                }
            }
            for (String name : declared.keySet()) {
                if (!present.contains(name)) {
                    throw new IOException("Backup bundle is incomplete: missing " + name);
                }
            }
            if (!declared.containsKey(DATABASE)) {
                throw new IOException("Backup bundle does not contain a database.");
            }

            long total = declared.values().stream().mapToLong(Declared::size).sum();
            long usable = Files.getFileStore(root).getUsableSpace();
            if (total > usable) {
                throw new IOException("Not enough free disk space to restore this backup (" + total + " bytes needed).");
            }

            for (Map.Entry<String, Declared> e : declared.entrySet()) {
                Path target = root.resolve(e.getKey()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Backup bundle is invalid: unsafe path " + e.getKey());
                }
                Files.createDirectories(target.getParent());
                unpack(zip, zip.getEntry(e.getKey()), target, e.getValue());
            }
            return new Extracted(root.resolve(DATABASE), declared.size() - 1);
        }
    }

    // ---- writing ----------------------------------------------------------------------------

    private static boolean isScratch(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(".rewear-") || name.endsWith(".tmp");
    }

    private static long addEntry(ZipOutputStream zip, String name, Path file, List<String> manifest) throws IOException {
        if (name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0 || name.indexOf('\t') >= 0) {
            throw new IOException("File name cannot be stored in a backup: " + name.replaceAll("\\p{Cntrl}", "?"));
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(Files.getLastModifiedTime(file).toMillis());
        zip.putNextEntry(entry);
        MessageDigest digest = sha256();
        long size;
        try (InputStream in = Files.newInputStream(file)) {
            size = pump(in, zip, digest, Long.MAX_VALUE);
        }
        zip.closeEntry();
        manifest.add(hex(digest.digest()) + "\t" + size + "\t" + name);
        return size;
    }

    // ---- reading ----------------------------------------------------------------------------

    private record Declared(String sha256, long size) { }

    private static Map<String, Declared> readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(MANIFEST);
        if (entry == null) {
            throw new IOException("Not a ReWear backup bundle (manifest is missing).");
        }
        if (entry.getSize() < 0 || entry.getSize() > MAX_MANIFEST_BYTES) {
            throw new IOException("Backup bundle is invalid: manifest has an unreasonable size.");
        }
        String text;
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw new IOException("Backup bundle is invalid: manifest is too large.");
            }
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        String[] lines = text.split("\n");
        if (lines.length == 0 || !FORMAT_LINE.equals(lines[0])) {
            throw new IOException("Unsupported backup bundle format.");
        }
        Map<String, Declared> declared = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() || line.startsWith("created=")) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length != 3 || !parts[0].matches("[0-9a-f]{64}") || !parts[1].matches("[0-9]{1,15}")) {
                throw new IOException("Backup bundle is invalid: malformed manifest line " + (i + 1));
            }
            requireSafeName(parts[2]);
            if (declared.put(parts[2], new Declared(parts[0], Long.parseLong(parts[1]))) != null) {
                throw new IOException("Backup bundle is invalid: duplicate manifest entry " + parts[2]);
            }
        }
        return declared;
    }

    private static void requireSafeName(String name) throws IOException {
        boolean allowed = DATABASE.equals(name);
        for (String folder : FOLDERS) {
            allowed |= name.startsWith(FILES_PREFIX + folder + "/") && name.length() > (FILES_PREFIX + folder + "/").length();
        }
        if (!allowed || name.indexOf('\\') >= 0 || name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IOException("Backup bundle is invalid: unexpected entry name " + name.replaceAll("\\p{Cntrl}", "?"));
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Backup bundle is invalid: unsafe path " + name);
            }
        }
    }

    private static void unpack(ZipFile zip, ZipEntry entry, Path target, Declared declared) throws IOException {
        MessageDigest digest = sha256();
        long written;
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = Files.newOutputStream(target)) {
            // One byte over the declared size is enough to prove the entry lies about its length.
            written = pump(in, out, digest, declared.size() + 1);
        }
        if (written != declared.size()) {
            throw new IOException("Backup bundle is damaged: size mismatch for " + entry.getName());
        }
        if (!hex(digest.digest()).equals(declared.sha256())) {
            throw new IOException("Backup bundle is damaged: checksum mismatch for " + entry.getName());
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static long pump(InputStream in, OutputStream out, MessageDigest digest, long limit) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long total = 0;
        while (total < limit) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, limit - total));
            if (read < 0) {
                break;
            }
            digest.update(buffer, 0, read);
            out.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
