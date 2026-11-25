-- ReWear WMS (due-prog-03) — SQLite schema
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
    assigned_station_id INTEGER,
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT NOT NULL,
    FOREIGN KEY (role_id) REFERENCES roles(id),
    FOREIGN KEY (assigned_station_id) REFERENCES warehouse_locations(id) ON DELETE SET NULL
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

-- 7. Warehouse Storage Locations (Operator Stations, Storage Shelves, Dispatch/Connection Zones)
CREATE TABLE IF NOT EXISTS warehouse_locations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    zone TEXT NOT NULL,
    aisle TEXT NOT NULL,
    shelf TEXT NOT NULL,
    location_type TEXT NOT NULL DEFAULT 'STORAGE'
        CHECK (location_type IN ('OPERATOR_STATION', 'STORAGE', 'DISPATCH_ZONE')),
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
        CHECK (status IN ('IN_STOCK', 'RESERVED', 'QUARANTINED', 'SHIPPED', 'DISCARDED')),
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
    assigned_forklift_id INTEGER,
    dispatch_location_id INTEGER,
    customer_arrived_at TEXT,
    delivery_date TEXT,
    created_at TEXT NOT NULL,
    shipped_at TEXT,
    FOREIGN KEY (customer_id) REFERENCES customers(id),
    FOREIGN KEY (created_by) REFERENCES users(id),
    FOREIGN KEY (assigned_forklift_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (dispatch_location_id) REFERENCES warehouse_locations(id) ON DELETE SET NULL
);

-- 11. Order Line Items (Exact Physical Packages Allocated to Orders)
CREATE TABLE IF NOT EXISTS order_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id INTEGER NOT NULL,
    package_id INTEGER NOT NULL,
    price_per_kg REAL NOT NULL CHECK (price_per_kg >= 0),
    line_total REAL NOT NULL CHECK (line_total >= 0),
    handover_scanned INTEGER NOT NULL DEFAULT 0 CHECK (handover_scanned IN (0, 1)),
    UNIQUE (order_id, package_id),
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    FOREIGN KEY (package_id) REFERENCES inventory_packages(id)
);

-- 12. Stock Movements Audit Trail
CREATE TABLE IF NOT EXISTS stock_movements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_id INTEGER NOT NULL,
    movement_type TEXT NOT NULL
        CHECK (movement_type IN ('INTAKE', 'RELOCATE', 'SCAN_VERIFY', 'RESERVE', 'RELEASE', 'SHIP',
                                 'QUARANTINE', 'QUARANTINE_RELEASE', 'DISCARD')),
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

-- Offline bank accounts and movements
CREATE TABLE IF NOT EXISTS bank_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bank_name TEXT NOT NULL,
    account_name TEXT NOT NULL,
    account_reference TEXT NOT NULL,
    bic TEXT NOT NULL DEFAULT '',
    currency TEXT NOT NULL,
    opening_date TEXT NOT NULL,
    opening_balance TEXT NOT NULL,
    demo INTEGER NOT NULL DEFAULT 0 CHECK (demo IN (0, 1)),
    UNIQUE(bank_name, account_reference, currency),
    FOREIGN KEY (currency) REFERENCES currencies(code)
);

CREATE TABLE IF NOT EXISTS bank_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    booking_date TEXT NOT NULL,
    amount TEXT NOT NULL,
    counterparty TEXT NOT NULL DEFAULT '',
    reference TEXT NOT NULL,
    FOREIGN KEY (account_id) REFERENCES bank_accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_bank_transactions_account_date ON bank_transactions(account_id, booking_date, id);

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
    archived_pdf_path TEXT,
    credit_for_invoice_id INTEGER REFERENCES invoices(id) ON DELETE SET NULL,
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

-- 14b. Invoice lifecycle audit trail: who issued, marked paid, un-paid or reversed an invoice, and when
CREATE TABLE IF NOT EXISTS invoice_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_id INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    from_status TEXT,
    to_status TEXT,
    note TEXT,
    user_id INTEGER,
    created_at TEXT NOT NULL,
    FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_invoice_events_invoice ON invoice_events(invoice_id);

-- 14c. Shift rota: who works which shift on which day
CREATE TABLE IF NOT EXISTS rota_shifts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    work_day TEXT NOT NULL,
    shift_code TEXT NOT NULL CHECK (shift_code IN ('MORNING', 'AFTERNOON', 'NIGHT', 'OFF')),
    start_minute INTEGER NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
    end_minute INTEGER NOT NULL CHECK (end_minute BETWEEN 0 AND 1439),
    note TEXT,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL,
    UNIQUE (user_id, work_day)
);
CREATE INDEX IF NOT EXISTS idx_rota_shifts_day ON rota_shifts(work_day);

-- 14d. Stocktake: counting the shelves and recording what did not match
CREATE TABLE IF NOT EXISTS stocktake_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope_zone TEXT,
    scope_location_id INTEGER REFERENCES warehouse_locations(id) ON DELETE SET NULL,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    note TEXT,
    started_by INTEGER NOT NULL REFERENCES users(id),
    started_at TEXT NOT NULL,
    closed_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    closed_at TEXT
);

CREATE TABLE IF NOT EXISTS stocktake_counts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES stocktake_sessions(id) ON DELETE CASCADE,
    package_id INTEGER NOT NULL REFERENCES inventory_packages(id) ON DELETE CASCADE,
    outcome TEXT NOT NULL CHECK (outcome IN ('FOUND', 'MISPLACED', 'REWEIGHED', 'DAMAGED', 'UNEXPECTED')),
    expected_location_id INTEGER REFERENCES warehouse_locations(id) ON DELETE SET NULL,
    found_location_id INTEGER REFERENCES warehouse_locations(id) ON DELETE SET NULL,
    expected_grams INTEGER NOT NULL,
    counted_grams INTEGER,
    note TEXT,
    counted_by INTEGER NOT NULL REFERENCES users(id),
    counted_at TEXT NOT NULL,
    UNIQUE (session_id, package_id)
);
CREATE INDEX IF NOT EXISTS idx_stocktake_counts_session ON stocktake_counts(session_id);

-- 15. User Email Mailboxes (External SMTP/IMAP & Internal Company Mail)
CREATE TABLE IF NOT EXISTS emails (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id INTEGER NOT NULL,
    folder TEXT NOT NULL DEFAULT 'INBOX' CHECK (folder IN ('INBOX', 'SENT', 'DRAFTS', 'TRASH')),
    from_address TEXT NOT NULL,
    to_addresses TEXT NOT NULL,
    cc_addresses TEXT,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    attachment_path TEXT,
    is_read INTEGER NOT NULL DEFAULT 0 CHECK (is_read IN (0, 1)),
    is_external INTEGER NOT NULL DEFAULT 0 CHECK (is_external IN (0, 1)),
    delivery_status TEXT NOT NULL DEFAULT 'DELIVERED',
    created_at TEXT NOT NULL,
    FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 16. Internal Team Messaging Rooms & Direct Messages (Element-style)
CREATE TABLE IF NOT EXISTS chat_rooms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    room_type TEXT NOT NULL DEFAULT 'ROOM' CHECK (room_type IN ('ROOM', 'DIRECT')),
    topic TEXT,
    created_by INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (created_by) REFERENCES users(id)
);

-- 17. Chat Room Membership & Read Cursors
CREATE TABLE IF NOT EXISTS chat_room_members (
    room_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    last_read_at TEXT,
    PRIMARY KEY (room_id, user_id),
    FOREIGN KEY (room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 18. Chat Messages
CREATE TABLE IF NOT EXISTS chat_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    room_id INTEGER NOT NULL,
    sender_id INTEGER NOT NULL,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (sender_id) REFERENCES users(id)
);

-- 19. Operational Notifications (Putaway alerts, Order staging alerts, Overdue alerts)
CREATE TABLE IF NOT EXISTS notifications (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    category TEXT NOT NULL,
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    target_view TEXT,
    is_read INTEGER NOT NULL DEFAULT 0 CHECK (is_read IN (0, 1)),
    created_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 20. Personal User To-Do Items (Separate from live System To-Do queue)
CREATE TABLE IF NOT EXISTS user_todos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    priority TEXT NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    due_date TEXT,
    completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
    created_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
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
CREATE INDEX IF NOT EXISTS idx_emails_owner_folder ON emails(owner_user_id, folder);
CREATE INDEX IF NOT EXISTS idx_chat_messages_room ON chat_messages(room_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_members_user ON chat_room_members(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_user_read ON notifications(user_id, is_read, created_at);
CREATE INDEX IF NOT EXISTS idx_user_todos_user ON user_todos(user_id, completed);

-- 24. Super-Admin-managed Role -> Module access matrix (each sidebar menu item is a toggleable module)
CREATE TABLE IF NOT EXISTS role_module_permissions (
    role_id     INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    module_code TEXT    NOT NULL,
    enabled     INTEGER NOT NULL DEFAULT 1,
    updated_at  TEXT    NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (role_id, module_code)
);

CREATE TABLE IF NOT EXISTS smart_folders (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope         TEXT NOT NULL CHECK (scope IN ('INVOICE', 'MAIL')),
    name          TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    icon          TEXT NOT NULL,
    UNIQUE (owner_user_id, scope, name COLLATE NOCASE)
);

CREATE TABLE IF NOT EXISTS smart_folder_rules (
    folder_id INTEGER NOT NULL REFERENCES smart_folders(id) ON DELETE CASCADE,
    field     TEXT NOT NULL,
    value     TEXT NOT NULL,
    PRIMARY KEY (folder_id, field, value)
);

-- Sorting production. Raw and finished stock use separate records.
CREATE TABLE IF NOT EXISTS sorting_teams (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE,
    driver_id INTEGER NOT NULL REFERENCES users(id),
    station_id INTEGER NOT NULL REFERENCES warehouse_locations(id),
    target_grams INTEGER NOT NULL DEFAULT 1000000 CHECK (target_grams > 0),
    bonus_basis_points INTEGER CHECK (bonus_basis_points BETWEEN 0 AND 10000)
);

CREATE TABLE IF NOT EXISTS sorting_team_members (
    sorter_id INTEGER PRIMARY KEY REFERENCES users(id),
    team_id INTEGER NOT NULL REFERENCES sorting_teams(id)
);

CREATE TABLE IF NOT EXISTS sorting_team_helpers (
    team_id INTEGER NOT NULL REFERENCES sorting_teams(id),
    helper_id INTEGER NOT NULL REFERENCES users(id),
    PRIMARY KEY (team_id, helper_id)
);

CREATE TABLE IF NOT EXISTS sorting_raw_lots (
    id INTEGER PRIMARY KEY,
    reference TEXT NOT NULL UNIQUE COLLATE NOCASE,
    material TEXT NOT NULL DEFAULT '',
    source_area TEXT NOT NULL DEFAULT '',
    location_id INTEGER REFERENCES warehouse_locations(id),
    received_grams INTEGER NOT NULL CHECK (received_grams > 0),
    available_grams INTEGER NOT NULL CHECK (available_grams >= 0 AND available_grams <= received_grams),
    received_by INTEGER NOT NULL REFERENCES users(id),
    received_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sorting_requests (
    id INTEGER PRIMARY KEY,
    sorter_id INTEGER NOT NULL REFERENCES users(id),
    team_id INTEGER NOT NULL REFERENCES sorting_teams(id),
    driver_id INTEGER NOT NULL REFERENCES users(id),
    station_id INTEGER NOT NULL REFERENCES warehouse_locations(id),
    target_grams INTEGER NOT NULL CHECK (target_grams > 0),
    bonus_basis_points INTEGER CHECK (bonus_basis_points BETWEEN 0 AND 10000),
    requested_grams INTEGER CHECK (requested_grams > 0),
    requested_at TEXT NOT NULL,
    requested_lot_id INTEGER REFERENCES sorting_raw_lots(id),
    lot_id INTEGER REFERENCES sorting_raw_lots(id),
    delivered_grams INTEGER CHECK (delivered_grams > 0
        AND (requested_grams IS NULL OR delivered_grams <= requested_grams)),
    delivered_at TEXT,
    processed_grams INTEGER CHECK (processed_grams >= 0 AND processed_grams <= delivered_grams),
    finished_at TEXT,
    work_day TEXT,
    cancelled_at TEXT,
    CHECK ((lot_id IS NULL AND delivered_grams IS NULL AND delivered_at IS NULL)
        OR (lot_id IS NOT NULL AND delivered_grams IS NOT NULL AND delivered_at IS NOT NULL)),
    CHECK ((processed_grams IS NULL AND finished_at IS NULL AND work_day IS NULL)
        OR (processed_grams IS NOT NULL AND finished_at IS NOT NULL AND work_day IS NOT NULL AND delivered_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS sorting_request_helpers (
    request_id INTEGER NOT NULL REFERENCES sorting_requests(id),
    helper_id INTEGER NOT NULL REFERENCES users(id),
    PRIMARY KEY (request_id, helper_id)
);

CREATE TABLE IF NOT EXISTS sorting_bags (
    id INTEGER PRIMARY KEY,
    request_id INTEGER NOT NULL REFERENCES sorting_requests(id),
    target_group_id INTEGER NOT NULL REFERENCES target_groups(id),
    garment_type_id INTEGER NOT NULL REFERENCES garment_types(id),
    quality_grade_id INTEGER NOT NULL REFERENCES quality_grades(id),
    weight_grams INTEGER NOT NULL CHECK (weight_grams > 0),
    reported_at TEXT NOT NULL,
    package_type_id INTEGER REFERENCES package_types(id),
    packed_at TEXT,
    packed_by INTEGER REFERENCES users(id),
    pack_day TEXT,
    inventory_package_id INTEGER REFERENCES inventory_packages(id) ON DELETE SET NULL,
    registered_by INTEGER REFERENCES users(id),
    registered_at TEXT,
    CHECK ((package_type_id IS NULL AND packed_at IS NULL) OR (package_type_id IS NOT NULL AND packed_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_sorting_bag_inventory ON sorting_bags(inventory_package_id);
CREATE INDEX IF NOT EXISTS idx_sorting_requests_sorter_day ON sorting_requests(sorter_id, work_day);
CREATE INDEX IF NOT EXISTS idx_sorting_bags_request ON sorting_bags(request_id);

CREATE TABLE IF NOT EXISTS order_webshop_references (
    order_id INTEGER PRIMARY KEY REFERENCES orders(id) ON DELETE CASCADE,
    source TEXT NOT NULL COLLATE NOCASE,
    external_id TEXT NOT NULL,
    UNIQUE (source, external_id)
);

CREATE TABLE IF NOT EXISTS order_requested_products (
    id INTEGER PRIMARY KEY,
    order_id INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    target_group_id INTEGER NOT NULL REFERENCES target_groups(id),
    garment_type_id INTEGER NOT NULL REFERENCES garment_types(id),
    quality_grade_id INTEGER NOT NULL REFERENCES quality_grades(id),
    package_type_id INTEGER NOT NULL REFERENCES package_types(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    UNIQUE (order_id, target_group_id, garment_type_id, quality_grade_id, package_type_id)
);

CREATE TABLE IF NOT EXISTS payroll_departments (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL COLLATE NOCASE UNIQUE,
    sorting_team_id INTEGER UNIQUE REFERENCES sorting_teams(id)
);

CREATE TABLE IF NOT EXISTS payroll_worker_base (
    worker_id INTEGER NOT NULL REFERENCES users(id),
    effective_from TEXT NOT NULL,
    monthly_gross_huf INTEGER NOT NULL CHECK (monthly_gross_huf >= 0),
    changed_by INTEGER NOT NULL REFERENCES users(id),
    PRIMARY KEY (worker_id, effective_from)
);

CREATE TABLE IF NOT EXISTS payroll_department_rates (
    department_id INTEGER NOT NULL REFERENCES payroll_departments(id),
    role_code TEXT NOT NULL REFERENCES roles(code),
    effective_from TEXT NOT NULL,
    daily_gross_huf INTEGER NOT NULL CHECK (daily_gross_huf >= 0),
    bonus_basis_points INTEGER NOT NULL CHECK (bonus_basis_points BETWEEN 0 AND 10000),
    changed_by INTEGER NOT NULL REFERENCES users(id),
    PRIMARY KEY (department_id, role_code, effective_from)
);

CREATE TABLE IF NOT EXISTS payroll_shifts (
    id INTEGER PRIMARY KEY,
    worker_id INTEGER NOT NULL REFERENCES users(id),
    work_day TEXT NOT NULL,
    department_id INTEGER NOT NULL REFERENCES payroll_departments(id),
    role_code TEXT NOT NULL REFERENCES roles(code),
    shift_basis_points INTEGER NOT NULL CHECK (shift_basis_points BETWEEN 1 AND 10000),
    approved_bonus INTEGER NOT NULL DEFAULT 0 CHECK (approved_bonus IN (0, 1)),
    saved_by INTEGER NOT NULL REFERENCES users(id),
    UNIQUE (worker_id, work_day, department_id, role_code)
);
