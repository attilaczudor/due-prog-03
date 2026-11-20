package hu.due.rewear.service;

import hu.due.rewear.model.ParsedProductCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCodeServiceTest {

    private final ProductCodeService service = new ProductCodeService();

    @Test
    void buildsAndParsesStandardConventionCode() {
        LocalDateTime dt = LocalDateTime.of(2026, 11, 12, 11, 23);
        String code = service.buildCode(dt, "L", "TSH", "A", "A", new BigDecimal("25"), "3F");

        assertEquals("2026-11-12-11-23-L-TSH-A-A-25-3F", code);

        Optional<ParsedProductCode> parsedOpt = service.parse(code);
        assertTrue(parsedOpt.isPresent());
        ParsedProductCode parsed = parsedOpt.get();
        assertEquals(dt, parsed.timestamp());
        assertEquals("L", parsed.targetGroupCode());
        assertEquals("TSH", parsed.garmentTypeCode());
        assertEquals("A", parsed.qualityGradeCode());
        assertEquals("A", parsed.packageTypeCode());
        assertEquals(0, new BigDecimal("25").compareTo(parsed.weightKg()));
        assertEquals("3F", parsed.hexSuffix());
    }

    @Test
    void supportsDecimalWeightsAndUserDefinedCodeSegments() {
        LocalDateTime dt = LocalDateTime.of(2026, 11, 20, 9, 5);
        String code = service.buildCode(dt, "WTR", "COAT", "CR", "XLBAG", new BigDecimal("18.50"), "0A");

        assertEquals("2026-11-20-09-05-WTR-COAT-CR-XLBAG-18.5-0A", code);
        assertTrue(service.isValid(code));
    }

    @Test
    void generatesUniqueRandomHexSuffixAvoidingCollisions() {
        LocalDateTime dt = LocalDateTime.of(2026, 11, 20, 14, 0);
        Set<String> existing = new HashSet<>();

        for (int i = 0; i < 40; i++) {
            String generated = service.generateUniqueCode(
                    dt, "L", "TSH", "A", "A", new BigDecimal("25"), existing::contains
            );
            assertTrue(existing.add(generated), "Generated code must be unique");
            assertTrue(service.isValid(generated));
        }
    }

    @Test
    void normalizesUserEnteredSegmentsAndRejectsInvalidCodes() {
        assertEquals("POLO1", service.normalizeCodeSegment("Póló-1!"));
        assertFalse(service.isValid("2026-02-30-11-23-L-TSH-A-A-25-3F")); // invalid date
        assertFalse(service.isValid("2026-11-12-11-23-L-TSH-A-A-25-ZZ")); // non-hex suffix
        assertFalse(service.isValid("2026-11-12-11-23-L-TSH-A-A-0-3F"));  // zero weight
        assertFalse(service.isValid("NOT-A-VALID-CODE"));

        assertThrows(IllegalArgumentException.class, () ->
                service.buildCode(LocalDateTime.now(), "L-1", "TSH", "A", "A", BigDecimal.TEN, "AF"));
    }
}
