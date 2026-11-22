# ReWear WMS (`due-prog-03`)

Desktop Java 17 GUI warehouse, QR labeling, scanning, B2B order management, and multi-currency invoicing system built for **used and sorted second-hand clothing packaged goods** (bales, bags, boxes, pallets) — Dunaújvárosi Egyetem, **Programozás III.** project by **Czudor Attila**.

---

## Key Features

- **Structured QR Product Code Convention:**
  ```text
  YYYY-MM-DD-HH-MM-<WHATFOR>-<TYPE>-<QUALITY>-<PACKAGETYPE>-<WEIGHT>-<HEX2>
  ```
  Example: `2026-11-12-11-23-L-TSH-A-A-25-3F`  
  *(Entered 2026-11-12 at 11:23 — Ladies (`L`) T-Shirts (`TSH`), Grade `A`, Compressed Bale (`A`), `25 kg`, random 2-digit uppercase hex suffix `3F` for collision-free uniqueness).*
- **In-App Code Dictionaries & Dual EUR/HUF Pricing:** Create and manage Target Groups, Garment Types, Quality Grades, and Package Types directly in the app (both from **Code Dictionaries** and inline `+` quick-add buttons on **Package Intake**), including per-kg EUR and HUF default prices.
- **Live ZXing QR Generator, Print Preview & Bulk Operations:** Real-time QR preview, zoomable vector print preview (`Thermal 100×150 mm`, `Thermal 80×100 mm`, `A6 Card`, `A4 4-up Sheet`), direct system printer integration (`PrinterJob`), 300-DPI OpenPDF label export, QR PNG export, plus multi-row bulk relocation and multi-label batch printing.
- **QR Scanner Station:** Supports physical USB/Bluetooth HID keyboard-wedge scanners, **drag-and-drop / file-picker QR image decoding (PNG/JPG via ZXing)**, and a **Demo Quick-Scan** picker with 1-click **Relocate**, **Verify Stocktake**, **Reprint**, and **Add to Open Order** actions.
- **B2B Partners (Customers & Suppliers), Orders & Negotiated Pricing:** Manage buyers (`CUSTOMER`), providers (`SUPPLIER`), or both (`BOTH`) with ISO country codes, preferred currency, and 1-click **Partner Account Statements & 30/60/90-day Aging PDFs**. Create orders, negotiate per-item unit prices, manage atomic status transitions (`DRAFT` → `PACKING` → `SHIPPED` / `CANCELLED`), and preview/export A4 PDF Packing Lists & Delivery Notes.
- **Native Multi-Currency Invoicing & Finance Suite:**
  - Separate non-converting currency ledgers (`EUR`, `HUF`, `USD`, or custom currencies) and configurable VAT/tax types (`HU 27%`, `EU B2B 0%`, `Export 0%`, etc.) mapped by country scope (`HU` / `EU` / `NON_EU` / `ANY`) in **Finance → Invoice Settings**.
  - Editable Seller Company Profile with per-currency bank IBANs dynamically rendered on A4 PDF invoices.
  - Multi-line Outgoing & Incoming invoices, order-linked invoicing, 1-click auditable **Credit Notes** (`CN-YYYY-NNNN`), automatic **past-due `ISSUED → OVERDUE`** status sync with 1-click localized `mailto:` payment reminders, portable **Expense Receipt Vault** (`./data/receipts/`), and printable A4 **P&L + Stock Valuation PDF Reports**.
- **Brutalist Monochrome Light & Dark Theme & Ergonomics:** Pure `#FFFFFF` / `#000000` FlatLaf styling (`arc = 0`, `1px`/`2px` hairline borders, pure-vector SVG icons & logo, monospace typography), live **XChart** analytics & dual-currency Warehouse Stock Valuation KPI, lazy-loaded views, off-EDT `SwingWorker` execution (`AsyncUiRunner`), and global keyboard shortcuts (`Cmd/Ctrl+1..9/0`, `F5`/`Cmd/Ctrl+R`, `Esc`).
- **CSV-Driven Bilingual UI (`EN` / `HU` + Extensible):** All 400 UI keys live in `src/main/resources/i18n/translations.csv`. Add any new language column (`de`, `sk`, etc.) and reload live from **Admin → Database & Translations** without recompiling.
- **Zero-Config 3NF Embedded Database & Diagnostics:** 17-table normalized SQLite schema (`PRAGMA foreign_keys = ON`) with automatic initialization, demo seed data, BCrypt authentication, RBAC (`ADMIN`, `MANAGER`, `OPERATOR`), audit trail CSV export, SQLite `VACUUM INTO` backup & validated restore, and a live **System & Packages** diagnostics tab.

---

## Demo Accounts

| Role | Username | Password | Access |
|---|---|---|---|
| **Administrator** | `admin` | `admin123` | Full access (Users, Audit Log, DB Backup/Restore, CSV i18n Reload, System Diagnostics) |
| **Warehouse Manager** | `manager` | `manager123` | Dashboard, Intake, Scanner, Inventory, Locations, Dictionaries, Partners, Orders, Invoices & Finance, Exports |
| **Sorting Operator** | `operator` | `operator123` | Dashboard, Intake & QR Label Print, QR Scanner Station, Inventory Browser |

---

## Requirements

- **Java 17+** (JDK) — or run one of the bundled cross-platform launchers (`run-mac.command`, `run-linux.sh`, `run-windows.bat`) which automatically detect Java 17+ or download a local Temurin 17 JRE if needed.
- **Apache Maven 3.9+** (or open directly as a Maven project in **Apache NetBeans** / IntelliJ IDEA).

---

## Build & Run

```bash
# Run all 42 JUnit 5 unit & integration tests (strict -Xlint:all,-serial)
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
- **Adatbázisterv (ER diagram + 17 táblás 3NF normalizálás):** [docs/Adatbazisterv.md](docs/Adatbazisterv.md)
- **Programterv (Use-Case, UML Osztálydiagram, Szekvenciadiagramok, Tesztek):** [docs/Programterv.md](docs/Programterv.md)
