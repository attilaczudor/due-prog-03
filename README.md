# ReWear WMS (`due-prog-03`)

Desktop Java GUI warehouse, QR labeling, scanning, and B2B order management system built for **used and sorted second-hand clothing packaged goods** (bales, bags, boxes, pallets) — Dunaújvárosi Egyetem, **Programozás III.** project by **Czudor Attila**.

---

## Key Features

- **Structured QR Product Code Convention:**
  ```text
  YYYY-MM-DD-HH-MM-<WHATFOR>-<TYPE>-<QUALITY>-<PACKAGETYPE>-<WEIGHT>-<HEX2>
  ```
  Example: `2026-11-12-11-23-L-TSH-A-A-25-3F`  
  *(Entered 2026-11-12 at 11:23 — Ladies (`L`) T-Shirts (`TSH`), Grade `A`, Compressed Bale (`A`), `25 kg`, random 2-digit uppercase hex suffix `3F` for collision-free uniqueness).*
- **In-App Code Element Creation:** Create and manage Target Groups, Product/Garment Types, Quality Grades, and Package Types directly in the app (both from the **Code Dictionaries** view and via inline `+` quick-add buttons on the **Package Intake** form).
- **Live ZXing QR Generator & Ticket Print Preview:** Real-time QR preview, zoomable vector print preview (`Thermal 100×150 mm`, `Thermal 80×100 mm`, `A6 Card`, `A4 4-up Sheet`), direct system printer integration (`PrinterJob`), 300-DPI OpenPDF label export, and QR PNG export.
- **QR Scanner Station:** Supports physical USB/Bluetooth HID keyboard-wedge scanners, **drag-and-drop / file-picker QR image decoding (PNG/JPG via ZXing)**, and a **Demo Quick-Scan** picker for classroom presentations, with 1-click **Relocate**, **Verify Stocktake**, **Reprint**, and **Add to Open Order** actions.
- **B2B Orders & PDF Delivery Notes:** Create customer orders, scan bales directly into an order or pick from stock, manage atomic status transitions (`DRAFT` → `PACKING` → `SHIPPED` / `CANCELLED`), and export brutalist A4 PDF Packing Lists / Delivery Notes.
- **Brutalist Monochrome Light & Dark Theme:** Pure `#FFFFFF` / `#000000` FlatLaf styling, zero rounded corners (`arc = 0`), `1px`/`2px` hairline borders, inverted active states, and monospace typography throughout, including monochrome **XChart** dashboard analytics.
- **CSV-Driven Bilingual UI (`EN` / `HU` + Extensible):** All UI strings live in `src/main/resources/i18n/translations.csv`. Add any new language column (`de`, `sk`, etc.) and load it live from **Admin → Database & Translations** without recompiling.
- **Zero-Config 3NF Embedded Database:** 12-table normalized SQLite schema (`PRAGMA foreign_keys = ON`) with automatic initialization, realistic demo seed data, BCrypt authentication, RBAC (`ADMIN`, `MANAGER`, `OPERATOR`), audit trail CSV export, and SQLite `VACUUM INTO` backup & validated restore.

---

## Demo Accounts

| Role | Username | Password | Access |
|---|---|---|---|
| **Administrator** | `admin` | `admin123` | Full access (including Users, Audit Log, DB Backup/Restore, CSV i18n Reload) |
| **Warehouse Manager** | `manager` | `manager123` | Dashboard, Intake, Scanner, Inventory, Locations, Dictionaries, Customers, Orders, Exports |
| **Sorting Operator** | `operator` | `operator123` | Dashboard, Intake & QR Label Print, QR Scanner Station, Inventory Browser |

---

## Requirements

- **Java 17+** (JDK)
- **Apache Maven 3.9+** (or open directly as a Maven project in **Apache NetBeans** / IntelliJ IDEA)

---

## Build & Run

```bash
# Run all 24 JUnit 5 unit & integration tests
mvn test

# Build the self-contained runnable JAR
mvn clean package

# Launch the application (auto-creates data/rewear-wms.db on first start)
java -jar target/due-prog-03.jar
```

---

## University Documentation (`docs/`)

- **Projektterv:** [docs/Projektterv.md](docs/Projektterv.md)
- **Adatbázisterv (ER diagram + 1NF–3NF normalizálás):** [docs/Adatbazisterv.md](docs/Adatbazisterv.md)
- **Programterv (Use-Case, UML Osztálydiagram, Szekvenciadiagramok):** [docs/Programterv.md](docs/Programterv.md)
