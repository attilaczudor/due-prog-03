package hu.due.rewear.dao;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Consistent SQLite TEXT timestamp formatting ({@code yyyy-MM-dd HH:mm:ss})
 * that preserves chronological sort order in SQL queries.
 */
public final class DaoDateUtil {

    public static final DateTimeFormatter SQLITE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DaoDateUtil() {
    }

    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? null : SQLITE_FORMAT.format(dateTime);
    }

    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().replace('T', ' ');
        if (normalized.length() > 19) {
            normalized = normalized.substring(0, 19);
        }
        return LocalDateTime.parse(normalized, SQLITE_FORMAT);
    }
}
