package hu.due.rewear.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Editable seller/issuer company identity and per-currency bank account IBANs stored portably
 * inside SQLite {@code app_settings}. Dynamically rendered on A4 PDF invoices and payment reminders
 * so HUF invoices display the HUF IBAN while EUR/USD invoices display their matching currency IBAN.
 */
public record CompanyProfile(
        String name,
        Address addressParts,
        String taxNumber,
        String email,
        String phone,
        Map<String, String> ibansByCurrency
) {
    public static final String DEFAULT_NAME = "ReWear Logistics Kft.";
    public static final String DEFAULT_ADDRESS = "2400 Dunaújváros, Táncsics Mihály u. 1/A";
    public static final String DEFAULT_TAX_NUMBER = "HU-27891042-2-07";
    public static final String DEFAULT_EMAIL = "billing@rewear-wms.hu";
    public static final String DEFAULT_PHONE = "+36 25 555 0190";
    public static final String DEFAULT_IBAN_HUF = "HU42 1177 3061 0023 4567 0000 0000";
    public static final String DEFAULT_IBAN_EUR = "HU88 1177 3061 0023 4567 0000 1111";

    public record Address(String street, String city, String postalCode) {
        public Address {
            street = clean(street);
            city = clean(city);
            postalCode = clean(postalCode);
        }

        public static Address fromLegacy(String value) {
            String text = clean(value);
            var hungarianAddress = java.util.regex.Pattern.compile("^(\\d{4})\\s+([^,]+),\\s*(.+)$").matcher(text);
            if (hungarianAddress.matches()) {
                return new Address(hungarianAddress.group(3), hungarianAddress.group(2), hungarianAddress.group(1));
            }
            return new Address(text, "", "");
        }

        public String locality() { return (postalCode + " " + city).strip(); }

        public String formatted() {
            String locality = locality();
            return locality.isEmpty() || street.isEmpty() ? locality + street : locality + ", " + street;
        }

        private static String clean(String value) { return value == null ? "" : value.strip(); }
    }

    public CompanyProfile(String name, String address, String taxNumber, String email, String phone,
                          Map<String, String> ibansByCurrency) {
        this(name, Address.fromLegacy(address == null || address.isBlank() ? DEFAULT_ADDRESS : address),
                taxNumber, email, phone, ibansByCurrency);
    }

    public CompanyProfile {
        name = (name == null || name.isBlank()) ? DEFAULT_NAME : name.trim();
        addressParts = addressParts == null ? Address.fromLegacy(DEFAULT_ADDRESS) : addressParts;
        taxNumber = (taxNumber == null || taxNumber.isBlank()) ? DEFAULT_TAX_NUMBER : taxNumber.trim();
        email = (email == null || email.isBlank()) ? DEFAULT_EMAIL : email.trim();
        phone = phone == null ? "" : phone.trim();
        Map<String, String> copy = new LinkedHashMap<>();
        if (ibansByCurrency != null) {
            for (Map.Entry<String, String> e : ibansByCurrency.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    copy.put(e.getKey().trim().toUpperCase(Locale.ROOT), e.getValue().trim());
                }
            }
        }
        ibansByCurrency = Collections.unmodifiableMap(copy);
    }

    public String address() { return addressParts.formatted(); }

    public static CompanyProfile defaults() {
        Map<String, String> ibans = new LinkedHashMap<>();
        ibans.put("HUF", DEFAULT_IBAN_HUF);
        ibans.put("EUR", DEFAULT_IBAN_EUR);
        return new CompanyProfile(DEFAULT_NAME, DEFAULT_ADDRESS, DEFAULT_TAX_NUMBER, DEFAULT_EMAIL, DEFAULT_PHONE, ibans);
    }

    /**
     * Resolves the bank account for the invoice currency, or an empty string when it has none.
     */
    public String ibanForCurrency(String currencyCode) {
        return currencyCode == null ? "" : ibansByCurrency.getOrDefault(currencyCode.strip().toUpperCase(Locale.ROOT), "");
    }
}
