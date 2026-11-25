package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.model.Invoice;
import hu.due.rewear.model.SmartFolder;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

record InvoicePartnerOptions(String key, String name, String taxNumber, String email, List<String> matchingKeys) {
    static Map<String, InvoicePartnerOptions> load(AppContext ctx, List<Invoice> invoices) {
        var choices = new LinkedHashMap<String, InvoicePartnerOptions>();
        ctx.getCustomerDao().findAll(false).forEach(customer -> {
            String key = "id:" + customer.id();
            choices.put(key, new InvoicePartnerOptions(key, customer.name(), customer.taxNumber(), customer.email(), List.of(key)));
        });
        var registered = List.copyOf(choices.values());
        var historicalTaxNumbers = new HashMap<String, Set<String>>();
        invoices.stream().filter(invoice -> invoice.customerId() == null).forEach(invoice ->
                historicalTaxNumbers.computeIfAbsent(SmartFolder.invoicePartnerKey(invoice), key -> new HashSet<>())
                        .add(normalizeTaxNumber(invoice.partnerTaxNumber())));
        invoices.forEach(invoice -> {
            String key = SmartFolder.invoicePartnerKey(invoice);
            if (choices.containsKey(key)) return;

            String taxNumber = normalizeTaxNumber(invoice.partnerTaxNumber());
            boolean confirmedTaxNumber = !taxNumber.isEmpty() && historicalTaxNumbers.getOrDefault(key, Set.of()).size() == 1;
            var matches = confirmedTaxNumber ? registered.stream()
                    .filter(partner -> partner.name().equalsIgnoreCase(invoice.partnerName())
                            && taxNumber.equals(normalizeTaxNumber(partner.taxNumber()))).toList() : List.<InvoicePartnerOptions>of();
            if (matches.size() == 1) {
                var partner = choices.get(matches.get(0).key());
                if (!partner.matchingKeys().contains(key)) {
                    var keys = new ArrayList<>(partner.matchingKeys());
                    keys.add(key);
                    choices.put(partner.key(), new InvoicePartnerOptions(partner.key(), partner.name(), partner.taxNumber(),
                            partner.email(), List.copyOf(keys)));
                }
            } else {
                choices.put(key, new InvoicePartnerOptions(key, invoice.partnerName(), invoice.partnerTaxNumber(),
                        invoice.partnerEmail(), List.of(key)));
            }
        });
        var order = Collator.getInstance(Locale.forLanguageTag(ctx.getI18n().getActiveLanguage()));
        var sorted = new LinkedHashMap<String, InvoicePartnerOptions>();
        choices.values().stream().sorted(Comparator.comparing(InvoicePartnerOptions::name, order))
                .forEach(partner -> sorted.put(partner.key(), partner));
        return sorted;
    }

    static List<String> ruleKeys(Map<String, InvoicePartnerOptions> options, Collection<String> selected) {
        return selected.stream().flatMap(key -> matchingKeys(options, key)).distinct().toList();
    }

    private static Stream<String> matchingKeys(Map<String, InvoicePartnerOptions> options, String key) {
        var option = options.get(key);
        return option == null ? Stream.of(key) : option.matchingKeys().stream();
    }

    String details() {
        return Objects.toString(taxNumber, "") + " " + Objects.toString(email, "");
    }

    private static String normalizeTaxNumber(String value) {
        return Objects.toString(value, "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").replaceFirst("^HU(?=\\d)", "");
    }
}
