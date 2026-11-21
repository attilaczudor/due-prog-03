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
        String lastName,
        String firstName,
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
     * Computes a crisp 1-2 character uppercase monogram from lastName + firstName,
     * falling back to fullName or username.
     */
    public String initials() {
        String ln = safeLastName();
        String fn = safeFirstName();
        if (!ln.isEmpty() && !fn.isEmpty()) {
            return (ln.substring(0, 1) + fn.substring(0, 1)).toUpperCase(Locale.ROOT);
        }
        String source = !displayName().isBlank() ? displayName() : username;
        if (source == null || source.isBlank()) {
            return "?";
        }
        List<String> words = new ArrayList<>();
        for (String part : source.trim().split("\\s+")) {
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
        return (words.get(0).substring(0, 1) + words.get(words.size() - 1).substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    public String safeLastName() {
        if (lastName != null && !lastName.isBlank()) {
            return lastName.trim();
        }
        String clean = stripRoleSuffix(fullName);
        if (clean.isEmpty()) {
            return "";
        }
        String[] parts = clean.split("\\s+", 2);
        return parts[0];
    }

    public String safeFirstName() {
        if (firstName != null && !firstName.isBlank()) {
            return firstName.trim();
        }
        String clean = stripRoleSuffix(fullName);
        if (clean.isEmpty()) {
            return "";
        }
        String[] parts = clean.split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    /** Clean display name without any parenthetical role tag. */
    public String displayName() {
        String ln = safeLastName();
        String fn = safeFirstName();
        if (!ln.isEmpty() || !fn.isEmpty()) {
            return (ln + " " + fn).trim();
        }
        String clean = stripRoleSuffix(fullName);
        return !clean.isEmpty() ? clean : (username != null ? username : "");
    }

    private static String stripRoleSuffix(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s*\\([^)]*\\)\\s*", " ").trim();
    }
}
