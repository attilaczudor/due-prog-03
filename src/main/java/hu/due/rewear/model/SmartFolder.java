package hu.due.rewear.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record SmartFolder(long id, String name, String icon, Map<Field, List<String>> rules) {
    public enum Scope { INVOICE, MAIL }

    public enum Field {
        CURRENCY, PARTNER, DIRECTION, STATUS, CATEGORY, SEARCH,
        MAILBOX, SENDER, RECIPIENT, SUBJECT, BODY, READ, ATTACHMENT
    }

    public SmartFolder {
        name = name.strip();
        if (name.isEmpty() || name.length() > 80) {
            throw new IllegalArgumentException("Folder name must contain 1–80 characters.");
        }
        var copy = new java.util.EnumMap<Field, List<String>>(Field.class);
        rules.forEach((field, values) -> copy.put(field, List.copyOf(values)));
        rules = Map.copyOf(copy);
    }

    public boolean matches(Invoice invoice) {
        return matchesValue(Field.CURRENCY, invoice.effectiveCurrencyCode())
                && matchesValue(Field.PARTNER, invoicePartnerKey(invoice))
                && matchesValue(Field.DIRECTION, invoice.direction().name())
                && matchesValue(Field.STATUS, invoice.status().name())
                && matchesValue(Field.CATEGORY, invoice.category().name())
                && contains(Field.SEARCH, invoiceSearchText(invoice));
    }

    public boolean matches(EmailMessage message) {
        List<String> mailboxes = rules.getOrDefault(Field.MAILBOX,
                List.of(EmailMessage.FOLDER_INBOX, EmailMessage.FOLDER_SENT));
        return mailboxes.contains(message.folder())
                && contains(Field.SENDER, message.fromAddress())
                && contains(Field.RECIPIENT, message.toAddresses() + " " + message.ccAddresses())
                && contains(Field.SUBJECT, message.subject())
                && contains(Field.BODY, message.body())
                && contains(Field.SEARCH, message.subject() + " " + message.body())
                && matchesValue(Field.READ, Boolean.toString(message.read()))
                && matchesValue(Field.ATTACHMENT, Boolean.toString(message.hasAttachment()));
    }

    public static String invoicePartnerKey(Invoice invoice) {
        return invoice.customerId() == null ? "name:" + invoice.partnerName() : "id:" + invoice.customerId();
    }

    public static List<String> parseSearchTerms(String query) {
        return Arrays.stream(query.split(";")).map(String::strip).filter(term -> !term.isEmpty()).distinct().toList();
    }

    public static String invoiceSearchText(Invoice invoice) {
        return String.join(" ", invoice.invoiceNumber(), invoice.partnerName(), invoice.effectiveCurrencyCode(),
                invoice.effectiveTaxTypeCode(), invoice.orderNumber() == null ? "" : invoice.orderNumber());
    }

    private boolean matchesValue(Field field, String actual) {
        List<String> allowed = rules.getOrDefault(field, List.of());
        return allowed.isEmpty() || allowed.contains(actual);
    }

    private boolean contains(Field field, String actual) {
        List<String> needles = rules.getOrDefault(field, List.of());
        String text = actual == null ? "" : actual.toLowerCase(Locale.ROOT);
        return needles.stream().allMatch(needle -> text.contains(needle.toLowerCase(Locale.ROOT)));
    }
}
