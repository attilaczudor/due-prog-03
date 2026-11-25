package hu.due.rewear.config;

import hu.due.rewear.dao.ChatDao;
import hu.due.rewear.dao.CustomerDao;
import hu.due.rewear.dao.DictionaryDao;
import hu.due.rewear.dao.EmailDao;
import hu.due.rewear.dao.InventoryPackageDao;
import hu.due.rewear.dao.InvoiceDao;
import hu.due.rewear.dao.OrderDao;
import hu.due.rewear.dao.OrderItemDao;
import hu.due.rewear.dao.RoleDao;
import hu.due.rewear.dao.StockMovementDao;
import hu.due.rewear.dao.UserDao;
import hu.due.rewear.dao.WarehouseLocationDao;
import hu.due.rewear.model.ChatRoom;
import hu.due.rewear.model.CountryDirectory;
import hu.due.rewear.model.CurrencyCode;
import hu.due.rewear.model.CurrencyDef;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.EmailMessage;
import hu.due.rewear.model.EmailSettings;
import hu.due.rewear.model.InvoiceCategory;
import hu.due.rewear.model.InvoiceDirection;
import hu.due.rewear.model.InvoiceStatus;
import hu.due.rewear.model.LocationType;
import hu.due.rewear.model.MovementType;
import hu.due.rewear.model.Order;
import hu.due.rewear.model.OrderItem;
import hu.due.rewear.model.OrderStatus;
import hu.due.rewear.model.PackageStatus;
import hu.due.rewear.model.PartnerType;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;
import hu.due.rewear.service.ProductCodeService;
import hu.due.rewear.util.BusinessClock;
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
    private final EmailDao emailDao;
    private final ChatDao chatDao;
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
        this.emailDao = new EmailDao(db);
        this.chatDao = new ChatDao(db);
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
        new SortingDemoSeeder(db).seedForDemoWorkspace();
        seedStarterInvoicesIfEmpty();
        seedStarterEmailsAndChatsIfEmpty();
    }

    /**
     * Keeps an already-existing demo workspace current across versions (the sorting roster). Unlike
     * {@link #seedIfEmpty()} it adds no invoices, mail or chat, and it does nothing unless the demo
     * operator account still has its published password, so a real company is never touched.
     */
    public void upgradeDemoWorkspace() {
        new SortingDemoSeeder(db).seedForDemoWorkspace();
    }

    private void seedStarterEmailsAndChatsIfEmpty() {
        List<User> users = userDao.findAll();
        if (users.isEmpty()) {
            return;
        }
        User superAdmin = users.stream().filter(u -> "superadmin".equalsIgnoreCase(u.username())).findFirst().orElse(users.get(0));
        User manager = users.stream().filter(u -> "manager".equalsIgnoreCase(u.username())).findFirst().orElse(superAdmin);
        User operator = users.stream().filter(u -> "operator".equalsIgnoreCase(u.username())).findFirst().orElse(superAdmin);
        User forklift = users.stream().filter(u -> "forklift".equalsIgnoreCase(u.username())).findFirst().orElse(superAdmin);
        LocalDateTime now = BusinessClock.now();

        if (emailDao.countAll() == 0) {
            db.runInTransaction(conn -> {
                for (User u : users) {
                    String toAddr = u.email() != null && !u.email().isBlank()
                            ? u.email()
                            : EmailSettings.defaults().generateUserEmail(u.safeFirstName(), u.safeLastName(), u.username());
                    emailDao.insert(conn, u.id(), EmailMessage.FOLDER_INBOX,
                            "attila.czudor@rewear-wms.hu", toAddr, "",
                            "Üdvözlünk a ReWear WMS vállalati levelezőben (@rewear-wms.hu)",
                            "Szia " + u.displayName() + ",\n\n"
                                    + "Elkészült a vállalati e-mail fiókod: " + toAddr + "\n"
                                    + "Innen tudsz belső csapattagoknak és külső partnereknek/beszállítóknak is e-mailt küldeni.\n\n"
                                    + "Üdvözlettel,\nCzudor Attila — ReWear Logistics Kft.",
                            null, false, false, "DELIVERED", now.minusHours(5));
                }
                emailDao.insert(conn, superAdmin.id(), EmailMessage.FOLDER_INBOX,
                        "office@alpinesecondhand.at", "attila.czudor@rewear-wms.hu", "eszter.kovacs@rewear-wms.hu",
                        "Re: Weekly Cream & Grade-A Textile Order (EUR)",
                        "Dear Attila & Eszter,\n\n"
                                + "Could you please confirm availability of 400 kg Ladies Cream Dresses and Grade A T-Shirts for next Tuesday's pickup?\n"
                                + "Please send the EUR 0% EU B2B invoice once packed at DOCK-01.\n\n"
                                + "Best regards,\nLukas Gruber — Alpine Second-Hand GmbH",
                        null, false, true, "RECEIVED (IMAP: imap.rewear-wms.hu:993)", now.minusHours(2));
                emailDao.insert(conn, manager.id(), EmailMessage.FOLDER_INBOX,
                        "office@alpinesecondhand.at", "eszter.kovacs@rewear-wms.hu", "attila.czudor@rewear-wms.hu",
                        "Re: Weekly Cream & Grade-A Textile Order (EUR)",
                        "Dear Eszter,\n\n"
                                + "Please let us know when the next EUR shipment is staged at DOCK-01.\n\n"
                                + "Best regards,\nLukas Gruber — Alpine Second-Hand GmbH",
                        null, false, true, "RECEIVED (IMAP: imap.rewear-wms.hu:993)", now.minusHours(2));
            });
            LOG.info("Seeded starter internal & external demo emails.");
        }

        if (chatDao.countRooms() == 0) {
            db.runInTransaction(conn -> {
                long generalId = chatDao.createRoom(conn, "#általános", ChatRoom.TYPE_ROOM,
                        "Céges közlemények és napi egyeztetés", superAdmin.id(), now.minusDays(2));
                long opsId = chatDao.createRoom(conn, "#raktár-logisztika", ChatRoom.TYPE_ROOM,
                        "Válogató állomások (ST-OP) ↔ Targonca ↔ Kiadó zóna (DOCK-01)", manager.id(), now.minusDays(2));
                long financeId = chatDao.createRoom(conn, "#számlázás", ChatRoom.TYPE_ROOM,
                        "EUR / HUF számlák, ÁFA és kintlévőségek", superAdmin.id(), now.minusDays(2));

                for (User u : users) {
                    chatDao.addMember(conn, generalId, u.id());
                    chatDao.addMember(conn, opsId, u.id());
                    if (u.roleCode() == RoleCode.SUPER_ADMIN || u.roleCode() == RoleCode.ADMIN || u.roleCode() == RoleCode.MANAGER) {
                        chatDao.addMember(conn, financeId, u.id());
                    }
                }

                chatDao.insertMessage(conn, generalId, superAdmin.id(),
                        "Sziasztok! Elindult a belső üzenetküldő — a raktári mozgatásokat a #raktár-logisztika szobában egyeztessük.",
                        now.minusHours(4));
                chatDao.insertMessage(conn, opsId, operator.id(),
                        "Kész a reggeli válogatás az ST-OP-01 és ST-OP-02 állomáson, vihető a polcokra!",
                        now.minusHours(2));
                chatDao.insertMessage(conn, opsId, forklift.id(),
                        "Vettem, indulok az ST-OP-01 felé, utána viszem a nyitott rendelés báláit a DOCK-01 zónába.",
                        now.minusHours(1));
                chatDao.insertMessage(conn, financeId, manager.id(),
                        "Az Alpine Second-Hand GmbH rendelése 0% EU-0 ÁFA-val, EUR főkönyvben számlázható.",
                        now.minusMinutes(45));

                if (operator.id() != forklift.id()) {
                    long dmId = chatDao.createRoom(conn, operator.displayName() + " ↔ " + forklift.displayName(),
                            ChatRoom.TYPE_DIRECT, "Direct Message", operator.id(), now.minusHours(3));
                    chatDao.addMember(conn, dmId, operator.id());
                    chatDao.addMember(conn, dmId, forklift.id());
                    chatDao.insertMessage(conn, dmId, operator.id(),
                            "Szia Márk, az ST-OP-01-en lévő 35 kg-os krém farmer bálát légy szíves az A-01-02-re tedd!",
                            now.minusMinutes(30));
                }
            });
            LOG.info("Seeded starter Element-style team chat rooms & DMs.");
        }
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
        // Pre-fetch related items & customers before opening the write transaction to avoid nested SQLite connections
        Map<Long, List<OrderItem>> itemsByOrderId = new HashMap<>();
        Map<Long, Customer> customerById = new HashMap<>();
        for (Order ord : orders) {
            if (ord.status() == OrderStatus.SHIPPED || ord.status() == OrderStatus.PACKING) {
                itemsByOrderId.put(ord.id(), orderItemDao.findByOrderId(ord.id()));
                customerById.computeIfAbsent(ord.customerId(),
                        cid -> customerDao.findById(cid).orElse(null));
            }
        }
        BigDecimal vat27 = new BigDecimal("27.00");
        LocalDate today = BusinessClock.today();
        LocalDateTime now = BusinessClock.now();

        db.runInTransaction(conn -> {
            // Outgoing invoices linked to shipped/active demo orders
            int outSeq = 1;
            for (Order ord : orders) {
                if (ord.status() == OrderStatus.SHIPPED || ord.status() == OrderStatus.PACKING) {
                    List<OrderItem> items = itemsByOrderId.getOrDefault(ord.id(), List.of());
                    if (items.isEmpty()) continue;
                    Customer cust = customerById.get(ord.customerId());
                    String cur = ord.effectiveCurrencyCode();
                    int dec = ord.currencyDecimals();
                    boolean isHu = cust == null || CountryDirectory.isHungary(cust.effectiveCountryCode());
                    BigDecimal appliedVat = isHu ? vat27 : BigDecimal.ZERO;
                    String taxCode = isHu ? "HU-27" : "EU-0";
                    BigDecimal net = CurrencyDef.roundForScale(ord.totalPrice(), dec);
                    BigDecimal vat = CurrencyDef.roundForScale(
                            net.multiply(appliedVat).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP), dec);
                    BigDecimal gross = CurrencyDef.roundForScale(net.add(vat), dec);
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
                            net, appliedVat, taxCode, vat, gross, cur, CurrencyCode.DEFAULT_EUR_HUF_RATE,
                            creatorId, now.minusDays(paid ? 5 : 1));
                    for (OrderItem oi : items) {
                        String desc = oi.packageQrCode() + " (" + oi.summaryEn() + ")";
                        invoiceDao.insertItem(conn, invId, desc, oi.weightKg(), "kg",
                                CurrencyDef.roundForScale(oi.pricePerKg(), dec),
                                CurrencyDef.roundForScale(oi.lineTotal(), dec));
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
        LocalDateTime now = BusinessClock.now().withSecond(0).withNano(0);

        // 1. Roles
        long superAdminRoleId = roleDao.ensure(conn, RoleCode.SUPER_ADMIN, "Super Administrator");
        long adminRoleId = roleDao.ensure(conn, RoleCode.ADMIN, "Administrator");
        long managerRoleId = roleDao.ensure(conn, RoleCode.MANAGER, "Warehouse Manager");
        long operatorRoleId = roleDao.ensure(conn, RoleCode.OPERATOR, "Station Manager");
        long supplyDriverRoleId = roleDao.ensure(conn, RoleCode.SUPPLY_DRIVER, "Supply Forklift Driver");

        // 2. Demo Users (BCrypt) with auto-populated @rewear-wms.hu corporate emails
        long superId = userDao.insert(conn, "superadmin", BCrypt.hashpw("superadmin123", BCrypt.gensalt(10)),
                "Czudor Attila", superAdminRoleId, true, now.minusDays(15));
        long adminId = userDao.insert(conn, "admin", BCrypt.hashpw("admin123", BCrypt.gensalt(10)),
                "Szabó Gábor", adminRoleId, true, now.minusDays(14));
        long managerId = userDao.insert(conn, "manager", BCrypt.hashpw("manager123", BCrypt.gensalt(10)),
                "Kovács Eszter", managerRoleId, true, now.minusDays(12));
        long operatorId = userDao.insert(conn, "operator", BCrypt.hashpw("operator123", BCrypt.gensalt(10)),
                "Nagy Bence", operatorRoleId, true, now.minusDays(10));
        long forkliftId = userDao.insert(conn, "forklift", BCrypt.hashpw("forklift123", BCrypt.gensalt(10)),
                "Tóth Márk", supplyDriverRoleId, true, now.minusDays(9));
        long deliveryRoleId = roleDao.ensure(conn, RoleCode.DELIVERY_DRIVER, "Delivery Forklift Driver");
        long deliveryId = userDao.insert(conn, "forklift2", BCrypt.hashpw("forklift2123", BCrypt.gensalt(10)),
                "Kelemen Ádám", deliveryRoleId, true, now.minusDays(9));

        try (var ps = conn.prepareStatement("UPDATE users SET email = ? WHERE id = ?")) {
            Object[][] emails = {
                    {superId, "attila.czudor@rewear-wms.hu"},
                    {adminId, "gabor.szabo@rewear-wms.hu"},
                    {managerId, "eszter.kovacs@rewear-wms.hu"},
                    {operatorId, "bence.nagy@rewear-wms.hu"},
                    {forkliftId, "mark.toth@rewear-wms.hu"},
                    {deliveryId, "adam.kelemen@rewear-wms.hu"}
            };
            for (Object[] row : emails) {
                ps.setString(1, (String) row[1]);
                ps.setLong(2, (Long) row[0]);
                ps.executeUpdate();
            }
        }

        // 3-6. Reference catalogues and 7. starter shelf layout, shared with real company workspaces
        StarterData.Catalogs catalogs = StarterData.insertCatalogs(conn, dictionaryDao);
        Map<String, Long> targets = catalogs.targets();
        Map<String, Long> garments = catalogs.garments();
        Map<String, Long> qualities = catalogs.qualities();
        Map<String, Long> packages = catalogs.packages();
        Map<String, BigDecimal> garmentEur = catalogs.garmentEur();
        Map<String, BigDecimal> garmentHuf = catalogs.garmentHuf();
        Map<String, Long> locations = StarterData.insertLocations(conn, locationDao);

        // Assign demo operator to ST-OP-01 by default
        try (var ps = conn.prepareStatement("UPDATE users SET assigned_station_id = ? WHERE id = ?")) {
            ps.setLong(1, locations.get("ST-OP-01"));
            ps.setLong(2, operatorId);
            ps.executeUpdate();
        }

        // 8. Business Partners: Buyers (Customers) + Providers (Suppliers) across HU, AT, SK, DE
        long cust1 = customerDao.insert(conn, "Duna Vintage Kft.", PartnerType.CUSTOMER, "Szabó Márta", "marta@dunavintage.hu",
                "+36 30 555 1201", "2400 Dunaújváros, Vasmű út 18.", "24891234-2-07", "HU", "HUF", now.minusDays(20));
        long cust2 = customerDao.insert(conn, "Pannon Textil Outlet Bt.", PartnerType.CUSTOMER, "Tóth Gábor", "rendeles@pannontextil.hu",
                "+36 20 444 9832", "8000 Székesfehérvár, Budai út 44.", "21904411-2-07", "HU", "HUF", now.minusDays(18));
        long cust3 = customerDao.insert(conn, "GreenLoop Second-Hand S.R.O.", PartnerType.CUSTOMER, "Jana Nováková", "supply@greenloop.sk",
                "+421 905 331 890", "811 06 Bratislava, Obchodná 22", "SK2024119822", "SK", "EUR", now.minusDays(15));
        customerDao.insert(conn, "Alföld Ruhabázis Kft.", PartnerType.CUSTOMER, "Varga László", "info@alfoldruha.hu",
                "+36 70 312 6640", "6000 Kecskemét, Izsáki út 10.", "26778890-2-03", "HU", "HUF", now.minusDays(9));
        customerDao.insert(conn, "Alpine Second-Hand GmbH", PartnerType.CUSTOMER, "Lukas Gruber", "office@alpinesecondhand.at",
                "+43 1 512 8890", "1010 Wien, Mariahilfer Str. 48", "ATU68219034", "AT", "EUR", now.minusDays(7));
        // Providers / Suppliers we pay
        customerDao.insert(conn, "EuroTex Import GmbH", PartnerType.SUPPLIER, "Klaus Weber", "billing@eurotex-import.de",
                "+49 89 4120 991", "80331 München, Industriestr. 14", "DE294810221", "DE", "EUR", now.minusDays(14));
        customerDao.insert(conn, "Duna Ipari Park Kft.", PartnerType.SUPPLIER, "Kovács Zoltán", "penzugy@dunaiparipark.hu",
                "+36 25 510 200", "2400 Dunaújváros, Papírgyári út 12.", "14820911-2-07", "HU", "HUF", now.minusDays(14));
        customerDao.insert(conn, "MVM Next Energiakereskedelmi Zrt.", PartnerType.SUPPLIER, "Ügyfélszolgálat", "szamlazas@mvm.hu",
                "+36 1 474 9999", "1081 Budapest, II. János Pál pápa tér 20.", "26713111-2-44", "HU", "HUF", now.minusDays(14));

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
                new SeedBale(now.withHour(8).withMinute(15), "L", "JNS", "CR", "A", "35", "88", "ST-OP-01", PackageStatus.IN_STOCK, operatorId, "Today's morning sort — awaiting forklift putaway"),
                new SeedBale(now.withHour(9).withMinute(40), "K", "SHO", "A", "C", "16", "F0", "ST-OP-02", PackageStatus.IN_STOCK, operatorId, "Kids paired shoes — awaiting forklift putaway"),
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
