package hu.due.rewear.service;

import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.OrderDemandDao.Product;
import hu.due.rewear.model.Order;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.ToLongFunction;

public final class WebshopOrderFile {
    private WebshopOrderFile() { }

    public static Order importOrder(Path path, DictionaryDao dictionaries, OrderService orders, long userId) throws IOException {
        if (Files.size(path) > 1_000_000) throw new IllegalArgumentException("The webshop order file is too large.");
        var props = new Properties();
        try (var reader = Files.newBufferedReader(path)) { props.load(reader); }
        var targets = dictionaries.findTargetGroups(true);
        var garments = dictionaries.findGarmentTypes(true);
        var grades = dictionaries.findQualityGrades(true);
        var packages = dictionaries.findPackageTypes(true);
        var items = new LinkedHashMap<Product, Integer>();
        for (String key : props.stringPropertyNames().stream().filter(name -> name.matches("product\\.[0-9]+")).sorted().toList()) {
            String[] fields = required(props, key).split(",", -1);
            if (fields.length != 5 || items.size() >= 200) {
                throw new IllegalArgumentException("Use target, product, grade, packaging and quantity for each webshop product.");
            }
            var product = new Product(resolve(targets, fields[0], x -> x.code(), x -> x.id()),
                    resolve(garments, fields[1], x -> x.code(), x -> x.id()),
                    resolve(grades, fields[2], x -> x.code(), x -> x.id()),
                    resolve(packages, fields[3], x -> x.code(), x -> x.id()));
            if (items.putIfAbsent(product, Integer.parseInt(fields[4].strip())) != null) {
                throw new IllegalArgumentException("Combine duplicate webshop product lines into one quantity.");
            }
        }
        return orders.importWebshopOrder(required(props, "source"), required(props, "external_id"),
                Long.parseLong(required(props, "customer_id")), required(props, "currency"),
                LocalDate.parse(required(props, "delivery_date")), items, userId);
    }

    private static String required(Properties props, String key) {
        String value = props.getProperty(key, "").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing webshop field: " + key);
        return value;
    }

    private static <T> long resolve(List<T> codes, String raw, Function<T, String> code, ToLongFunction<T> id) {
        String value = raw.strip().toUpperCase(Locale.ROOT);
        return codes.stream().filter(entry -> code.apply(entry).equalsIgnoreCase(value)).findFirst().map(id::applyAsLong)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or inactive webshop product code: " + value));
    }
}
