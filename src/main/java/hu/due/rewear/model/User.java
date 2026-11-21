package hu.due.rewear.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record User(
        long id,
        String username,
        String passwordHash,
        String fullName,
        String email,
        String phone,
        byte[] avatarPng,
        long roleId,
        RoleCode roleCode,
        String roleName,
        boolean active,
        LocalDateTime createdAt
) {
    /**
     * Computes a crisp 1-2 character uppercase monogram from the user's full name
     * (ignoring parenthetical suffixes such as "(Admin)"), falling back to username.
     */
    public String initials() {
        String source = (fullName != null && !fullName.isBlank()) ? fullName : username;
        if (source == null || source.isBlank()) {
            return "?";
        }
        String cleaned = source.replaceAll("\\([^)]*\\)", " ").trim();
        if (cleaned.isEmpty()) {
            cleaned = source.trim();
        }
        List<String> words = new ArrayList<>();
        for (String part : cleaned.split("\\s+")) {
            if (!part.isBlank()) {
                words.add(part);
            }
        }
        if (words.isEmpty()) {
            return "?";
        }
        if (words.size() == 1) {
            String w = words.get(0);
            return w.substring(0, Math.min(2, w.length())).toUpperCase(Locale.ROOT);
        }
        String first = words.get(0).substring(0, 1);
        String last = words.get(words.size() - 1).substring(0, 1);
        return (first + last).toUpperCase(Locale.ROOT);
    }

    /** Display name without redundant trailing role tag like "(Admin)". */
    public String displayName() {
        if (fullName == null || fullName.isBlank()) {
            return username != null ? username : "";
        }
        String stripped = fullName.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        return stripped.isEmpty() ? fullName.trim() : stripped;
    }
}
