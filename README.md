# ReWear WMS (`due-prog-03`)

Desktop Java 17 GUI warehouse, QR labeling, scanning, forklift logistics, internal communications, B2B order management, and multi-currency invoicing system built for **used and sorted second-hand clothing packaged goods** (bales, bags, boxes, pallets) — Dunaújvárosi Egyetem, **Programozás III.** project by **Czudor Attila**.

---

## Key Features (v2.5)

- **Structured QR Product Code Convention:**
  ```text
  YYYY-MM-DD-HH-MM-<WHATFOR>-<TYPE>-<QUALITY>-<PACKAGETYPE>-<WEIGHT>-<HEX2>
  ```
  Example: `2026-11-12-11-23-L-TSH-A-A-25-3F`  
  *(Entered 2026-11-12 at 11:23 — Ladies (`L`) T-Shirts (`TSH`), Grade `A`, Compressed Bale (`A`), `25 kg`, random 2-digit uppercase hex suffix `3F` for collision-free uniqueness).*
- **In-App Code Dictionaries, Assigned Operator Stations & Mandatory Pre-Save Preview:**
  - Create and manage Target Groups, Garment Types, Quality Grades, and Package Types directly in the app (both from **Code Dictionaries** and inline `+` quick-add buttons on **Package Intake**), including per-kg EUR and HUF default prices.
  - Warehouse locations are typed (`OPERATOR_STATION`, `STORAGE`, `DISPATCH_ZONE`). Sorting Operators are assigned to their dedicated workstation (e.g., `ST-OP-01`) and every intake requires confirming a **Pre-Save Product & Label Preview** before anything is committed to SQLite.
- **Dedicated Forklift Logistics (`Putaway` & `Order Staging`) + Verified Manager Handover:**
  - Separate **`Putaway` (`Betárolás`)** workspace to move newly sorted packages from Operator Stations onto Storage shelves, and **`Order Staging` (`Összekészítés`)** workspace where Forklift Drivers scan package QR/barcodes to collect assigned orders to the Dispatch Collection Zone (`DOCK-01`).
  - Managers assign Forklift Driver & Collection Site per order, trigger **Customer Arrived** alerts, and verify 100% of packages via QR scan in the **Order Handover Dialog** before shipping.
- **Live ZXing QR Generator, Print Preview & Bulk Operations:** Real-time QR preview, zoomable vector print preview (`Thermal 100×150 mm`, `Thermal 80×100 mm`, `A6 Card`, `A4 4-up Sheet`), direct system printer integration (`PrinterJob`), 300-DPI OpenPDF label export, QR PNG export, plus multi-row bulk relocation and multi-label batch printing.
- **QR Scanner Station:** Supports physical USB/Bluetooth HID keyboard-wedge scanners, **drag-and-drop / file-picker QR image decoding (PNG/JPG via ZXing)**, and a **Demo Quick-Scan** picker with 1-click **Relocate**, **Verify Stocktake**, **Reprint**, and **Add to Open Order** actions.
- **Role-Based To-Do Center, Operational Notifications & Internal Comms Suite:**
  - Dedicated **To-Do Center (`Teendők`)** combining prioritized, auto-generated **System To-Dos** (tailored per role with 1-click navigation) and private **Personal To-Dos**.
  - Dedicated **Notifications (`Értesítések`)** feed with automated operational triggers (Operator intake → Forklift Putaway; Order allocation/arrival → Forklift Staging; Dock ready → Manager Handover) and live red sidebar badges.
  - **Element-Style Team Messaging (`Üzenetek`)** with group rooms (`#általános`, `#raktár-logisztika`, custom rooms managed by Manager/Admin/SuperAdmin) and 1-on-1 Direct Messages.
  - **Corporate Email Client (`E-mail`)** with collapsible icon folder rail, hot-search multi-select **To / CC** recipient dropdowns with chips, attachments, Super-Admin configurable company domain (`@rewear-wms.hu`) & SMTP/IMAP gateway settings, and automatic internal-only enforcement for `OPERATOR` and `FORKLIFT` roles.
- **B2B Partners (Buyers & Providers), Orders & Native Multi-Currency Invoicing:**
  - Manage buyers (`CUSTOMER`), providers (`SUPPLIER`), or both (`BOTH`) with ISO country codes, preferred currency, and 1-click **Partner Account Statements & 30/60/90-day Aging PDFs**.
  - Separate non-converting currency ledgers (`EUR`, `HUF`, `USD`, or custom currencies), country-aware VAT rules (`HU 27%`, `EU B2B 0%`, `Export 0%`), editable Seller Company Profile with per-currency IBANs, multi-line Outgoing/Incoming invoices, 1-click auditable **Credit Notes** (`CN-YYYY-NNNN`), automatic **`ISSUED → OVERDUE`** sync + localized `mailto:` reminders, portable **Expense Receipt Vault** (`./data/receipts/`), and printable A4 **P&L + Stock Valuation PDF Reports**.
- **Super-Admin Module-Based RBAC (`Roles & Modules` Matrix):**
  - All 16 sidebar menus are managed as toggleable modules (`AppModule`) persisted in SQLite (`role_module_permissions`). `SUPER_ADMIN` retains unrestricted access and can turn modules ON or OFF per role (`ADMIN`, `MANAGER`, `OPERATOR`, `FORKLIFT`) live from **Admin → Roles & Modules**.
- **Brutalist Monochrome Light & Dark Theme & Zero-Refresh Ergonomics:** Pure `#FFFFFF` / `#000000` FlatLaf styling (`arc = 0`, `1px`/`2px` hairline borders, pure-vector SVG icons & logo, monospace typography, locked active tab/nav states), instant lazy-loaded views, automatic background polling (zero manual Refresh buttons), off-EDT `SwingWorker` execution (`AsyncUiRunner`), and global keyboard shortcuts (`Cmd/Ctrl+1..9/0`, `F5`/`Cmd/Ctrl+R`, `Esc`).
- **CSV-Driven Bilingual UI (`EN` / `HU` + Extensible):** All **553** UI keys live in `src/main/resources/i18n/translations.csv`. Add any new language column (`de`, `sk`, etc.) and reload live from **Admin → Database & Translations** without recompiling.
- **Zero-Config 3NF Embedded Database & Diagnostics:** **24-table** normalized SQLite schema (`PRAGMA foreign_keys = ON`, 19 indexes) with automatic initialization & migrations, demo seed data, BCrypt authentication, audit trail CSV export, SQLite `VACUUM INTO` backup & validated restore, and a live **System & Packages** diagnostics tab.

---

## Demo Accounts

| Role | Username | Password | Default Access |
|---|---|---|---|
| **Super Admin** | `superadmin` | `superadmin123` | Unrestricted full access + **Roles & Modules** matrix + **Email Settings** |
| **Administrator** | `admin` | `admin123` | All operational, financial, comms, and admin modules (Users, Audit Log, DB Backup/Restore, CSV i18n, Diagnostics) |
| **Warehouse Manager** | `manager` | `manager123` | Dashboard, To-Do, Intake, Putaway, Order Staging, Scanner, Inventory, Locations, Dictionaries, Partners, Orders & Handover, Invoices & Finance, Comms |
| **Sorting Operator** | `operator` | `operator123` | Dashboard, To-Do, Package Intake (locked to `ST-OP-01` + Pre-Save Preview), QR Scanner, Inventory, Notifications, Messages, Internal Email |
| **Forklift Driver** | `forklift` | `forklift123` | Dashboard, To-Do, Putaway (`Betárolás`), Order Staging (`Összekészítés` with QR Scan-Collect), Inventory, Notifications, Messages, Internal Email |

---

## Requirements

- **Java 17+** (JDK) — or run one of the bundled cross-platform launchers (`run-mac.command`, `run-linux.sh`, `run-windows.bat`) which automatically detect Java 17+ or download a local Temurin 17 JRE if needed.
- **Apache Maven 3.9+** (or open directly as a Maven project in **Apache NetBeans** / IntelliJ IDEA).

---

## Build & Run

```bash
# Run all 50 JUnit 5 unit & integration tests (strict -Xlint:all,-serial, 0 warnings)
mvn test

# Build the self-contained runnable fat JAR
mvn clean package

# Launch the application (auto-creates ./data/rewear-wms.db on first start)
java -jar target/due-prog-03.jar

# Or use the cross-platform one-click launchers:
./run-mac.command      # macOS
./run-linux.sh         # Linux
run-windows.bat        # Windows
```

---

## University Documentation (`docs/`)

- **Projektterv:** [docs/Projektterv.md](docs/Projektterv.md)
- **Adatbázisterv (ER diagram + 24 táblás 3NF normalizálás):** [docs/Adatbazisterv.md](docs/Adatbazisterv.md)
- **Programterv (Use-Case, UML Osztálydiagram, Szekvenciadiagramok, 50 Teszt):** [docs/Programterv.md](docs/Programterv.md)
