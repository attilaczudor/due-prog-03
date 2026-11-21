# Programterv — ReWear WMS (`due-prog-03`)

Ez a dokumentum a **ReWear WMS** szoftverarchitektúráját, használati eseteit (Use-Case), UML osztálymodelljét és főbb futásidejű folyamatait (szekvenciadiagramok) mutatja be.

---

## 1. Rétegzett architektúra (Layered MVC)

Az alkalmazás szigorúan elkülönített rétegekre épül a `hu.due.rewear` gyökércsomag alatt:

```mermaid
flowchart TD
    UI["UI Réteg (hu.due.rewear.ui)<br/>LoginFrame, MainFrame, NavigableView implementációk,<br/>PrintPreviewDialog, QuickAddDictionaryDialog, BrutalistStyle"]
    SVC["Üzleti Logika Réteg (hu.due.rewear.service)<br/>AuthService, SessionContext, ProductCodeService, QrCodeService,<br/>InventoryService, OrderService, ScannerService,<br/>TicketLabelPainter, LabelPdfExporter, DeliveryNotePdfExporter,<br/>CsvExportService, BackupService"]
    I18N["Nyelvi & Téma Réteg (i18n / config)<br/>I18nManager, CsvTranslationLoader, ThemeManager, AppPreferences, AppContext"]
    DAO["Adatelérési Réteg (hu.due.rewear.dao)<br/>RoleDao, UserDao, DictionaryDao, WarehouseLocationDao,<br/>InventoryPackageDao, StockMovementDao, CustomerDao, OrderDao, OrderItemDao"]
    MODEL["Domain Modellek (hu.due.rewear.model)<br/>Java 17 Record POJO-k és Enumok"]
    DB[("Beágyazott SQLite 3 Adatbázis<br/>data/rewear-wms.db")]

    UI --> SVC
    UI --> I18N
    SVC --> DAO
    SVC --> MODEL
    DAO --> MODEL
    DAO --> DB
    I18N -.->|"translations.csv / app.properties"| UI
```

### Alkalmazott tervezési minták
- **DAO / Repository minta:** Minden SQL lekérdezés paraméterezett `PreparedStatement`-ekkel a `dao` csomagban izolált (SQL-injekció elleni védelem, cserélhető perzisztencia).
- **Dependency Container (`AppContext`):** Központi konténer példányosítja és köti össze az adatbázist, DAO-kat, szolgáltatásokat és i18n kezelőt.
- **Observer / Listener minta:** `LanguageChangeListener` és `ThemeManager.ThemeChangeListener` biztosítja az azonnali, újraindítás nélküli nyelv- és téma-váltást (EN/HU/egyedi CSV nyelv, Brutalist Világos/Sötét).
- **Strategy / Preset minta:** A `LabelPreset` enum (`THERMAL_100X150`, `THERMAL_80X100`, `A6_CARD`, `A4_4UP`) egységesen vezérli a `TicketPrintable` (Swing előnézet + `PrinterJob`) és a `LabelPdfExporter` (OpenPDF) elrendezését.

---

## 2. Use-Case diagram és használati esetek

```mermaid
flowchart LR
    OP(("Válogató Operátor<br/>(OPERATOR)"))
    MGR(("Raktárvezető<br/>(MANAGER)"))
    ADM(("Adminisztrátor<br/>(ADMIN)"))

    subgraph WMS["ReWear WMS Rendszer"]
        UC1["UC-01 Bejelentkezés, Nyelv (EN/HU) & Téma váltás"]
        UC2["UC-02 Dashboard & XChart Készletelemzés"]
        UC3["UC-03 Csomagfelvétel, Kódépítés & Élő QR Generálás"]
        UC4["UC-04 Címke Nyomtatási Előnézet, Nyomtatás, PDF/PNG"]
        UC5["UC-05 QR Szkenner (HID / Képfájl / Demó): Ellenőrzés, Áthelyezés, Leltár"]
        UC6["UC-06 Készlet Böngészés, Szűrés & A4 4-es Csoportos Nyomtatás"]
        UC7["UC-07 Kódtörzsek & Raktárhelyek Kezelése (+ Gyors-hozzáadás)"]
        UC8["UC-08 B2B Ügyfelek & Rendelések (QR Kiadás + PDF Szállítólevél)"]
        UC9["UC-09 Készlet & Audit Napló CSV Exportálás"]
        UC10["UC-10 Felhasználókezelés, SQLite Backup/Restore & Külső i18n CSV"]
    end

    OP --> UC1
    OP --> UC2
    OP --> UC3
    OP --> UC4
    OP --> UC5
    OP --> UC6

    MGR -.->|"örökli jogosultságait"| OP
    MGR --> UC7
    MGR --> UC8
    MGR --> UC9

    ADM -.->|"örökli jogosultságait"| MGR
    ADM --> UC10
```

---

## 3. UML Osztálydiagram (Kiemelt osztályok)

```mermaid
classDiagram
    class AppContext {
        -DatabaseManager databaseManager
        -I18nManager i18n
        -SessionContext session
        -ProductCodeService productCodeService
        -QrCodeService qrCodeService
        -InventoryService inventoryService
        -OrderService orderService
        -ScannerService scannerService
        -BackupService backupService
    }

    class I18nManager {
        -TranslationBundle bundle
        -String activeLanguage
        +getAvailableLanguages() List~String~
        +setLanguage(lang)
        +t(key, args) String
        +loadFromFile(Path)
        +exportToFile(Path)
    }

    class ProductCodeService {
        +generateHexSuffix() String
        +normalizeCodeSegment(raw) String
        +buildCode(ts, target, type, quality, pkg, weight, hex) String
        +generateUniqueCode(..., existsCheck) String
        +parse(rawCode) Optional~ParsedProductCode~
    }

    class QrCodeService {
        +generateQrImage(content, sizePx) BufferedImage
        +saveQrPng(content, sizePx, Path)
        +decodeQrFromImage(BufferedImage) Optional~String~
        +decodeQrFromFile(Path) Optional~String~
    }

    class InventoryService {
        +createPackages(...) List~InventoryPackage~
        +relocatePackage(pkgId, locId, note, userId) InventoryPackage
        +verifyStocktake(pkgId, note, userId)
        +discardPackage(pkgId, note, userId) InventoryPackage
    }

    class OrderService {
        +createOrder(customerId, notes, userId) Order
        +addPackageToOrder(orderId, pkgId, price, userId) Order
        +removeItemFromOrder(orderId, itemId, userId) Order
        +startPacking(orderId) Order
        +shipOrder(orderId, userId) Order
        +cancelOrder(orderId, userId) Order
    }

    class ScannerService {
        +scanCode(rawInput) ScanResult
        +scanImageFile(Path) ScanResult
    }

    class TicketPrintable {
        -List~InventoryPackage~ packages
        -LabelPreset preset
        -TicketLabelPainter painter
        +getPageCount() int
        +paintPage(Graphics2D, page, w, h)
        +print(Graphics, PageFormat, page) int
    }

    class NavigableView {
        <<interface>>
        +getViewKey() String
        +getTitleI18nKey() String
        +getComponent() JComponent
        +refreshData()
        +applyTranslations()
        +applyTheme()
    }

    class MainFrame {
        -Map~String, NavigableView~ views
        +navigateTo(viewKey)
        +onLanguageChanged(lang)
        +onThemeChanged(dark)
    }

    AppContext --> I18nManager
    AppContext --> ProductCodeService
    AppContext --> QrCodeService
    AppContext --> InventoryService
    AppContext --> OrderService
    AppContext --> ScannerService
    ScannerService --> ProductCodeService
    ScannerService --> QrCodeService
    InventoryService --> ProductCodeService
    MainFrame --> AppContext
    MainFrame o-- NavigableView
    TicketPrintable --> QrCodeService
```

---

## 4. Fő folyamatok szekvenciadiagramjai

### 4.1 Csomagfelvétel, élő QR-kód generálás és címkenyomtatás
```mermaid
sequenceDiagram
    actor Op as Operátor
    participant UI as PackageIntakeView
    participant Code as ProductCodeService
    participant Inv as InventoryService
    participant DB as SQLite (DAO)
    participant Prev as PrintPreviewDialog
    participant Prn as PrinterJob / OpenPDF

    Op->>UI: Kódelemek + súly (25 kg) + raktárhely kiválasztása
    UI->>Code: buildCode(now, L, TSH, A, A, 25, hex)
    Code-->>UI: "2026-11-12-11-23-L-TSH-A-A-25-3F"
    UI-->>Op: Élő ZXing QR-kód előnézet megjelenítése
    Op->>UI: "Mentés és nyomtatási előnézet"
    UI->>Inv: createPackages(..., quantity, hex, userId)
    Inv->>DB: BEGIN TRANSACTION
    Inv->>DB: INSERT inventory_packages + INSERT stock_movements (INTAKE)
    Inv->>DB: COMMIT
    Inv-->>UI: List<InventoryPackage>
    UI->>Prev: showPreview(packages, preset)
    Op->>Prev: Formátum (100x150 / A4 4-es), Zoom, Nyomtatás vagy PDF
    Prev->>Prn: PrinterJob.print() / LabelPdfExporter.exportToPdf()
```

### 4.2 QR szkennelés és rendeléshez rendelés + PDF szállítólevél
```mermaid
sequenceDiagram
    actor Mgr as Raktárvezető
    participant ScanUI as QrScannerView
    participant ScanSvc as ScannerService
    participant QR as QrCodeService
    participant OrdSvc as OrderService
    participant DB as SQLite (DAO)
    participant PDF as DeliveryNotePdfExporter

    Mgr->>ScanUI: USB QR szkenner vagy QR PNG képfájl beolvasása
    ScanUI->>ScanSvc: scanCode(text) / scanImageFile(path)
    opt Képfájl esetén
        ScanSvc->>QR: decodeQrFromFile(path)
        QR-->>ScanSvc: dekódolt QR kód
    end
    ScanSvc->>DB: findByQrCode() + findByPackageId(history)
    ScanSvc-->>ScanUI: ScanResult (FOUND_IN_DB + történet)
    Mgr->>ScanUI: "Hozzáadás nyitott rendeléshez"
    ScanUI->>OrdSvc: addPackageToOrder(orderId, pkgId, price, userId)
    OrdSvc->>DB: ATOMI TRANZAKCIÓ: order_items + status=RESERVED + RESERVE napló + totals
    Mgr->>PDF: PDF Szállítólevél exportálása (OrdersView)
    PDF-->>Mgr: A4 PDF szállítólevél (rendelés QR + tétellista + aláírási blokk)
```

---

## 5. Tesztelési terv és eredmények (JUnit 5)

A rendszer üzleti logikáját, fájlkezelését, adatbázis-integritását és PDF/QR generálását **10 tesztosztály, összesen 24 automatizált JUnit 5 teszt** ellenőrzi:

| Tesztosztály | Ellenőrzött funkcionalitás |
|---|---|
| `I18nManagerTest` (4 teszt) | EN/HU fordítások, paraméteres formázás, 3. nyelv (`de`) dinamikus felismerése CSV-ből, veszteségmentes CSV export/újratöltés, fallback kezelés |
| `ProductCodeServiceTest` (4 teszt) | `2026-11-12-11-23-L-TSH-A-A-25-3F` konvenció, tizedes súlyok, egyedi kódszegmensek normalizálása, 40 ütközésmentes véletlen hex végződés, hibás kódok elutasítása |
| `DatabaseLayerIntegrationTest` (4 teszt) | 12 táblás séma + idempotens seed, BCrypt ellenőrzés, egyedi kódtörzsek létrehozása, `UNIQUE` és `FOREIGN KEY` megszorítások kikényszerítése, telítettség és statisztikák |
| `AuthServiceTest` (3 teszt) | `ADMIN` / `MANAGER` / `OPERATOR` RBAC mátrix, hibás jelszó és inaktív fiók elutasítása, jelszócsere, `AppPreferences` perzisztencia |
| `QrCodeServiceTest` (1 teszt) | ZXing QR generálás → PNG fájl mentés → dekódolás pontos round-trip ellenőrzése |
| `InventoryServiceAndLabelPdfTest` (2 teszt) | Atomi kötegelt csomagfelvétel + áthelyezés + leltári ellenőrzés + magyar nyelvű Thermal 100×150 és A4 4-es PDF címke generálás |
| `ScannerAndOrderServiceTest` (2 teszt) | Szöveges & képfájlos QR szkennelés összes állapota + teljes rendelési életciklus (`DRAFT` → `PACKING` → `SHIPPED` és `CANCELLED` készlet-felszabadítás) |
| `CsvExportAndDictionaryGuardTest` (2 teszt) | UTF-8 BOM magyar készlet és audit napló CSV export, hivatkozott kódtörzs törlésvédelme |
| `DeliveryNotePdfExporterTest` (1 teszt) | Angol és magyar A4 PDF szállítólevél generálása rendelés QR-kóddal |
| `BackupServiceTest` (1 teszt) | SQLite `VACUUM INTO` biztonsági mentés, visszaállítás, előzetes `.db.bak` mentés, érvénytelen fájl elutasítása |
