package hu.due.rewear.model;

import java.util.Map;

/** Persistable fields for the offline Online Számla setup. Access secrets are excluded. */
public record NavDemoSettings(Map<Field, String> values) {
    public enum Environment {
        TEST("https://api-test.onlineszamla.nav.gov.hu/invoiceService/v3"),
        PRODUCTION("https://api.onlineszamla.nav.gov.hu/invoiceService/v3");

        private final String endpoint;
        Environment(String endpoint) { this.endpoint = endpoint; }
        public String endpoint() { return endpoint; }
    }

    public enum Field {
        ENVIRONMENT, TAX_NUMBER, LOGIN, SOFTWARE_ID, SOFTWARE_NAME, SOFTWARE_VERSION,
        SOFTWARE_OPERATION, DEVELOPER, DEVELOPER_CONTACT, DEVELOPER_COUNTRY, DEVELOPER_TAX,
        OUTBOUND, INBOUND;

        public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public NavDemoSettings { values = Map.copyOf(values); }

    public String value(Field field) { return values.getOrDefault(field, defaults().getOrDefault(field, "")); }

    public static Map<Field, String> defaults() { return DEFAULTS; }

    private static final Map<Field, String> DEFAULTS = Map.ofEntries(
            Map.entry(Field.ENVIRONMENT, "TEST"), Map.entry(Field.TAX_NUMBER, "12345678"),
            Map.entry(Field.LOGIN, "reweartechdemo1"), Map.entry(Field.SOFTWARE_ID, "REWEARWMSDEMO00001"),
            Map.entry(Field.SOFTWARE_NAME, "ReWear WMS"), Map.entry(Field.SOFTWARE_VERSION, "2.5"),
            Map.entry(Field.SOFTWARE_OPERATION, "LOCAL_SOFTWARE"), Map.entry(Field.DEVELOPER, "ReWear WMS Demo"),
            Map.entry(Field.DEVELOPER_CONTACT, "developer@example.invalid"), Map.entry(Field.DEVELOPER_COUNTRY, "HU"),
            Map.entry(Field.DEVELOPER_TAX, "12345678"), Map.entry(Field.OUTBOUND, "true"), Map.entry(Field.INBOUND, "true"));
}
