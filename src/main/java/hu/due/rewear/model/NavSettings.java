package hu.due.rewear.model;

import java.util.Locale;
import java.util.Map;

/**
 * The reportable identity of this installation: which NAV environment it talks to, which taxpayer it reports
 * for, and which software the regulator should see in the request.
 *
 * <p>Secrets are deliberately absent. The technical user's password and the two keys live outside the
 * database entirely — see {@code NavCredentialStore} — so that a database copy, a backup bundle or a support
 * export never carries credentials that can file tax returns.
 */
public record NavSettings(Map<Field, String> values) {

    public enum Environment {
        TEST("https://api-test.onlineszamla.nav.gov.hu/invoiceService/v3"),
        PRODUCTION("https://api.onlineszamla.nav.gov.hu/invoiceService/v3");

        private final String endpoint;

        Environment(String endpoint) {
            this.endpoint = endpoint;
        }

        public String endpoint() {
            return endpoint;
        }
    }

    public enum Field {
        ENABLED, ENVIRONMENT, TAX_NUMBER, VAT_CODE, COUNTY_CODE, LOGIN,
        SOFTWARE_ID, SOFTWARE_NAME, SOFTWARE_VERSION, SOFTWARE_OPERATION,
        DEVELOPER, DEVELOPER_CONTACT, DEVELOPER_COUNTRY, DEVELOPER_TAX;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public NavSettings {
        values = Map.copyOf(values);
    }

    public String value(Field field) {
        return values.getOrDefault(field, DEFAULTS.getOrDefault(field, ""));
    }

    /** Reporting happens only when this is switched on, and it starts off. */
    public boolean enabled() {
        return Boolean.parseBoolean(value(Field.ENABLED));
    }

    public Environment environment() {
        try {
            return Environment.valueOf(value(Field.ENVIRONMENT));
        } catch (IllegalArgumentException ex) {
            return Environment.TEST;
        }
    }

    public String endpoint() {
        return environment().endpoint();
    }

    public static Map<Field, String> defaults() {
        return DEFAULTS;
    }

    /**
     * The defaults point at the test environment and keep reporting switched off. Nothing reaches the tax
     * authority until somebody enters a real technical user and turns it on deliberately.
     */
    private static final Map<Field, String> DEFAULTS = Map.ofEntries(
            Map.entry(Field.ENABLED, "false"),
            Map.entry(Field.ENVIRONMENT, "TEST"),
            Map.entry(Field.TAX_NUMBER, ""),
            Map.entry(Field.VAT_CODE, "2"),
            Map.entry(Field.COUNTY_CODE, ""),
            Map.entry(Field.LOGIN, ""),
            Map.entry(Field.SOFTWARE_ID, "REWEARWMS000000001"),
            Map.entry(Field.SOFTWARE_NAME, "ReWear WMS"),
            Map.entry(Field.SOFTWARE_VERSION, "2.6"),
            Map.entry(Field.SOFTWARE_OPERATION, "LOCAL_SOFTWARE"),
            Map.entry(Field.DEVELOPER, "ReWear WMS"),
            Map.entry(Field.DEVELOPER_CONTACT, ""),
            Map.entry(Field.DEVELOPER_COUNTRY, "HU"),
            Map.entry(Field.DEVELOPER_TAX, ""));
}
