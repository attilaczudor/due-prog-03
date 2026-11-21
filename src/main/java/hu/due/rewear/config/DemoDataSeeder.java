package hu.due.rewear.config;

import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.RoleDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.service.ProductCodeService;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds realistic second-hand clothing warehouse demo data idempotently on first launch.
 */
public final class DemoDataSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DatabaseManager db;
    private final RoleDao roleDao;
    private final UserDao userDao;
    private final DictionaryDao dictionaryDao;
    private final WarehouseLocationDao locationDao;
    private final InventoryPackageDao packageDao;
    private final StockMovementDao movementDao;
    private final CustomerDao customerDao;
    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;
    private final InvoiceDao invoiceDao;
    private final ProductCodeService codeService;

    public DemoDataSeeder(DatabaseManager db) {
        this.db = db;
        this.roleDao = new RoleDao(db);
        this.userDao = new UserDao(db);
        this.dictionaryDao = new DictionaryDao(db);
        this.locationDao = new WarehouseLocationDao(db);
        this.packageDao = new InventoryPackageDao(db);
        this.movementDao = new StockMovementDao(db);
        this.customerDao = new CustomerDao(db);
        this.orderDao = new OrderDao(db);
        this.orderItemDao = new OrderItemDao(db);
        this.invoiceDao = new InvoiceDao(db);
        this.codeService = new ProductCodeService();
    }

    public void seedIfEmpty() {
        if (userDao.count() == 0) {
            LOG.info("Seeding initial ReWear WMS demo data...");
            db.runInTransaction(this::seedAll);
            LOG.info("Demo data seeded successfully.");
        } else {
            LOG.debug("Database already populated; skipping core demo data seed.");
        }
        seedStarterInvoicesIfEmpty();
    }

    private void seedStarterInvoicesIfEmpty() {
        if (invoiceDao.count() > 0 || userDao.count() == 0) {
            return;
        }
        long creatorId = userDao.findByUsername("superadmin")
                .or(() -> userDao.findByUsername("admin"))
                .map(hu.due.rewear.model.User::id)
                .orElse(1L);
        List<Order> orders = orderDao.findAll();
        BigDecimal vat27 = new BigDecimal("27.00");
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        db.runInTransaction(conn -> {
            // Outgoing invoices linked to shipped/active demo orders
            int outSeq = 1;
            for (Order ord : orders) {
                if (ord.status() == OrderStatus.SHIPPED || ord.status() == OrderStatus.PACKING) {
                    List<OrderItem> items = orderItemDao.findByOrderId(ord.id());
                    if (items.isEmpty()) continue;
                    Customer cust = customerDao.findById(ord.customerId()).orElse(null);
                    CurrencyCode cur = ord.effectiveCurrency();
                    BigDecimal net = cur.round(ord.totalPrice());
                    BigDecimal vat = cur.round(net.multiply(vat27).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                    BigDecimal gross = cur.round(net.add(vat));
                    boolean paid = ord.status() == OrderStatus.SHIPPED && outSeq == 1;
                    String invNo = String.format("INV-OUT-%d-%04d", today.getYear(), outSeq++);
                    long invId = invoiceDao.insert(conn, invNo, InvoiceDirection.OUTGOING,
                            InvoiceCategory.WHOLESALE_SALES, ord.id(), ord.customerId(),
                            cust != null ? cust.name() : ord.customerName(),
                            cust != null ? cust.taxNumber() : null,
                            cust != null ? cust.address() : null,
                            cust != null ? cust.email() : null,
                            paid ? InvoiceStatus.PAID : InvoiceStatus.ISSUED,
                            today.minusDays(paid ? 5 : 1),
                            today.plusDays(paid ? 9 : 13),
                            paid ? today.minusDays(3) : null,
                            net, vat27, vat, gross, cur.name(), CurrencyCode.DEFAULT_EUR_HUF_RATE,
                            creatorId, now.minusDays(paid ? 5 : 1));
                    for (OrderItem oi : items) {
                        String desc = oi.packageQrCode() + " (" + oi.summaryEn() + ")";
                        invoiceDao.insertItem(conn, invId, desc, oi.weightKg(), "kg",
                                cur.round(oi.pricePerKg()), cur.round(oi.lineTotal()));
                    }
                }
            }

            // Realistic Dual-Currency Incoming (Expense) Invoices for warehouse P&L
            // 1. European textile import in EUR
            seedIncomingExpense(conn, "INV-IN-" + today.getYear() + "-0001", InvoiceCategory.RAW_STOCK_PURCHASE,
                    "EuroTex Import GmbH", "DE-294810221", "80331 München, Industriestr. 14",
                    "billing@eurotex-import.de", "Unsorted European Textile Lot (400 kg)",
                    new BigDecimal("400"), "kg", new BigDecimal("0.95"), BigDecimal.ZERO, CurrencyCode.EUR,
                    today.minusDays(8), today.plusDays(6), InvoiceStatus.PAID, today.minusDays(6), creatorId, now);

            // 2. Hungarian warehouse lease in HUF
            seedIncomingExpense(conn, "INV-IN-" + today.getYear() + "-0002", InvoiceCategory.WAREHOUSE_RENT,
                    "Duna Ipari Park Kft.", "HU-14820911-2-07", "2400 Dunaújváros, Papírgyári út 12.",
                    "penzugy@dunaiparipark.hu", "Warehouse Hall B Monthly Lease",
                    BigDecimal.ONE, "hó", new BigDecimal("110000"), vat27, CurrencyCode.HUF,
                    today.minusDays(6), today.plusDays(8), InvoiceStatus.PAID, today.minusDays(5), creatorId, now);

            // 3. Hungarian utilities bill in HUF
            seedIncomingExpense(conn, "INV-IN-" + today.getYear() + "-0003", InvoiceCategory.UTILITIES,
                    "MVM Next Energiakereskedelmi Zrt.", "HU-26713111-2-44", "1081 Budapest, II. János Pál pápa tér 20.",
                    "szamlazas@mvm.hu", "Warehouse Electricity & Heating",
                    BigDecimal.ONE, "hó", new BigDecimal("36000"), vat27, CurrencyCode.HUF,
                    today.minusDays(2), today.plusDays(12), InvoiceStatus.ISSUED, null, creatorId, now);

            // 4. Packaging & thermal labels in HUF
            seedIncomingExpense(conn, "INV-IN-" + today.getYear() + "-0004", InvoiceCategory.PACKAGING_SUPPLIES,
                    "ZebraLabel Pack Kft.", "HU-25104439-2-13", "2040 Budaörs, Gyár u. 2.",
                    "orders@zebralabel.hu", "Thermal Sticker Rolls 100x150 & 100x100 + Bale Straps",
                    new BigDecimal("12"), "db", new BigDecimal("1600"), vat27, CurrencyCode.HUF,
                    today.minusDays(4), today.plusDays(10), InvoiceStatus.PAID, today.minusDays(3), creatorId, now);
        });
        LOG.info("Seeded starter outgoing & incoming demo invoices.");
    }

    private void seedIncomingExpense(Connection conn, String invNo, InvoiceCategory cat,
                                     String partner, String tax, String addr, String email,
                                     String desc, BigDecimal qty, String unit, BigDecimal unitPrice,
                                     BigDecimal vatRate, CurrencyCode currency, LocalDate issue, LocalDate due,
                                     InvoiceStatus status, LocalDate paid, long creatorId, LocalDateTime now) throws SQLException {
        BigDecimal net = currency.round(qty.multiply(unitPrice));
        BigDecimal vat = currency.round(net.multiply(vatRate).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal gross = currency.round(net.add(vat));
        long id = invoiceDao.insert(conn, invNo, InvoiceDirection.INCOMING, cat, null, null,
                partner, tax, addr, email, status, issue, due, paid, net, vatRate, vat, gross,
                currency.name(), CurrencyCode.DEFAULT_EUR_HUF_RATE, creatorId, now);
        invoiceDao.insertItem(conn, id, desc, qty, unit, currency.round(unitPrice), net);
    }

    private void seedAll(Connection conn) throws SQLException {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);

        // 1. Roles
        long superAdminRoleId = roleDao.insert(conn, RoleCode.SUPER_ADMIN, "Super Administrator");
        long adminRoleId = roleDao.insert(conn, RoleCode.ADMIN, "Administrator");
        long managerRoleId = roleDao.insert(conn, RoleCode.MANAGER, "Warehouse Manager");
        long operatorRoleId = roleDao.insert(conn, RoleCode.OPERATOR, "Sorting Operator");

        // 2. Demo Users (BCrypt)
        userDao.insert(conn, "superadmin", BCrypt.hashpw("superadmin123", BCrypt.gensalt(10)),
                "Czudor Attila", superAdminRoleId, true, now.minusDays(15));
        long adminId = userDao.insert(conn, "admin", BCrypt.hashpw("admin123", BCrypt.gensalt(10)),
                "Szabó Gábor", adminRoleId, true, now.minusDays(14));
        long managerId = userDao.insert(conn, "manager", BCrypt.hashpw("manager123", BCrypt.gensalt(10)),
                "Kovács Eszter", managerRoleId, true, now.minusDays(12));
        long operatorId = userDao.insert(conn, "operator", BCrypt.hashpw("operator123", BCrypt.gensalt(10)),
                "Nagy Bence", operatorRoleId, true, now.minusDays(10));

        // 3. Target Groups (WHATFOR)
        Map<String, Long> targets = new HashMap<>();
        targets.put("L", dictionaryDao.insertTargetGroup(conn, "L", "Ladies", "Női"));
        targets.put("M", dictionaryDao.insertTargetGroup(conn, "M", "Men", "Férfi"));
        targets.put("K", dictionaryDao.insertTargetGroup(conn, "K", "Kids", "Gyerek"));
        targets.put("B", dictionaryDao.insertTargetGroup(conn, "B", "Baby", "Baba"));
        targets.put("U", dictionaryDao.insertTargetGroup(conn, "U", "Unisex", "Uniszex"));
        targets.put("H", dictionaryDao.insertTargetGroup(conn, "H", "Home Textiles", "Lakástextil"));

        // 4. Garment / Product Types (TYPE) with dual EUR/kg & HUF/kg pricing
        Map<String, Long> garments = new HashMap<>();
        Map<String, BigDecimal> garmentEur = new HashMap<>();
        Map<String, BigDecimal> garmentHuf = new HashMap<>();
        garments.put("TSH", dictionaryDao.insertGarmentType(conn, "TSH", "T-Shirts", "Pólók", new BigDecimal("4.50"), new BigDecimal("1800")));
        garmentEur.put("TSH", new BigDecimal("4.50")); garmentHuf.put("TSH", new BigDecimal("1800"));
        garments.put("JNS", dictionaryDao.insertGarmentType(conn, "JNS", "Jeans", "Farmerek", new BigDecimal("5.50"), new BigDecimal("2200")));
        garmentEur.put("JNS", new BigDecimal("5.50")); garmentHuf.put("JNS", new BigDecimal("2200"));
        garments.put("JKT", dictionaryDao.insertGarmentType(conn, "JKT", "Jackets", "Kabátok", new BigDecimal("7.50"), new BigDecimal("3000")));
        garmentEur.put("JKT", new BigDecimal("7.50")); garmentHuf.put("JKT", new BigDecimal("3000"));
        garments.put("DRS", dictionaryDao.insertGarmentType(conn, "DRS", "Dresses", "Ruhák", new BigDecimal("6.00"), new BigDecimal("2400")));
        garmentEur.put("DRS", new BigDecimal("6.00")); garmentHuf.put("DRS", new BigDecimal("2400"));
        garments.put("SWT", dictionaryDao.insertGarmentType(conn, "SWT", "Sweaters", "Pulóverek", new BigDecimal("5.00"), new BigDecimal("2000")));
        garmentEur.put("SWT", new BigDecimal("5.00")); garmentHuf.put("SWT", new BigDecimal("2000"));
        garments.put("SHO", dictionaryDao.insertGarmentType(conn, "SHO", "Shoes", "Cipők", new BigDecimal("8.00"), new BigDecimal("3200")));
        garmentEur.put("SHO", new BigDecimal("8.00")); garmentHuf.put("SHO", new BigDecimal("3200"));
        garments.put("MIX", dictionaryDao.insertGarmentType(conn, "MIX", "Mixed Garments", "Vegyes ruházat", new BigDecimal("3.50"), new BigDecimal("1400")));
        garmentEur.put("MIX", new BigDecimal("3.50")); garmentHuf.put("MIX", new BigDecimal("1400"));

        // 5. Quality Grades (QUALITY) with dual EUR/kg & HUF/kg fallback prices
        Map<String, Long> qualities = new HashMap<>();
        qualities.put("CR", dictionaryDao.insertQualityGrade(conn, "CR", "Cream / Extra", "Krém / Extra", new BigDecimal("9.50"), new BigDecimal("3800")));
        qualities.put("A", dictionaryDao.insertQualityGrade(conn, "A", "Grade A", "A osztály", new BigDecimal("5.20"), new BigDecimal("2080")));
        qualities.put("B", dictionaryDao.insertQualityGrade(conn, "B", "Grade B", "B osztály", new BigDecimal("2.80"), new BigDecimal("1120")));
        qualities.put("C", dictionaryDao.insertQualityGrade(conn, "C", "Grade C / Rags", "C osztály / Géprongy", new BigDecimal("0.90"), new BigDecimal("360")));

        // 6. Package Types (PACKAGETYPE)
        Map<String, Long> packages = new HashMap<>();
        packages.put("A", dictionaryDao.insertPackageType(conn, "A", "Compressed Bale", "Préselt bála", new BigDecimal("0.50"), new BigDecimal("55")));
        packages.put("B", dictionaryDao.insertPackageType(conn, "B", "Clear Bag", "Átlátszó zsák", new BigDecimal("0.20"), new BigDecimal("30")));
        packages.put("C", dictionaryDao.insertPackageType(conn, "C", "Cardboard Box", "Kartondoboz", new BigDecimal("1.00"), new BigDecimal("35")));
        packages.put("P", dictionaryDao.insertPackageType(conn, "P", "Pallet", "Raklap", new BigDecimal("15.00"), new BigDecimal("500")));

        // 7. Warehouse Locations
        Map<String, Long> locations = new HashMap<>();
        locations.put("A-01-01", locationDao.insert(conn, "A-01-01", "A", "01", "01", new BigDecimal("600")));
        locations.put("A-01-02", locationDao.insert(conn, "A-01-02", "A", "01", "02", new BigDecimal("600")));
        locations.put("A-02-01", locationDao.insert(conn, "A-02-01", "A", "02", "01", new BigDecimal("500")));
        locations.put("B-01-01", locationDao.insert(conn, "B-01-01", "B", "01", "01", new BigDecimal("800")));
        locations.put("B-01-02", locationDao.insert(conn, "B-01-02", "B", "01", "02", new BigDecimal("800")));
        locations.put("C-01-01", locationDao.insert(conn, "C-01-01", "C", "01", "01", new BigDecimal("1200")));

        // 8. Customers (Hungarian B2B in HUF, Slovak export partner in EUR)
        long cust1 = customerDao.insert(conn, "Duna Vintage Kft.", "Szabó Márta", "marta@dunavintage.hu",
                "+36 30 555 1201", "2400 Dunaújváros, Vasmű út 18.", "24891234-2-07", CurrencyCode.HUF, now.minusDays(20));
        long cust2 = customerDao.insert(conn, "Pannon Textil Outlet Bt.", "Tóth Gábor", "rendeles@pannontextil.hu",
                "+36 20 444 9832", "8000 Székesfehérvár, Budai út 44.", "21904411-2-07", CurrencyCode.HUF, now.minusDays(18));
        long cust3 = customerDao.insert(conn, "GreenLoop Second-Hand S.R.O.", "Jana Nováková", "supply@greenloop.sk",
                "+421 905 331 890", "811 06 Bratislava, Obchodná 22", "SK2024119822", CurrencyCode.EUR, now.minusDays(15));
        long cust4 = customerDao.insert(conn, "Alföld Ruhabázis Kft.", "Varga László", "info@alfoldruha.hu",
                "+36 70 312 6640", "6000 Kecskemét, Izsáki út 10.", "26778890-2-03", CurrencyCode.HUF, now.minusDays(9));

        // 9. Inventory Packages + Stock Movements
        record SeedBale(LocalDateTime ts, String t, String g, String q, String p, String w, String hex,
                        String loc, PackageStatus status, long userId, String note) {}

        SeedBale[] seedBales = new SeedBale[]{
                // Exact convention showcase example
                new SeedBale(LocalDateTime.of(2026, 11, 12, 11, 23), "L", "TSH", "A", "A", "25", "3F", "A-01-01", PackageStatus.IN_STOCK, operatorId, "Summer cotton batch"),
                new SeedBale(now.minusDays(5).withHour(9).withMinute(15), "L", "DRS", "CR", "B", "18", "A4", "A-01-01", PackageStatus.IN_STOCK, operatorId, "Boutique cream dresses"),
                new SeedBale(now.minusDays(5).withHour(10).withMinute(40), "M", "JNS", "A", "A", "45", "1C", "A-01-02", PackageStatus.IN_STOCK, operatorId, "Denim sorted sizes M-XL"),
                new SeedBale(now.minusDays(4).withHour(8).withMinute(30), "M", "TSH", "A", "A", "30", "7B", "A-01-02", PackageStatus.IN_STOCK, managerId, "Men cotton tees"),
                new SeedBale(now.minusDays(4).withHour(11).withMinute(10), "K", "MIX", "CR", "B", "20", "D2", "A-02-01", PackageStatus.IN_STOCK, operatorId, "Kids autumn mix"),
                new SeedBale(now.minusDays(4).withHour(14).withMinute(5), "B", "MIX", "CR", "C", "15", "9E", "A-02-01", PackageStatus.IN_STOCK, operatorId, "Baby clean cotton"),
                new SeedBale(now.minusDays(3).withHour(9).withMinute(45), "L", "JKT", "A", "A", "40", "5A", "B-01-01", PackageStatus.IN_STOCK, operatorId, "Winter & autumn jackets"),
                new SeedBale(now.minusDays(3).withHour(13).withMinute(20), "M", "JKT", "B", "A", "45", "C8", "B-01-01", PackageStatus.IN_STOCK, operatorId, "Men work/casual jackets"),
                new SeedBale(now.minusDays(2).withHour(10).withMinute(0), "U", "SHO", "A", "C", "22", "4D", "B-01-02", PackageStatus.IN_STOCK, managerId, "Paired sneakers & boots"),
                new SeedBale(now.minusDays(2).withHour(15).withMinute(12), "H", "MIX", "B", "A", "50", "E1", "C-01-01", PackageStatus.IN_STOCK, operatorId, "Curtains & bed linens"),
                new SeedBale(now.minusDays(1).withHour(8).withMinute(50), "L", "SWT", "CR", "B", "20", "2F", "A-01-01", PackageStatus.IN_STOCK, operatorId, "Knitwear wool blend"),
                new SeedBale(now.minusDays(1).withHour(11).withMinute(35), "U", "MIX", "C", "P", "180", "B6", "C-01-01", PackageStatus.IN_STOCK, adminId, "Industrial wiper cotton pallet"),
                new SeedBale(now.withHour(8).withMinute(15), "L", "JNS", "CR", "A", "35", "88", "A-01-02", PackageStatus.IN_STOCK, operatorId, "Today's morning sort"),
                new SeedBale(now.withHour(9).withMinute(40), "K", "SHO", "A", "C", "16", "F0", "A-02-01", PackageStatus.IN_STOCK, operatorId, "Kids paired shoes"),
                new SeedBale(now.withHour(10).withMinute(55), "M", "SWT", "A", "B", "25", "63", "B-01-02", PackageStatus.IN_STOCK, managerId, "Men fleece & knit"),
                // Reserved for PACKING order
                new SeedBale(now.minusDays(3).withHour(11).withMinute(5), "L", "TSH", "CR", "B", "20", "19", "A-01-01", PackageStatus.RESERVED, operatorId, "Reserved for Duna Vintage"),
                new SeedBale(now.minusDays(3).withHour(11).withMinute(18), "L", "DRS", "A", "A", "30", "42", "A-01-01", PackageStatus.RESERVED, operatorId, "Reserved for Duna Vintage"),
                new SeedBale(now.minusDays(2).withHour(9).withMinute(25), "M", "JNS", "CR", "A", "40", "77", "A-01-02", PackageStatus.RESERVED, managerId, "Reserved for Duna Vintage"),
                // Shipped in completed order
                new SeedBale(now.minusDays(7).withHour(10).withMinute(10), "L", "TSH", "A", "A", "35", "0C", "A-01-01", PackageStatus.SHIPPED, operatorId, "Shipped batch #1"),
                new SeedBale(now.minusDays(7).withHour(10).withMinute(30), "M", "JKT", "A", "A", "45", "5E", "B-01-01", PackageStatus.SHIPPED, operatorId, "Shipped batch #1"),
                new SeedBale(now.minusDays(6).withHour(14).withMinute(0), "K", "TSH", "B", "B", "25", "91", "A-02-01", PackageStatus.SHIPPED, managerId, "Shipped batch #1")
        };

        long[] pkgIds = new long[seedBales.length];
        BigDecimal[] pkgWeights = new BigDecimal[seedBales.length];
        BigDecimal[] pkgPricesEur = new BigDecimal[seedBales.length];
        BigDecimal[] pkgPricesHuf = new BigDecimal[seedBales.length];

        for (int i = 0; i < seedBales.length; i++) {
            SeedBale b = seedBales[i];
            BigDecimal weight = new BigDecimal(b.w());
            String qr = codeService.buildCode(b.ts(), b.t(), b.g(), b.q(), b.p(), weight, b.hex());
            long locId = locations.get(b.loc());

            long pkgId = packageDao.insert(
                    conn, qr, targets.get(b.t()), garments.get(b.g()), qualities.get(b.q()), packages.get(b.p()),
                    locId, weight, b.hex(), b.status(), b.note(), b.userId(), b.ts()
            );
            pkgIds[i] = pkgId;
            pkgWeights[i] = weight;
            pkgPricesEur[i] = garmentEur.get(b.g());
            pkgPricesHuf[i] = garmentHuf.get(b.g());

            movementDao.insert(conn, pkgId, MovementType.INTAKE, null, locId, b.userId(), "Initial intake", b.ts());
        }

        // Add a realistic relocate & scan-verify event for traceability
        movementDao.insert(conn, pkgIds[0], MovementType.SCAN_VERIFY, locations.get("A-01-01"), locations.get("A-01-01"),
                managerId, "Weekly stocktake verification", now.minusDays(1).withHour(16).withMinute(0));
        movementDao.insert(conn, pkgIds[2], MovementType.RELOCATE, locations.get("A-01-01"), locations.get("A-01-02"),
                operatorId, "Consolidated denim rack", now.minusDays(2).withHour(12).withMinute(30));

        // 10. Orders & Order Items (both HUF domestic and EUR export showcase)
        // Order 1: SHIPPED in HUF (Hungarian domestic partner, indices 18, 19, 20)
        BigDecimal ord1Weight = pkgWeights[18].add(pkgWeights[19]).add(pkgWeights[20]);
        BigDecimal ord1Price = CurrencyCode.HUF.round(pkgWeights[18].multiply(pkgPricesHuf[18])
                .add(pkgWeights[19].multiply(pkgPricesHuf[19]))
                .add(pkgWeights[20].multiply(pkgPricesHuf[20])));
        long order1Id = orderDao.insert(conn, "ORD-2026-0001", cust2, OrderStatus.SHIPPED, CurrencyCode.HUF,
                ord1Weight, ord1Price, "Completed autumn delivery", managerId, now.minusDays(6), now.minusDays(5));
        for (int idx : new int[]{18, 19, 20}) {
            orderItemDao.insert(conn, order1Id, pkgIds[idx], pkgPricesHuf[idx],
                    CurrencyCode.HUF.round(pkgWeights[idx].multiply(pkgPricesHuf[idx])));
            movementDao.insert(conn, pkgIds[idx], MovementType.SHIP, locations.get(seedBales[idx].loc()), null,
                    managerId, "Shipped on ORD-2026-0001", now.minusDays(5));
        }

        // Order 2: PACKING in EUR (indices 15, 16, 17)
        BigDecimal ord2Weight = pkgWeights[15].add(pkgWeights[16]).add(pkgWeights[17]);
        BigDecimal ord2Price = CurrencyCode.EUR.round(pkgWeights[15].multiply(pkgPricesEur[15])
                .add(pkgWeights[16].multiply(pkgPricesEur[16]))
                .add(pkgWeights[17].multiply(pkgPricesEur[17])));
        long order2Id = orderDao.insert(conn, "ORD-2026-0002", cust1, OrderStatus.PACKING, CurrencyCode.EUR,
                ord2Weight, ord2Price, "Ready for Friday pickup", managerId, now.minusDays(2), null);
        for (int idx : new int[]{15, 16, 17}) {
            orderItemDao.insert(conn, order2Id, pkgIds[idx], pkgPricesEur[idx],
                    CurrencyCode.EUR.round(pkgWeights[idx].multiply(pkgPricesEur[idx])));
            movementDao.insert(conn, pkgIds[idx], MovementType.RESERVE, locations.get(seedBales[idx].loc()),
                    locations.get(seedBales[idx].loc()), managerId, "Reserved for ORD-2026-0002", now.minusDays(1));
        }

        // Order 3: DRAFT in EUR (Slovak export customer, ready to scan bales into during live demo!)
        orderDao.insert(conn, "ORD-2026-0003", cust3, OrderStatus.DRAFT, CurrencyCode.EUR,
                BigDecimal.ZERO, BigDecimal.ZERO, "Export draft — scan bales to pack", adminId, now.minusHours(3), null);
    }
}
