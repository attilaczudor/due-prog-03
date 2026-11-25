# ReWear WMS (`due-prog-03`)

Desktop Java 17 GUI warehouse, QR labeling, scanning, forklift logistics, internal communications, B2B order management, and multi-currency invoicing system built for **used and sorted second-hand clothing packaged goods** (bales, bags, boxes, pallets) — Dunaújvárosi Egyetem, **Programozás III.** project by **Czudor Attila**.

---

## Key Features

- **Structured QR Product Code Convention:**
  ```text
  YYYY-MM-DD-HH-MM-<WHATFOR>-<TYPE>-<QUALITY>-<PACKAGETYPE>-<WEIGHT>-<HEX2>
  ```
  Example: `2026-11-12-11-23-L-TSH-A-A-25-3F`  
  *(Entered 2026-11-12 at 11:23 — Ladies (`L`) T-Shirts (`TSH`), Grade `A`, Compressed Bale (`A`), `25 kg`, random 2-digit uppercase hex suffix `3F` for collision-free uniqueness).*
- **In-App Code Dictionaries, Assigned Labelling Stations & Mandatory Pre-Save Preview:**
  - Create and manage Target Groups, Garment Types, Quality Grades, and Package Types directly in the app (both from **Code Dictionaries** and inline `+` quick-add buttons on **Package Intake**), including per-kg EUR and HUF default prices.
  - Warehouse locations are typed (`OPERATOR_STATION`, `STORAGE`, `DISPATCH_ZONE`). Station managers can be assigned to a dedicated labelling station (e.g., `ST-OP-01`) and manual intake shows a **Pre-Save Product & Label Preview** before saving.
- **Dedicated Forklift Logistics (`Putaway` & `Order Staging`) and Verified Vehicle Loading:**
  - Separate **`Putaway` (`Betárolás`)** workspace to move newly sorted packages from labelling stations onto storage shelves, and **`Order Staging` (`Összekészítés`)** workspace where Forklift Drivers scan package QR/barcodes to collect assigned orders to the Dispatch Collection Zone (`DOCK-01`). A **Pick list** button prints or previews a route-sorted list (zone, aisle, shelf) for the selected orders, with collected/total progress.
  - Managers assign a driver and dock and mark the customer as arrived. Warehouse management or the assigned fulfilment driver scans each package when loading.
  - Scheduled orders reserve all requested packages from active storage before collection. A pickup becomes ready when every reserved package reaches its dock. Verified shipment makes the order eligible for invoicing.
- **Sorting Production:** Manage raw lots and sorting teams with flexible sorter and helper rosters. Track requests, forklift deliveries, graded output, packaging, labelling, and daily processed-input targets. The [sorting-to-sale implementation plan](docs/Sorting-to-Sale-Implementation.md) includes the webshop import contract.
- **Payroll draft:** Record effective monthly gross pay, department and duty rates, daily shift allocations, and approved bonuses. The [payroll plan](docs/Payroll-Implementation-Plan.md) explains daily production evidence and rollout decisions.
- **Live ZXing QR Generator, Print Preview & Bulk Operations:** Real-time QR preview, zoomable vector print preview (`Thermal 100×150 mm`, `Thermal 80×100 mm`, `A6 Card`, `A4 4-up Sheet`), direct system printer integration (`PrinterJob`), 300-DPI OpenPDF label export, QR PNG export, plus multi-row bulk relocation and multi-label batch printing.
- **QR Scanner Station:** Supports physical USB/Bluetooth HID keyboard-wedge scanners, **drag-and-drop / file-picker QR image decoding (PNG/JPG via ZXing)**, and a **Demo Quick-Scan** picker with 1-click **Relocate**, **Verify Stocktake**, **Reprint**, and **Add to Open Order** actions.
- **Role-Based To-Do Center, Operational Notifications & Internal Comms Suite:**
  - Dedicated **To-Do Center (`Teendők`)** combining prioritized, auto-generated **System To-Dos** (tailored per role with 1-click navigation) and private **Personal To-Dos**.
  - Dedicated **Notifications (`Értesítések`)** feed with automated operational triggers (Operator intake → Forklift Putaway; Order allocation/arrival → Forklift Staging; Dock ready → Manager Handover) and live red sidebar badges.
  - **Element-Style Team Messaging (`Üzenetek`)** with group rooms (`#általános`, `#raktár-logisztika`, custom rooms managed by Manager/Admin/SuperAdmin) and 1-on-1 Direct Messages.
  - **Corporate Email Client (`E-mail`)** with a collapsible folder rail, searchable **To / CC** fields and attachments. Mail between teammates is delivered locally. Document previews attach their selected PDFs in the built-in composer, where **Open file location** opens the attachment folder. On send or save, each attachment is copied into the portable `data/mail-attachments/YYYY/MM/` vault, so a message keeps its file after the original export is moved or cleaned up. Attachments can be PDF, PNG, JPEG, DOCX, XLSX, PPTX, CSV or TXT files up to 25 MB, are checked against their file signature, and are the only kinds the viewer will open. SMTP/IMAP settings support connectivity checks and simulated external delivery. Operators and forklift drivers use internal mail.
- **B2B Partners (Buyers & Providers), Orders & Native Multi-Currency Invoicing:**
  - Manage buyers (`CUSTOMER`), providers (`SUPPLIER`), or both (`BOTH`) with ISO country codes, preferred currency, and 1-click **Partner Account Statements & 30/60/90-day Aging PDFs**.
  - Separate non-converting currency ledgers (`EUR`, `HUF`, `USD`, or custom currencies), country-aware VAT rules (`HU 27%`, `EU B2B 0%`, `Export 0%`), editable Seller Company Profile with per-currency IBANs, multi-line Outgoing/Incoming invoices, 1-click auditable **Credit Notes** (`CN-YYYY-NNNN`), automatic **`ISSUED → OVERDUE`** sync + localized `mailto:` reminders, portable **Expense Receipt Vault** (`./data/receipts/`), and printable A4 **P&L + Stock Valuation PDF Reports**.
  - Open a row with double-click or Enter to see line items and invoice actions. The list starts at All Invoices. Use the right filter drawer for searchable partners and suppliers, currencies, statuses, directions, and categories. Create a smart folder from the active filters, with the direction icon preselected for incoming or outgoing invoices. In the header’s three-dot menu, the financial PDF report covers the full selected currency ledgers. CSV exports the visible invoice list.
  - **Admin → Invoice Settings** lists every configured currency’s IBAN alongside seller details. Address, city, and postal code have separate fields. Super Admin can update the billing email. New invoices print the full IBAN for their own currency when configured. Double-click a VAT type or currency to edit, activate, deactivate, or request deletion.
  - Outgoing invoices start with a searchable order picker and read-only order lines. Manual sales and expenses can use a saved partner or create a buyer or supplier at save time. Enter invoice lines or the net total, select one configured VAT type, and optionally attach a PDF, PNG, or JPEG.
  - On save, invoice PDFs go to `data/invoices/YYYY/MM/` and uploaded receipts go to `data/receipts/YYYY/MM/`. Both use the invoice issue date, partner name, and gross amount, for example `2026-11-24-Duna-Vintage-Kft-125.5.pdf`. A suffix preserves files with the same name. Existing receipt links remain readable.
  - Create an order invoice from **Payments**. The order document preview shows the delivery note and any existing outgoing invoice.
- **NAV Online Számla reporting:** Outgoing invoices are reported to the Hungarian tax authority as they are issued, against the official [NAV Online Invoice API 3.0 specification](https://github.com/nav-gov-hu/Online-Invoice/blob/master/docs/API%20docs/hu/Online_Szamla_interfesz%20specifikacio_HU_v3.0.pdf): token exchange, `manageInvoice` and a status query, with the transaction id kept as evidence and a per-invoice state machine that says plainly whether an invoice is reported. Reporting starts switched off and pointed at the test environment, and the technical user's password and keys are kept outside the database in a file readable only by the account that runs the application, so they never travel in a backup.
- **Offline integrations:** Bank Integration has demo accounts in HUF, EUR, and USD. Add manual accounts in any configured active currency and record credits and debits. Balances use exact decimal arithmetic, with separate totals for manual and demo accounts.
- **Smart folders:** Save private invoice and mail searches with a name, icon, and reusable criteria. The creator opens directly with a searchable SVG icon gallery that starts with two rows. The gear on each smart folder opens its resizable editor and removal action. Each option group starts with **All**, and selecting it clears the group’s individual choices. Invoice folders support multiple partners and currencies. Mail folders can match sender, recipient, subject, body, read status, attachments, and source mailbox.
- **Super-Admin Module-Based RBAC (`Roles & Modules` Matrix):**
  - All 18 sidebar menus are managed as toggleable modules (`AppModule`) persisted in SQLite (`role_module_permissions`). `SUPER_ADMIN` retains unrestricted access and can turn modules ON or OFF per role (`ADMIN`, `MANAGER`, `OPERATOR`, `SORTER`, `SORTING_HELPER`, `FORKLIFT`) live from **Admin → Roles**.
  - Admin lists Users, Invoice Settings, NAV Integration, Bank Integration, Database, Translations, Log, and System. Super Admin also sees Roles above Invoice Settings and Email Settings below it. Changes to a configured external translation CSV surface in Notifications and the Admin badge.
  - Double-click an account in **Admin → Users** to open its editor and optionally reset its password. Only Super Admin can edit account email addresses.
  - The menu list scrolls when the window is short. The avatar and sidebar controls stay pinned below it in both sidebar modes.
- **Brutalist Monochrome Light & Dark Theme & Zero-Refresh Ergonomics:** FlatLaf uses square corners, hairline borders, vector icons, and monospace typography. Admin & Maintenance, Code Dictionaries, and Payments use collapsible secondary menus styled like the Email folder rail. Table header arrows sort columns in either direction. Views load on demand and refresh automatically, with heavy work off the Swing event thread. Global shortcuts include `Cmd/Ctrl+1..9/0`, `F5`/`Cmd/Ctrl+R`, and `Esc`.
- **CSV-Driven Bilingual UI (`EN` / `HU` + Extensible):** All **811** UI keys live in `src/main/resources/i18n/translations.csv`. Add any new language column (`de`, `sk`, etc.) and reload live from **Admin → Translations** without recompiling.
- **Zero-Config 3NF Embedded Database & Diagnostics:** The SQLite schema declares **42 tables** and **24 indexes**, with foreign keys enabled. The app shows startup progress, prepares storage, runs migrations, asks on first launch whether to start your own company or the demo workspace, and provides BCrypt authentication, audit CSV export, a guarded invoice lifecycle with an audit trail, full backup and restore (database plus invoice, receipt and mail files, checksum-verified with rollback), and live diagnostics.

---

## First launch and demo accounts

A new installation opens a **first-time setup** screen and creates nothing until you choose:

- **Set up my company** starts empty with your own administrator account (password of 8+ characters, not a demo or common password). Optionally add the standard clothing catalogues and a starter shelf layout; both can be edited later.
- **Try the demo workspace** loads the sample warehouse and the demo accounts below. Their passwords are public, so never put real data in a demo workspace.
- **Restore from a backup** brings in an earlier full backup (`.zip`) or database (`.db`) instead.

The accounts below exist only in a demo workspace, and the login screen offers them only there.

| Role | Username | Password | Default Access |
|---|---|---|---|
| **Super Admin** | `superadmin` | `superadmin123` | Unrestricted full access + **Roles** matrix + **Email Settings** |
| **Administrator** | `admin` | `admin123` | Warehouse, Payments, Payroll, communications, and shared Admin settings |
| **Warehouse Manager** | `manager` | `manager123` | Sorting, Intake, Putaway, Order Staging, Scanner, Inventory, Locations, Dictionaries, Partners, Orders, Payments, and communications |
| **Station Manager** | `operator` | `operator123` | Sorting and registration at the assigned station, Package Intake, QR Scanner, Inventory, and internal communications |
| **Sorting Operator** | `sorter01`–`sorter20` | `sorter123` | Request raw stock, report graded bags, close input batches, view daily progress |
| **Sorting Helper** | `helper01`–`helper05` | `helper123` | Pack graded bags, send them to the station, view team progress |
| **Supply Driver** | `forklift` | `forklift123` | Confirm assigned raw deliveries and put away labelled stock |
| **Fulfilment Driver** | `forklift2` | `forklift2123` | Collect assigned orders and verify vehicle loading |

---

## Requirements

- **Java 17+** (JDK) — or run one of the bundled cross-platform launchers (`run-mac.command`, `run-linux.sh`, `run-windows.bat`) which automatically detect Java 17+ or download a local Temurin 17 JRE if needed.
- **Apache Maven** is *not* required — the bundled wrapper (`./mvnw`, `mvnw.cmd`) downloads Maven 3.9.9 on first use. An existing Maven 3.9+ installation works too, as does opening the project directly in **Apache NetBeans** / IntelliJ IDEA.

---

## Build & Run

```bash
# Run the JUnit 5 unit and integration tests
./mvnw test

# Build and verify the self-contained runnable JAR
./mvnw clean verify

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
- **Adatbázisterv (ER diagram és 3NF normalizálás):** [docs/Adatbazisterv.md](docs/Adatbazisterv.md)
- **Programterv (használati esetek, UML- és szekvenciadiagramok, tesztek):** [docs/Programterv.md](docs/Programterv.md)

The [session handover](docs/HANDOVER.md) records the demonstrated workflows and verification. The [task board](docs/TASKS.md) lists remaining work. The [codebase review](docs/CodebaseReview.md) preserves the earlier audit findings and their resolution.
