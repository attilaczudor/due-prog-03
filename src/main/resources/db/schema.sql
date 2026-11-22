-- ReWear WMS (due-prog-03) — 3NF SQLite Schema (17 Tables)
PRAGMA foreign_keys = ON;

-- 0. Portable Workspace Settings
CREATE TABLE IF NOT EXISTS app_settings (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL
);

-- 0b. Configurable Currencies (HUF, EUR, USD, GBP, ...)
CREATE TABLE IF NOT EXISTS currencies (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL,
    name_hu TEXT NOT NULL,
    symbol TEXT NOT NULL,
    decimal_places INTEGER NOT NULL DEFAULT 2 CHECK (decimal_places IN (0, 2)),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- 0c. Configurable VAT / Tax Types (HU 27%, EU B2B 0%, Export 0%, ...)
CREATE TABLE IF NOT EXISTS tax_types (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL,
    name_hu TEXT NOT NULL,
    rate_percent REAL NOT NULL DEFAULT 0 CHECK (rate_percent >= 0),
    country_scope TEXT NOT NULL DEFAULT 'ANY' CHECK (country_scope IN ('HU', 'EU', 'NON_EU', 'ANY')),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- 1. Roles
CREATE TABLE IF NOT EXISTS roles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL
);

-- 2. Users
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    full_name TEXT NOT NULL,
    last_name TEXT,
    first_name TEXT,
    email TEXT,
    phone TEXT,
    avatar_png BLOB,
    role_id INTEGER NOT NULL,
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT NOT NULL,
    FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- 3. Target Groups (WHATFOR segment, e.g. L, M, K, B, U, H)
CREATE TABLE IF NOT EXISTS target_groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL,
    name_hu TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- 4. Garment / Product Types (TYPE segment, e.g. TSH, JNS, JKT, SHO)
CREATE TABLE IF NOT EXISTS garment_types (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL,
    name_hu TEXT NOT NULL,
    price_eur_per_kg REAL NOT NULL DEFAULT 0 CHECK (price_eur_per_kg >= 0),
    price_huf_per_kg REAL NOT NULL DEFAULT 0 CHECK (price_huf_per_kg >= 0),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- 5. Quality Grades (QUALITY segment, e.g. CR, A, B, C)
CREATE TABLE IF NOT EXISTS quality_grades (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL,
    name_hu TEXT NOT NULL,
    default_price_per_kg REAL NOT NULL DEFAULT 0 CHECK (default_price_per_kg >= 0),
    default_price_huf_per_kg REAL NOT NULL DEFAULT 0 CHECK (default_price_huf_per_kg >= 0),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- 6. Package Types (PACKAGETYPE segment, e.g. A, B, C, P)
CREATE TABLE IF NOT EXISTS package_types (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL,
    name_hu TEXT NOT NULL,
    tare_weight_kg REAL NOT NULL DEFAULT 0 CHECK (tare_weight_kg >= 0),
    max_weight_kg REAL NOT NULL DEFAULT 100 CHECK (max_weight_kg > 0),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- 7. Warehouse Storage Locations
CREATE TABLE IF NOT EXISTS warehouse_locations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    zone TEXT NOT NULL,
    aisle TEXT NOT NULL,
    shelf TEXT NOT NULL,
    max_capacity_kg REAL NOT NULL CHECK (max_capacity_kg > 0),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- 8. Core Inventory Packages (Sorted Second-Hand Clothing Bales/Bags/Boxes)
CREATE TABLE IF NOT EXISTS inventory_packages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    qr_code TEXT NOT NULL UNIQUE,
    target_group_id INTEGER NOT NULL,
    garment_type_id INTEGER NOT NULL,
    quality_grade_id INTEGER NOT NULL,
    package_type_id INTEGER NOT NULL,
    location_id INTEGER NOT NULL,
    weight_kg REAL NOT NULL CHECK (weight_kg > 0),
    hex_suffix TEXT NOT NULL CHECK (length(hex_suffix) = 2),
    status TEXT NOT NULL DEFAULT 'IN_STOCK'
        CHECK (status IN ('IN_STOCK', 'RESERVED', 'SHIPPED', 'DISCARDED')),
    notes TEXT,
    created_by INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (target_group_id) REFERENCES target_groups(id),
    FOREIGN KEY (garment_type_id) REFERENCES garment_types(id),
    FOREIGN KEY (quality_grade_id) REFERENCES quality_grades(id),
    FOREIGN KEY (package_type_id) REFERENCES package_types(id),
    FOREIGN KEY (location_id) REFERENCES warehouse_locations(id),
    FOREIGN KEY (created_by) REFERENCES users(id)
);

-- 9. B2B Business Partners (Buyers / Customers & Providers / Suppliers)
CREATE TABLE IF NOT EXISTS customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    partner_type TEXT NOT NULL DEFAULT 'CUSTOMER' CHECK (partner_type IN ('CUSTOMER', 'SUPPLIER', 'BOTH')),
    contact_name TEXT,
    email TEXT,
    phone TEXT,
    address TEXT,
    tax_number TEXT,
    country_code TEXT NOT NULL DEFAULT 'HU',
    preferred_currency TEXT NOT NULL DEFAULT 'EUR',
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT NOT NULL
);

-- 10. Customer Orders
CREATE TABLE IF NOT EXISTS orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_number TEXT NOT NULL UNIQUE,
    customer_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PACKING', 'SHIPPED', 'CANCELLED')),
    currency TEXT NOT NULL DEFAULT 'EUR',
    total_weight_kg REAL NOT NULL DEFAULT 0 CHECK (total_weight_kg >= 0),
    total_price REAL NOT NULL DEFAULT 0 CHECK (total_price >= 0),
    notes TEXT,
    created_by INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    shipped_at TEXT,
    FOREIGN KEY (customer_id) REFERENCES customers(id),
    FOREIGN KEY (created_by) REFERENCES users(id)
);

-- 11. Order Line Items (Exact Physical Packages Allocated to Orders)
CREATE TABLE IF NOT EXISTS order_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id INTEGER NOT NULL,
    package_id INTEGER NOT NULL,
    price_per_kg REAL NOT NULL CHECK (price_per_kg >= 0),
    line_total REAL NOT NULL CHECK (line_total >= 0),
    UNIQUE (order_id, package_id),
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    FOREIGN KEY (package_id) REFERENCES inventory_packages(id)
);

-- 12. Stock Movements Audit Trail
CREATE TABLE IF NOT EXISTS stock_movements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_id INTEGER NOT NULL,
    movement_type TEXT NOT NULL
        CHECK (movement_type IN ('INTAKE', 'RELOCATE', 'SCAN_VERIFY', 'RESERVE', 'RELEASE', 'SHIP', 'DISCARD')),
    from_location_id INTEGER,
    to_location_id INTEGER,
    user_id INTEGER NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (package_id) REFERENCES inventory_packages(id),
    FOREIGN KEY (from_location_id) REFERENCES warehouse_locations(id),
    FOREIGN KEY (to_location_id) REFERENCES warehouse_locations(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 13. Invoices (Outgoing Revenue & Incoming Expenses)
CREATE TABLE IF NOT EXISTS invoices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_number TEXT NOT NULL UNIQUE,
    direction TEXT NOT NULL CHECK (direction IN ('OUTGOING', 'INCOMING')),
    category TEXT NOT NULL,
    order_id INTEGER,
    customer_id INTEGER,
    partner_name TEXT NOT NULL,
    partner_tax_number TEXT,
    partner_address TEXT,
    partner_email TEXT,
    status TEXT NOT NULL DEFAULT 'ISSUED'
        CHECK (status IN ('DRAFT', 'ISSUED', 'PAID', 'OVERDUE', 'CANCELLED')),
    issue_date TEXT NOT NULL,
    due_date TEXT NOT NULL,
    paid_date TEXT,
    net_amount REAL NOT NULL DEFAULT 0 CHECK (net_amount >= 0),
    vat_rate REAL NOT NULL DEFAULT 27 CHECK (vat_rate >= 0),
    tax_type_code TEXT,
    vat_amount REAL NOT NULL DEFAULT 0 CHECK (vat_amount >= 0),
    gross_amount REAL NOT NULL DEFAULT 0 CHECK (gross_amount >= 0),
    currency TEXT NOT NULL DEFAULT 'EUR',
    exchange_rate REAL NOT NULL DEFAULT 400.0 CHECK (exchange_rate > 0),
    attachment_path TEXT,
    created_by INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE SET NULL,
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE SET NULL,
    FOREIGN KEY (created_by) REFERENCES users(id)
);

-- 14. Invoice Line Items
CREATE TABLE IF NOT EXISTS invoice_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_id INTEGER NOT NULL,
    description TEXT NOT NULL,
    quantity REAL NOT NULL CHECK (quantity > 0),
    unit TEXT NOT NULL DEFAULT 'kg',
    unit_price REAL NOT NULL CHECK (unit_price >= 0),
    line_net REAL NOT NULL CHECK (line_net >= 0),
    FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
);

-- Performance Indexes
CREATE INDEX IF NOT EXISTS idx_packages_qr_code ON inventory_packages(qr_code);
CREATE INDEX IF NOT EXISTS idx_packages_status ON inventory_packages(status);
CREATE INDEX IF NOT EXISTS idx_packages_location ON inventory_packages(location_id);
CREATE INDEX IF NOT EXISTS idx_packages_created_at ON inventory_packages(created_at);
CREATE INDEX IF NOT EXISTS idx_movements_package ON stock_movements(package_id);
CREATE INDEX IF NOT EXISTS idx_invoices_direction ON invoices(direction);
CREATE INDEX IF NOT EXISTS idx_invoices_order ON invoices(order_id);
CREATE INDEX IF NOT EXISTS idx_invoices_customer ON invoices(customer_id);
CREATE INDEX IF NOT EXISTS idx_invoice_items_invoice ON invoice_items(invoice_id);
CREATE INDEX IF NOT EXISTS idx_movements_created_at ON stock_movements(created_at);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_package ON order_items(package_id);
