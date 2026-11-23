package hu.due.rewear.model;

import java.text.Normalizer;
import java.util.Locale;

/**
 * External SMTP & IMAP mail gateway configuration plus the company email domain
 * managed exclusively by {@link RoleCode#SUPER_ADMIN} and persisted portably in {@code app_settings}.
 */
public record EmailSettings(
        String companyDomain,
        String smtpHost,
        int smtpPort,
        String smtpSecurity,
        String smtpUsername,
        String smtpPassword,
        String imapHost,
        int imapPort,
        String imapSecurity,
        String imapUsername,
        String imapPassword,
        boolean simulationMode
) {
    public static final String DEFAULT_DOMAIN = "rewear-wms.hu";
    public static final String DEFAULT_SMTP_HOST = "smtp.rewear-wms.hu";
    public static final int DEFAULT_SMTP_PORT = 587;
    public static final String DEFAULT_IMAP_HOST = "imap.rewear-wms.hu";
    public static final int DEFAULT_IMAP_PORT = 993;

    public EmailSettings {
        companyDomain = sanitizeDomain(companyDomain);
        smtpHost = (smtpHost == null || smtpHost.isBlank()) ? "smtp." + companyDomain : smtpHost.trim();
        smtpPort = smtpPort > 0 && smtpPort <= 65535 ? smtpPort : DEFAULT_SMTP_PORT;
        smtpSecurity = normalizeSecurity(smtpSecurity, "STARTTLS");
        smtpUsername = smtpUsername == null ? "" : smtpUsername.trim();
        smtpPassword = smtpPassword == null ? "" : smtpPassword;
        imapHost = (imapHost == null || imapHost.isBlank()) ? "imap." + companyDomain : imapHost.trim();
        imapPort = imapPort > 0 && imapPort <= 65535 ? imapPort : DEFAULT_IMAP_PORT;
        imapSecurity = normalizeSecurity(imapSecurity, "SSL/TLS");
        imapUsername = imapUsername == null ? "" : imapUsername.trim();
        imapPassword = imapPassword == null ? "" : imapPassword;
    }

    public static EmailSettings defaults() {
        return new EmailSettings(
                DEFAULT_DOMAIN,
                DEFAULT_SMTP_HOST,
                DEFAULT_SMTP_PORT,
                "STARTTLS",
                "postmaster@" + DEFAULT_DOMAIN,
                "",
                DEFAULT_IMAP_HOST,
                DEFAULT_IMAP_PORT,
                "SSL/TLS",
                "postmaster@" + DEFAULT_DOMAIN,
                "",
                true
        );
    }

    /** Generates a normalized corporate email like {@code attila.czudor@rewear-wms.hu} from user's name. */
    public String generateUserEmail(String firstName, String lastName, String fallbackUsername) {
        String fn = asciiSlug(firstName);
        String ln = asciiSlug(lastName);
        String local;
        if (!fn.isEmpty() && !ln.isEmpty()) {
            local = fn + "." + ln;
        } else if (!ln.isEmpty()) {
            local = ln;
        } else if (!fn.isEmpty()) {
            local = fn;
        } else {
            local = asciiSlug(fallbackUsername);
            if (local.isEmpty()) {
                local = "user";
            }
        }
        return local + "@" + companyDomain;
    }

    public static String sanitizeDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DOMAIN;
        }
        String d = raw.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("@")) {
            d = d.substring(1);
        }
        d = d.replaceAll("[^a-z0-9.-]", "");
        return d.contains(".") ? d : DEFAULT_DOMAIN;
    }

    private static String normalizeSecurity(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "STARTTLS", "SSL/TLS", "NONE" -> u;
            default -> fallback;
        };
    }

    private static String asciiSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.+|\\.+$", "");
    }
}
