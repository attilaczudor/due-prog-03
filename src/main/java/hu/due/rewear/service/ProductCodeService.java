package hu.due.rewear.service;

import hu.due.rewear.model.ParsedProductCode;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds, validates, and parses structured second-hand clothing package QR codes.
 * <p>
 * Convention:
 * {@code YYYY-MM-DD-HH-MM-<WHATFOR>-<TYPE>-<QUALITY>-<PACKAGETYPE>-<WEIGHT>-<HEX2>}
 * Example: {@code 2026-11-12-11-23-L-TSH-A-A-25-3F}
 */
public final class ProductCodeService {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm");

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("^[A-Z0-9]{1,8}$");
    private static final Pattern HEX2_PATTERN = Pattern.compile("^[0-9A-F]{2}$");
    private static final Pattern FULL_CODE_PATTERN = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})"
                    + "-([A-Z0-9]{1,8})"
                    + "-([A-Z0-9]{1,8})"
                    + "-([A-Z0-9]{1,8})"
                    + "-([A-Z0-9]{1,8})"
                    + "-(\\d+(?:\\.\\d{1,2})?)"
                    + "-([0-9A-F]{2})$"
    );

    private final SecureRandom random;

    public ProductCodeService() {
        this(new SecureRandom());
    }

    public ProductCodeService(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Generates a random 2-digit uppercase hexadecimal suffix ({@code 00}..{@code FF}).
     */
    public String generateHexSuffix() {
        return String.format(Locale.ROOT, "%02X", random.nextInt(256));
    }

    /**
     * Normalizes user-entered dictionary codes into uppercase ASCII alphanumeric tokens (1–8 chars, no hyphens).
     */
    public String normalizeCodeSegment(String raw) {
        if (raw == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD);
        String ascii = decomposed.replaceAll("\\p{M}+", "");
        String cleaned = ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
    }

    public boolean isValidCodeSegment(String segment) {
        return segment != null && SEGMENT_PATTERN.matcher(segment).matches();
    }

    public String buildCode(
            LocalDateTime timestamp,
            String targetGroupCode,
            String garmentTypeCode,
            String qualityGradeCode,
            String packageTypeCode,
            BigDecimal weightKg,
            String hexSuffix
    ) {
        Objects.requireNonNull(timestamp, "timestamp");
        String target = requireValidSegment(targetGroupCode, "targetGroupCode");
        String garment = requireValidSegment(garmentTypeCode, "garmentTypeCode");
        String quality = requireValidSegment(qualityGradeCode, "qualityGradeCode");
        String pkg = requireValidSegment(packageTypeCode, "packageTypeCode");
        String weightStr = formatWeight(weightKg);
        String hex = requireValidHex(hexSuffix);

        return TIMESTAMP_FORMATTER.format(timestamp)
                + "-" + target
                + "-" + garment
                + "-" + quality
                + "-" + pkg
                + "-" + weightStr
                + "-" + hex;
    }

    /**
     * Generates a structured code with a random 2-digit hex suffix guaranteed not to collide
     * according to {@code codeExists}.
     */
    public String generateUniqueCode(
            LocalDateTime timestamp,
            String targetGroupCode,
            String garmentTypeCode,
            String qualityGradeCode,
            String packageTypeCode,
            BigDecimal weightKg,
            Predicate<String> codeExists
    ) {
        Objects.requireNonNull(codeExists, "codeExists");
        Set<String> tried = new HashSet<>();
        while (tried.size() < 256) {
            String hex = generateHexSuffix();
            if (!tried.add(hex)) {
                continue;
            }
            String candidate = buildCode(
                    timestamp, targetGroupCode, garmentTypeCode, qualityGradeCode, packageTypeCode, weightKg, hex
            );
            if (!codeExists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Exhausted all 256 hex suffixes for this package configuration and minute.");
    }

    public Optional<ParsedProductCode> parse(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawCode.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = FULL_CODE_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            int hour = Integer.parseInt(matcher.group(4));
            int minute = Integer.parseInt(matcher.group(5));
            LocalDateTime timestamp = LocalDateTime.of(year, month, day, hour, minute);

            BigDecimal weight = new BigDecimal(matcher.group(10));
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                return Optional.empty();
            }

            return Optional.of(new ParsedProductCode(
                    normalized,
                    timestamp,
                    matcher.group(6),
                    matcher.group(7),
                    matcher.group(8),
                    matcher.group(9),
                    weight,
                    matcher.group(11)
            ));
        } catch (DateTimeException | NumberFormatException ex) {
            return Optional.empty();
        }
    }

    public boolean isValid(String rawCode) {
        return parse(rawCode).isPresent();
    }

    private String requireValidSegment(String raw, String fieldName) {
        if (raw == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!SEGMENT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be 1-8 uppercase alphanumeric characters without hyphens: '" + raw + "'"
            );
        }
        return normalized;
    }

    private String requireValidHex(String rawHex) {
        if (rawHex == null) {
            throw new IllegalArgumentException("hexSuffix must not be null");
        }
        String normalized = rawHex.trim().toUpperCase(Locale.ROOT);
        if (!HEX2_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("hexSuffix must be exactly 2 uppercase hex digits (00-FF): '" + rawHex + "'");
        }
        return normalized;
    }

    private String formatWeight(BigDecimal weightKg) {
        if (weightKg == null || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weightKg must be positive");
        }
        BigDecimal scaled = weightKg.stripTrailingZeros();
        if (scaled.scale() > 2) {
            throw new IllegalArgumentException("weightKg supports at most 2 decimal places: " + weightKg);
        }
        if (scaled.scale() < 0) {
            scaled = scaled.setScale(0);
        }
        return scaled.toPlainString();
    }
}
