package hu.due.rewear.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * European & international country directory with VAT scope classification:
 * Hungary (HU -> 27% domestic), other EU member states (EU -> 0% reverse charge), and outside EU (NON_EU -> 0% export).
 */
public final class CountryDirectory {

    public record CountryOption(String code, String nameEn, String nameHu) {
        public String localizedLabel(String lang) {
            String name = "hu".equalsIgnoreCase(lang) ? nameHu : nameEn;
            return code + " — " + name;
        }

        @Override
        public String toString() {
            return code + " — " + nameHu;
        }
    }

    private static final Set<String> EU_MEMBER_CODES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU",
            "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"
    );

    public static final List<CountryOption> COUNTRIES = List.of(
            new CountryOption("HU", "Hungary", "Magyarország"),
            new CountryOption("AT", "Austria", "Ausztria"),
            new CountryOption("SK", "Slovakia", "Szlovákia"),
            new CountryOption("DE", "Germany", "Németország"),
            new CountryOption("RO", "Romania", "Románia"),
            new CountryOption("CZ", "Czechia", "Csehország"),
            new CountryOption("PL", "Poland", "Lengyelország"),
            new CountryOption("HR", "Croatia", "Horvátország"),
            new CountryOption("SI", "Slovenia", "Szlovénia"),
            new CountryOption("NL", "Netherlands", "Hollandia"),
            new CountryOption("BE", "Belgium", "Belgium"),
            new CountryOption("FR", "France", "Franciaország"),
            new CountryOption("IT", "Italy", "Olaszország"),
            new CountryOption("ES", "Spain", "Spanyolország"),
            new CountryOption("BG", "Bulgaria", "Bulgária"),
            new CountryOption("SE", "Sweden", "Svédország"),
            new CountryOption("DK", "Denmark", "Dánia"),
            new CountryOption("CH", "Switzerland", "Svájc"),
            new CountryOption("GB", "United Kingdom", "Egyesült Királyság"),
            new CountryOption("US", "United States", "Egyesült Államok"),
            new CountryOption("RS", "Serbia", "Szerbia"),
            new CountryOption("UA", "Ukraine", "Ukrajna")
    );

    private CountryDirectory() {
    }

    public static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return "HU";
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isHungary(String code) {
        return "HU".equals(normalizeCode(code));
    }

    public static boolean isOtherEuMember(String code) {
        String norm = normalizeCode(code);
        return !"HU".equals(norm) && EU_MEMBER_CODES.contains(norm);
    }

    /** Returns the VAT tax scope key for a partner country: "HU", "EU", or "NON_EU". */
    public static String taxScopeForCountry(String countryCode) {
        String norm = normalizeCode(countryCode);
        if ("HU".equals(norm)) {
            return "HU";
        }
        if (EU_MEMBER_CODES.contains(norm)) {
            return "EU";
        }
        return "NON_EU";
    }

    public static String localizedCountryName(String code, String lang) {
        String norm = normalizeCode(code);
        return COUNTRIES.stream()
                .filter(c -> c.code().equals(norm))
                .findFirst()
                .map(c -> c.localizedLabel(lang))
                .orElse(norm);
    }
}
