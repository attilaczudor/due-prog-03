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
        String address,
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

    public CompanyProfile {
        name = (name == null || name.isBlank()) ? DEFAULT_NAME : name.trim();
        address = (address == null || address.isBlank()) ? DEFAULT_ADDRESS : address.trim();
        taxNumber = (taxNumber == null || taxNumber.isBlank()) ? DEFAULT_TAX_NUMBER : taxNumber.trim();
        email = (email == null || email.isBlank()) ? DEFAULT_EMAIL : email.trim();
        phone = phone == null ? "" : phone.trim();
        Map<String, String> copy = new LinkedHashMap<>();
        if (ibansByCurrency != null) {
            for (Map.Entry<String, String> e : ibansByCurrency.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && !e.getValue().isBlank()) {
                    copy.put(e.getKey().trim().toUpperCase(Locale.ROOT), e.getValue().trim());
                }
            }
        }
        ibansByCurrency = Collections.unmodifiableMap(copy);
    }

    public static CompanyProfile defaults() {
        Map<String, String> ibans = new LinkedHashMap<>();
        ibans.put("HUF", DEFAULT_IBAN_HUF);
        ibans.put("EUR", DEFAULT_IBAN_EUR);
        return new CompanyProfile(DEFAULT_NAME, DEFAULT_ADDRESS, DEFAULT_TAX_NUMBER, DEFAULT_EMAIL, DEFAULT_PHONE, ibans);
    }

    /**
     * Resolves the bank account IBAN matching {@code currencyCode} (e.g. {@code HUF} or {@code EUR}),
     * falling back to the first configured IBAN or the default HUF IBAN if none is set for that currency.
     */
    public String ibanForCurrency(String currencyCode) {
        if (currencyCode != null && !currencyCode.isBlank()) {
            String key = currencyCode.trim().toUpperCase(Locale.ROOT);
            String exact = ibansByCurrency.get(key);
            if (exact != null && !exact.isBlank()) {
                return exact;
            }
        }
        if (!ibansByCurrency.isEmpty()) {
            return ibansByCurrency.values().iterator().next();
        }
        return "EUR".equalsIgnoreCase(currencyCode) ? DEFAULT_IBAN_EUR : DEFAULT_IBAN_HUF;
    }
}
