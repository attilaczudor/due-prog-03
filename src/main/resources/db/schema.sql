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
    price_eur_per_kg TEXT NOT NULL DEFAULT '0',
    price_huf_per_kg TEXT NOT NULL DEFAULT '0',
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- 5. Quality Grades (QUALITY segment, e.g. CR, A, B, C)
CREATE TABLE IF NOT EXISTS quality_grades (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL,
    name_hu TEXT NOT NULL,
    default_price_per_kg TEXT NOT NULL DEFAULT '0',
    default_price_huf_per_kg TEXT NOT NULL DEFAULT '0',
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- 6. Package Types (PACKAGETYPE segment, e.g. A, B, C, P)
CREATE TABLE IF NOT EXISTS package_types (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL,
    name_hu TEXT NOT NULL,
    tare_weight_kg TEXT NOT NULL DEFAULT '0',
    max_weight_kg TEXT NOT NULL DEFAULT '100',
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
    max_capacity_kg TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

-- Money and mass are stored as exact decimal strings (for example '1800.00'), never as floating point
-- REAL, so a value is read back exactly as it was written. The bank ledger already did this for money.
-- Mass is included because the extended-producer-responsibility fee is charged per kilogram and the
-- penalty for under-reporting is half the shortfall plus the fee, so a recorded mass is a financial
-- control. Rates (VAT percentage, exchange rate) stay REAL: the values in use are exact in binary.

-- 8. Core Inventory Packages (Sorted Second-Hand Clothing Bales/Bags/Boxes)
CREATE TABLE IF NOT EXISTS inventory_packages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    qr_code TEXT NOT NULL UNIQUE,
    target_group_id INTEGER NOT NULL,
    garment_type_id INTEGER NOT NULL,
    quality_grade_id INTEGER NOT NULL,
    package_type_id INTEGER NOT NULL,
    location_id INTEGER NOT NULL,
    weight_kg TEXT NOT NULL,
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
    total_weight_kg TEXT NOT NULL DEFAULT '0',
    total_price TEXT NOT NULL DEFAULT '0',
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
    price_per_kg TEXT NOT NULL,
    line_total TEXT NOT NULL,
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
    net_amount TEXT NOT NULL DEFAULT '0',
    vat_rate REAL NOT NULL DEFAULT 27 CHECK (vat_rate >= 0),
    tax_type_code TEXT,
    vat_amount TEXT NOT NULL DEFAULT '0',
    gross_amount TEXT NOT NULL DEFAULT '0',
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
    quantity TEXT NOT NULL,
    unit TEXT NOT NULL DEFAULT 'kg',
    unit_price TEXT NOT NULL,
    line_net TEXT NOT NULL,
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
    updated_at  TEXT    NOT NULL,
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

-- 46. NAV Online Számla reporting
-- One row per report sent, or owed, to the tax authority. The obligation is met only when NAV has
-- processed the report, so the state here — not the invoice's own status — is what says whether an
-- issued invoice is known to the authority. The transaction id is the evidence that it is.
CREATE TABLE IF NOT EXISTS nav_invoice_reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_id INTEGER NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    operation TEXT NOT NULL CHECK (operation IN ('CREATE', 'MODIFY', 'STORNO', 'ANNUL')),
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'SUBMITTED', 'DONE', 'WARN', 'ERROR', 'ABORTED', 'ANNUL_REQUESTED')),
    request_id TEXT,
    transaction_id TEXT,
    invoice_index INTEGER NOT NULL DEFAULT 1,
    response_code TEXT,
    message TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT,
    submitted_at TEXT,
    completed_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Every state change, kept because the report is the company's evidence of having reported.
CREATE TABLE IF NOT EXISTS nav_report_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id INTEGER NOT NULL REFERENCES nav_invoice_reports(id) ON DELETE CASCADE,
    from_state TEXT,
    to_state TEXT NOT NULL,
    detail TEXT,
    created_by INTEGER REFERENCES users(id),
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_nav_reports_invoice ON nav_invoice_reports(invoice_id);
CREATE INDEX IF NOT EXISTS idx_nav_reports_state ON nav_invoice_reports(state, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_nav_report_events_report ON nav_report_events(report_id, id);

-- 48. Purchase lots: where unsorted material came from, and what it legally is
-- Sorting draws from raw lots; raw lots come from a purchase lot; so every package and every order can be
-- traced back to a supplier, a country and a collection channel. The legal status is a judgement a named
-- person made at a named place, because that is what an export declaration turns on, and a judgement with
-- nobody behind it is worth nothing when it is questioned.
-- The supplier is a row in `customers`: that table already distinguishes CUSTOMER, SUPPLIER and BOTH, and a
-- second master list of the same companies would only drift apart from the first.
CREATE TABLE IF NOT EXISTS purchase_lots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    reference TEXT NOT NULL UNIQUE COLLATE NOCASE,
    supplier_id INTEGER REFERENCES customers(id),
    origin_country TEXT NOT NULL,
    collection_channel TEXT NOT NULL CHECK (collection_channel IN
        ('HOUSEHOLD_COLLECTION', 'DIRECT_HANDOVER', 'COMMERCIAL_PURCHASE', 'IMPORT', 'OWN_COLLECTION')),
    customs_code TEXT,
    customs_description TEXT,
    purchase_price_per_kg TEXT NOT NULL DEFAULT '0',
    currency TEXT NOT NULL DEFAULT 'EUR',
    gross_weight_kg TEXT NOT NULL,
    net_weight_kg TEXT NOT NULL,
    legal_status TEXT NOT NULL DEFAULT 'UNASSESSED'
        CHECK (legal_status IN ('UNASSESSED', 'WASTE', 'FIT_FOR_REUSE')),
    assessed_by INTEGER REFERENCES users(id),
    assessed_at TEXT,
    assessment_basis TEXT,
    assessment_place TEXT,
    invoice_id INTEGER REFERENCES invoices(id) ON DELETE SET NULL,
    notes TEXT,
    received_by INTEGER NOT NULL REFERENCES users(id),
    received_on TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS purchase_lot_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    lot_id INTEGER NOT NULL REFERENCES purchase_lots(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    detail TEXT,
    created_by INTEGER REFERENCES users(id),
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_purchase_lots_supplier ON purchase_lots(supplier_id);
CREATE INDEX IF NOT EXISTS idx_purchase_lots_received ON purchase_lots(received_on);
CREATE INDEX IF NOT EXISTS idx_purchase_lot_events_lot ON purchase_lot_events(lot_id, id);

-- 50. Grade yield baselines
-- The mix a sorting line produces is the measurement the business runs on: premium grade is about a tenth
-- of the weight and over half of the revenue, so a point of mix is worth far more than a point of
-- throughput. What a *good* mix looks like, though, is a property of this site's own material and people.
-- The published cascades are Nordic 2014 figures and the reusable/recyclable/waste split swings from
-- 53/33/14 to 80/13/6 between countries, so this application ships no target at all. A baseline is captured
-- from a period of the site's own history, deliberately, by a named person, and frozen: variance measured
-- against a figure that drifts every time it is recomputed means nothing.
CREATE TABLE IF NOT EXISTS grade_yield_baselines (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    period_from TEXT NOT NULL,
    period_to TEXT NOT NULL,
    processed_grams INTEGER NOT NULL CHECK (processed_grams > 0),
    active INTEGER NOT NULL DEFAULT 0 CHECK (active IN (0, 1)),
    captured_by INTEGER NOT NULL REFERENCES users(id),
    captured_at TEXT NOT NULL,
    notes TEXT
);

-- One share per grade, in basis points of processed input, so the shares are exact integers that add up.
-- The residue -- what went in but came out as no graded bag at all -- is a share like any other and is held
-- against a null grade, because a cascade that only counts what was saleable flatters itself.
CREATE TABLE IF NOT EXISTS grade_yield_baseline_shares (
    baseline_id INTEGER NOT NULL REFERENCES grade_yield_baselines(id) ON DELETE CASCADE,
    quality_grade_id INTEGER REFERENCES quality_grades(id),
    share_basis_points INTEGER NOT NULL CHECK (share_basis_points BETWEEN 0 AND 10000),
    UNIQUE (baseline_id, quality_grade_id)
);

CREATE INDEX IF NOT EXISTS idx_yield_baseline_active ON grade_yield_baselines(active, period_to);

-- Downtime and non-productive time. Two things make this record worth keeping. The first is that a bonus
-- paid on kilograms is only defensible if the time somebody spent waiting for material is separable from the
-- time they spent sorting: the same log that answers "where did the hours go" also answers "why is my bonus
-- lower than hers". The second is that a manual sorting line has no machine signal to read, so the only
-- availability figure this operation can ever have is the one a supervisor writes down.
--
-- Lean practice is specific about the shape of the list, and the shape is the feature: a short, fixed set of
-- loss groups with a handful of reasons each, written in the operators' own words and reviewed every couple
-- of weeks. Plants that put two hundred reasons on a touchscreen get operators who pick the first plausible
-- option during an active stoppage, which is worse than no list at all.
CREATE TABLE IF NOT EXISTS downtime_reasons (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE COLLATE NOCASE,
    loss_group TEXT NOT NULL CHECK (loss_group IN ('SUPPLY', 'EQUIPMENT', 'QUALITY', 'CHANGEOVER',
        'FACILITY', 'STAFFING', 'ADMIN', 'EXTERNAL')),
    name_en TEXT NOT NULL,
    name_hu TEXT NOT NULL,
    -- Whether the stopped time is the crew's own. A break that ran over is theirs; a power cut is not, and
    -- counting it against them is how a bonus scheme loses the room.
    attributable INTEGER NOT NULL DEFAULT 0 CHECK (attributable IN (0, 1)),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_by INTEGER REFERENCES users(id),
    created_at TEXT NOT NULL,
    -- A retired reason keeps its rows: the stoppages logged under it last year happened under that wording.
    retired_at TEXT,
    CHECK ((active = 1 AND retired_at IS NULL) OR (active = 0 AND retired_at IS NOT NULL))
);

-- One stoppage. A null team is the whole line rather than nobody, and a null sorter is everybody on the
-- team: a power cut stops the site, and saying so should not mean naming twenty people.
CREATE TABLE IF NOT EXISTS downtime_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    work_day TEXT NOT NULL,
    team_id INTEGER REFERENCES sorting_teams(id),
    sorter_id INTEGER REFERENCES users(id),
    station_id INTEGER REFERENCES warehouse_locations(id),
    started_at TEXT NOT NULL,
    ended_at TEXT,
    -- Stored, not derived at read time, for the same reason weights are stored in grams: the aggregates are
    -- then exact integer arithmetic. The service computes it from the two timestamps and never anywhere else.
    minutes INTEGER CHECK (minutes > 0),
    -- How many people were standing still. Derived from the team when the stoppage is opened rather than
    -- typed, and stored, because team membership changes and a figure recomputed from next month's roster
    -- would quietly rewrite last month's availability.
    people_affected INTEGER NOT NULL DEFAULT 1 CHECK (people_affected > 0),
    reason_id INTEGER REFERENCES downtime_reasons(id),
    note TEXT,
    opened_by INTEGER NOT NULL REFERENCES users(id),
    opened_at TEXT NOT NULL,
    closed_by INTEGER REFERENCES users(id),
    closed_at TEXT,
    -- Nothing closes without a reason. A stoppage closed with no explanation is the row that turns up as
    -- "unknown" when somebody finally asks where the hours went, and by then nobody remembers.
    CHECK ((ended_at IS NULL AND minutes IS NULL AND closed_at IS NULL AND closed_by IS NULL)
        OR (ended_at IS NOT NULL AND minutes IS NOT NULL AND reason_id IS NOT NULL
            AND closed_at IS NOT NULL AND closed_by IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_downtime_day ON downtime_events(work_day);
CREATE INDEX IF NOT EXISTS idx_downtime_reason ON downtime_events(reason_id);

-- The shift handover. The best-specified feature in the whole research set, and the smallest: a published
-- template is six written fields completed in ten minutes or less, before the outgoing supervisor leaves.
-- Four of those six are not KPI data at all, which is the point — an application that offers a shift summary
-- and calls it a handover has covered one field of six. The other five are structured notes with names
-- against them.
--
-- Two companion rules shape the record as much as the fields do. The board has to be current before the next
-- shift's meeting, so writing it belongs to shift *close* rather than shift open, and the incoming
-- supervisor's first screen is therefore the last shift rather than today so far. And an issue left open for
-- more than forty-eight hours escalates by itself, so that the person who raised it does not have to keep
-- pushing it.
CREATE TABLE IF NOT EXISTS shift_handovers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    work_day TEXT NOT NULL,
    shift_code TEXT NOT NULL CHECK (shift_code IN ('MORNING', 'AFTERNOON', 'NIGHT')),
    outgoing_by INTEGER NOT NULL REFERENCES users(id),
    -- The four written fields. All of them are required: "nothing to report" is an answer somebody has to
    -- give, and a blank is the thing the record exists to prevent.
    equipment_status TEXT NOT NULL,
    staffing_notes TEXT NOT NULL,
    safety_items TEXT NOT NULL,
    -- One sentence on the single thing the incoming supervisor must do before the line starts.
    must_know TEXT NOT NULL,
    -- The measured field, frozen as it stood at close. A handover is a document of what was said at that
    -- moment; recomputing it later, after another day of data entry, would quietly rewrite what was handed
    -- over. The figures cover the shift's own hours, not the whole work day.
    processed_grams INTEGER NOT NULL DEFAULT 0,
    target_grams INTEGER NOT NULL DEFAULT 0,
    output_grams INTEGER NOT NULL DEFAULT 0,
    downtime_minutes INTEGER NOT NULL DEFAULT 0,
    primary_cause_reason_id INTEGER REFERENCES downtime_reasons(id),
    submitted_at TEXT NOT NULL,
    -- Who picked it up, and when. A handover nobody acknowledged is a handover that did not happen.
    acknowledged_by INTEGER REFERENCES users(id),
    acknowledged_at TEXT,
    UNIQUE (work_day, shift_code)
);

-- An open issue with a name and a date against it. Both are required: an issue with no owner is a note, and
-- a note is what a handover sheet is meant to replace.
CREATE TABLE IF NOT EXISTS shift_handover_issues (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    handover_id INTEGER NOT NULL REFERENCES shift_handovers(id) ON DELETE CASCADE,
    summary TEXT NOT NULL,
    owner_id INTEGER NOT NULL REFERENCES users(id),
    due_on TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'RESOLVED')),
    created_at TEXT NOT NULL,
    escalated_at TEXT,
    escalated_to INTEGER REFERENCES users(id),
    resolved_at TEXT,
    resolved_by INTEGER REFERENCES users(id),
    resolution TEXT,
    CHECK ((state = 'OPEN' AND resolved_at IS NULL AND resolved_by IS NULL)
        OR (state = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_handover_day ON shift_handovers(work_day, shift_code);
CREATE INDEX IF NOT EXISTS idx_handover_issue_open ON shift_handover_issues(state, created_at);

-- 29. What shape this database is in, written down inside it.
-- One row per version it has reached. applied_at is null for a version that was already in place before
-- the record started being kept: nobody wrote down when it happened, and a plausible date would be worse
-- than an admitted gap.
CREATE TABLE IF NOT EXISTS schema_versions (
    version     INTEGER PRIMARY KEY,
    note        TEXT    NOT NULL,
    applied_at  TEXT,
    recorded_at TEXT    NOT NULL
);
