# Development Phases & Tasks — ReWear WMS (`due-prog-03`)

This roadmap breaks down [PRD.md](./PRD.md) into incremental, verifiable phases. Each phase produces a working, testable milestone and corresponds to clean logical commits.

---

## Phase 0: Project Setup & Documentation Skeleton
**Goal:** Establish repository hygiene, Maven project structure (NetBeans-compatible), dependencies, and baseline configuration.

- [x] Create `.gitignore`, `PRD.md`, and `PHASES_AND_TASKS.md`
- [x] Initialize Git repository and connect to public GitHub repo `due-prog-03`
- [ ] Create Maven `pom.xml` (Java 17, FlatLaf, `sqlite-jdbc`, ZXing `core`/`javase`, OpenPDF, XChart, jBCrypt, SLF4J/Logback, JUnit 5, `maven-shade-plugin`)
- [ ] Set up package directory layout under `src/main/java/hu/due/rewear/...` and `src/main/resources/...`
- [ ] Configure Logback (`logback.xml`) writing to console + rolling log file
- [ ] Verify `mvn test` and `mvn package` build a runnable JAR cleanly

---

## Phase 1: CSV Internationalization (i18n) & Product Code Engine
**Goal:** Build the two signature domain foundations early and cover them with unit tests.

- [ ] Design `src/main/resources/i18n/translations.csv` with initial `key,en,hu` columns covering all core UI screens
- [ ] Implement RFC-4180 compliant `CsvTranslationLoader` (UTF-8, quoted values, dynamic language column discovery)
- [ ] Implement `I18nManager` (active language, `t(key, args...)`, English fallback, listener registration, external CSV override & reload)
- [ ] Implement `ProductCodeService`:
  - [ ] Generate code: `YYYY-MM-DD-HH-MM-<WHATFOR>-<TYPE>-<QUALITY>-<PACKAGETYPE>-<WEIGHT>-<HEX2>`
  - [ ] Random uppercase 2-digit hex generator (`00`–`FF`) using `SecureRandom`
  - [ ] Regex parser & validator extracting all 11 components into a `ParsedProductCode` object
- [ ] Write JUnit 5 tests for `CsvTranslationLoader`, `I18nManager` (including dynamically adding a 3rd language column), and `ProductCodeService`

---

## Phase 2: Database Layer (SQLite 3NF Schema, DAOs & Demo Seeder)
**Goal:** Implement the complete normalized database, JDBC helpers, DAOs, and realistic demo seed data.

- [ ] Implement `DatabaseManager` (SQLite connection management, `PRAGMA foreign_keys = ON`, transaction helper)
- [ ] Write SQL schema DDL (`src/main/resources/db/schema.sql`) for all 12 normalized tables + indexes
- [ ] Implement `SchemaInitializer` and `DemoDataSeeder`:
  - [ ] Seed roles (`ADMIN`, `MANAGER`, `OPERATOR`) and demo users with BCrypt hashes
  - [ ] Seed dictionaries (`target_groups`, `garment_types`, `quality_grades`, `package_types`) with English & Hungarian names
  - [ ] Seed warehouse locations (`A-01-01` .. `C-02-04`)
  - [ ] Seed sample customers, ~25 realistic clothing bales/bags, sample orders, and movement history
- [ ] Implement domain models & enums (`PackageStatus`, `OrderStatus`, `MovementType`, etc.)
- [ ] Implement JDBC DAOs:
  - [ ] `RoleDao`, `UserDao`
  - [ ] `DictionaryDao` (for target groups, garment types, quality grades, package types)
  - [ ] `WarehouseLocationDao` (including live occupancy weight query)
  - [ ] `InventoryPackageDao` (CRUD, lookup by `qr_code`, multi-criteria filter query)
  - [ ] `StockMovementDao`
  - [ ] `CustomerDao`, `OrderDao`, `OrderItemDao`
- [ ] Write JUnit 5 integration tests against an in-memory/temp SQLite database verifying FK constraints, uniqueness, and transactions

---

## Phase 3: Authentication, RBAC & Modern Swing Application Shell
**Goal:** Launchable bilingual desktop application with login, role-based navigation, and Light/Dark themes.

- [ ] Implement `AuthService` & `SessionContext` (BCrypt verify, password change, role permission checks)
- [ ] Build `LoginDialog` / `LoginFrame` (username/password, demo credentials hint, language switcher `EN`/`HU`, theme toggle)
- [ ] Build `MainFrame`:
  - [ ] Left sidebar navigation filtered by current user role
  - [ ] Top bar with user info, role badge, live language dropdown (populated dynamically from `translations.csv`), Light/Dark FlatLaf toggle, Quick Scan button, Logout
  - [ ] CardLayout content host with clean refresh hook when switching language or active view
- [ ] Persist UI preferences (selected language, Light/Dark theme) in local config file

---

## Phase 4: Package Intake, Live QR Generation & Label Print Preview
**Goal:** Complete the core bale/package creation workflow with live QR preview, thermal/sheet print preview, and real printing/PDF export.

- [ ] Implement `QrCodeService` (ZXing QR image generation to `BufferedImage`, PNG export, QR decoding from `BufferedImage`/file)
- [ ] Build **Package Intake Panel**:
  - [ ] Dropdowns backed by DB lookup tables (localized in current language)
  - [ ] Weight input, warehouse location selector with remaining capacity indicator, notes, batch quantity
  - [ ] Live code preview + live QR code preview updating on every field change
  - [ ] Collision-safe hex suffix generation against `InventoryPackageDao`
  - [ ] Transactional save (`inventory_packages` + `stock_movements` `INTAKE`)
- [ ] Build **Ticket / Label Print Preview Dialog**:
  - [ ] High-DPI printable label renderer (`LabelRenderer` implementing `java.awt.print.Printable`)
  - [ ] Presets: `Thermal 100x150 mm`, `Thermal 80x100 mm`, `A6 Card`, `A4 Sheet (4-up)`
  - [ ] Interactive Zoom (`50%`–`200%`), Copies spinner, Printer selector (`PrintServiceLookup`)
  - [ ] **"Print"** action via `PrinterJob`
  - [ ] **"Export PDF"** action via OpenPDF (matching selected layout)
  - [ ] **"Save QR PNG"** action (for quick testing & presentation demos)

---

## Phase 5: QR Scanner Station (Hardware HID + Image Scan)
**Goal:** Fast, reliable scanning workflow that works both with physical USB/Bluetooth scanners and image files during demos.

- [ ] Build **QR Scanner Station Panel**:
  - [ ] Auto-focused HID scan input field (handles rapid scanner keystrokes + Enter, clears cleanly for next scan)
  - [ ] **"Scan from Image (PNG/JPG)"** button + drag-and-drop dropzone using ZXing decoder
  - [ ] **"Demo Quick-Scan"** picker (select an existing bale to simulate a live hardware scan during presentation if needed)
  - [ ] Clear visual feedback banner (Matched / Valid-format-not-in-DB / Invalid QR)
- [ ] Display decoded product card (human-readable breakdown of every QR code segment + DB status + shelf location + movement timeline)
- [ ] Wire post-scan instant actions:
  - [ ] **Relocate** (choose new shelf → updates package + logs `RELOCATE`)
  - [ ] **Verify Stocktake** (logs `SCAN_VERIFY`)
  - [ ] **Reprint Label** (opens Print Preview)
  - [ ] **Add to Open Order** (links package to an active `DRAFT`/`PACKING` order)

---

## Phase 6: Inventory Browser, Warehouse Locations & Code Dictionaries
**Goal:** Full visibility and administrative control over stock, storage racks, and QR code segments.

- [ ] Build **Inventory Panel**:
  - [ ] Filter bar (QR code text, Status, Target Group, Garment Type, Quality Grade, Package Type, Location)
  - [ ] Sortable table with status color badges & summary footer (filtered count + total kg)
  - [ ] Context/toolbar actions: View Details & History, Relocate, Reprint Single Label, **Batch Print Preview (A4 4-up)**, Discard/Adjust, **Export Filtered CSV**
- [ ] Build **Warehouse Locations Panel** (Admin/Manager):
  - [ ] CRUD for storage locations + visual capacity bar (`used_kg / max_capacity_kg`)
  - [ ] Click a location to view all packages currently stored there
- [ ] Build **Code Dictionaries Panel** (Admin/Manager):
  - [ ] Tabbed CRUD for `target_groups`, `garment_types`, `quality_grades`, `package_types`
  - [ ] Guardrails preventing deletion of codes already used by existing packages (soft-deactivate instead)

---

## Phase 7: Customers, Orders & PDF Delivery Notes
**Goal:** Complete outbound dispatch workflow from B2B order creation to printable PDF packing lists.

- [ ] Build **Customers Panel** (Admin/Manager): CRUD for B2B buyers with order count/history view
- [ ] Build **Orders & Dispatch Panel**:
  - [ ] Create new order for a customer
  - [ ] Add packages via **Live QR Scan input** inside the order editor OR **Available Stock Picker**
  - [ ] Auto-calculate line price (`weight_kg * price_per_kg`), order total weight, and total price
  - [ ] Lifecycle transitions (`DRAFT` → `PACKING` → `SHIPPED` / `CANCELLED`) in atomic DB transactions updating package statuses & `stock_movements`
- [ ] Implement **PDF Delivery Note / Packing List** generator (OpenPDF):
  - [ ] Company/Order metadata, Customer details, itemized table of scanned QR codes + specs + weight + price, summary totals, signature line

---

## Phase 8: Dashboard Analytics, Admin Tools & File Operations
**Goal:** Visual management overview plus user management, audit log, CSV i18n reload, and database backup/restore.

- [ ] Build **Dashboard Panel**:
  - [ ] 4 KPI cards (In-Stock Bales, In-Stock kg, Today's Intake, Active Orders)
  - [ ] XChart charts: Stock kg by Quality Grade (Pie), Stock kg by Garment Type (Bar), Intake vs. Shipped kg (Bar)
  - [ ] Recent Stock Movements table
- [ ] Build **Admin & Maintenance Panel** (`ADMIN` only):
  - [ ] User management (Create user, assign role, reset password, enable/disable)
  - [ ] Full Audit Log viewer (`stock_movements`) with CSV export
  - [ ] **Database Backup** (timestamped `.db` file copy) & **Restore** (with validation and confirmation)
  - [ ] **Translations Manager**: Export `translations.csv`, load custom external CSV, and live-reload UI languages

---

## Phase 9: Verification, Packaging & University Documentation
**Goal:** Harden the application, build the final standalone `.jar`, and write all required university deliverables.

- [ ] End-to-end manual QA checklist (all roles, both `EN` and `HU`, Light & Dark themes, printing, PDF export, CSV export, DB backup/restore)
- [ ] Run full JUnit 5 test suite and verify zero warnings/failures
- [ ] Build standalone fat `.jar` (`mvn clean package`) and test launching cleanly in an isolated folder (`java -jar due-prog-03.jar`)
- [ ] Write Hungarian university documentation in `docs/`:
  - [ ] `docs/Projektterv.md` (project description, participants, project lead, schedule, tech stack)
  - [ ] `docs/Adatbazisterv.md` (ER diagram, table specs, FK relationships, 1NF–3NF normalization explanation)
  - [ ] `docs/Programterv.md` (Use-Case diagram, UML Class diagram, architecture & key sequence flows)
- [ ] Write `README.md` with build/run instructions, demo credentials, and QR code format reference

---

## Phase 10: 10-Minute Presentation & Submission Bundle
**Goal:** Prepare a smooth, memorable 10-minute presentation and the exact university submission package.

- [ ] Write `docs/Presentation_Outline_10min.md` (slide breakdown, talking points, timed 5-minute live demo script, backup Q&A)
- [ ] Prepare sample exported QR label PNGs & PDFs ready for the live demo
- [ ] Assemble final submission `.zip` containing: Maven/NetBeans project source, `docs/` (`Projektterv`, `Adatbázisterv`, `Programterv`), and runnable `due-prog-03.jar`
