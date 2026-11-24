# Programterv — ReWear WMS (`due-prog-03`)

Ez a dokumentum a **ReWear WMS (v2.5)** szoftverarchitektúráját, használati eseteit (Use-Case), UML osztálymodelljét, főbb futásidejű folyamatait (szekvenciadiagramok) és automatizált tesztelési eredményeit mutatja be.

---

## 1. Rétegzett architektúra (Layered MVC)

Az alkalmazás szigorúan elkülönített rétegekre épül a `hu.due.rewear` gyökércsomag alatt (**136 fő Java forrásfájl**, **20 JUnit 5 tesztosztály**, **50 teszt**, 0 `-Xlint:all,-serial` figyelmeztetés):

```mermaid
flowchart TD
    UI["UI Réteg (hu.due.rewear.ui)<br/>LoginFrame, MainFrame (modul-alapú oldalsáv, élő jelvények, auto-refresh),<br/>16 NavigableView nézet (TransportView PUTAWAY/STAGING, TodoView,<br/>NotificationsView, MessagesView, EmailView + RecipientMultiSelectField,<br/>AdminModulesPanel, AdminEmailSettingsPanel, OrderHandoverDialog,<br/>PrintPreviewDialog, DocumentPreviewDialog, AsyncUiRunner, BrutalistTabbedPaneUI)"]
    SVC["Üzleti Logika & PDF Réteg (hu.due.rewear.service)<br/>AuthService, SessionContext, RoleModuleService, ProductCodeService,<br/>QrCodeService, InventoryService, TransportService, OrderService,<br/>ScannerService, InvoiceService, ReceiptVaultService, PartnerStatementService,<br/>EmailService, ChatService, NotificationService, TodoService,<br/>PDF/CSV exportálók, BackupService"]
    I18N["Nyelvi & Téma Réteg (i18n / config)<br/>I18nManager (553 EN/HU kulcs), CsvTranslationLoader,<br/>ThemeManager, AppPreferences, AppContext"]
    DAO["Adatelérési Réteg (hu.due.rewear.dao)<br/>17 JDBC DAO: RoleDao, UserDao, RoleModulePermissionDao, SettingsDao,<br/>FinanceConfigDao, DictionaryDao, WarehouseLocationDao, InventoryPackageDao,<br/>StockMovementDao, CustomerDao, OrderDao, OrderItemDao, InvoiceDao,<br/>EmailDao, ChatDao, NotificationDao, TodoDao"]
    MODEL["Domain Modellek (hu.due.rewear.model)<br/>Java 17 Record POJO-k és Enumok (AppModule, RoleCode, LocationType,<br/>InventoryPackage, Order, Invoice, EmailMessage, ChatRoom, AppNotification, ...)"]
    DB[("Beágyazott SQLite 3 (24 tábla, 19 index)<br/>data/rewear-wms.db + data/receipts/")]

    UI --> SVC
    UI --> I18N
    SVC --> DAO
    SVC --> MODEL
    DAO --> MODEL
    DAO --> DB
    I18N -.->|"translations.csv / app_settings"| UI
```

### Alkalmazott tervezési minták
- **DAO / Repository minta:** Minden SQL lekérdezés paraméterezett `PreparedStatement`-ekkel a `dao` csomagban izolált, és írási tranzakciókban megosztott `Connection` példányt használ (`runInTransaction`).
- **Dependency Container (`AppContext`):** Központi konténer példányosítja és köti össze az adatbázist, a 17 DAO-t, a szolgáltatásokat és az i18n kezelőt.
- **Observer / Listener minta:** `LanguageChangeListener`, `ThemeManager.ThemeChangeListener`, `NotificationService` és `RoleModuleService` változás-figyelők biztosítják a nyelv, téma, élő jelvények és modul-jogosultságok újraindítás nélküli, azonnali frissítését.
- **Strategy / Document Source minta:** A `LabelPreset` enum (`THERMAL_100X150`, `THERMAL_80X100`, `A6_CARD`, `A4_4UP`) vezérli a címkenyomtatást (beleértve a kötelező mentés előtti előnézetet is), a `DocumentPageSource` interfész pedig egységesíti az A4 PDF számlák, jóváíró számlák, szállítólevelek, partner korosítási egyenlegközlők és P&L riportok kezelését.
- **Lazy View Factory & Off-EDT Worker minta:** A `MainFrame` `Supplier<NavigableView>` gyárakkal lustán hozza létre a nézeteket (kirajzolás előtti azonnali `refreshData()` hívással, üres képkocka nélkül), az `AsyncUiRunner` pedig `SwingWorker` segítségével a háttérszálra viszi a 250–300 DPI PDF generálást, CSV exportot, készletszűrést és oldalsáv-jelvény lekérdezéseket.

---

## 2. Use-Case diagram és használati esetek (5 szerepkör)

```mermaid
flowchart LR
    OP(("Válogató Operátor<br/>(OPERATOR)"))
    FL(("Targoncás<br/>(FORKLIFT)"))
    MGR(("Raktárvezető<br/>(MANAGER)"))
    ADM(("Adminisztrátor<br/>(ADMIN)"))
    SADM(("Szuper Admin<br/>(SUPER_ADMIN)"))

    subgraph WMS["ReWear WMS v2.5 Rendszer"]
        UC1["UC-01 Bejelentkezés, Profil, Teendők, Értesítések, Belső Chat & E-mail"]
        UC2["UC-02 Dashboard, XChart Analitika & EUR/HUF Készletértékelés"]
        UC3["UC-03 Csomagfelvétel (saját állomás), Mentés Előtti Előnézet & QR Nyomtatás"]
        UC4["UC-04 Betárolás (Állomás -> Polc) & Összekészítés (QR Szkennelés -> Kiadó Zóna)"]
        UC5["UC-05 QR Szkenner Állomás & Készlet Böngészés / Kötegelt Műveletek"]
        UC6["UC-06 Kódtörzsek (EUR/HUF), Raktárhelyek & B2B Partnerek / Korosítási PDF"]
        UC7["UC-07 Rendelések, Targoncás Kiosztás, Vevő Érkezés & QR-Ellenőrzött Átadás"]
        UC8["UC-08 Többdevizás Számlák, Jóváírások, Bizonylattár, Lejárt Emlékeztető & P&L PDF"]
        UC9["UC-09 Felhasználók, Audit Napló, DB Backup/Restore, i18n CSV & Diagnosztika"]
        UC10["UC-10 Vállalati E-mail Domain/SMTP/IMAP & Szerepkör x Modul Jogosultsági Mátrix"]
    end

    OP --> UC1
    OP --> UC2
    OP --> UC3
    OP --> UC5

    FL --> UC1
    FL --> UC2
    FL --> UC4
    FL --> UC5

    MGR --> UC1
    MGR --> UC2
    MGR --> UC3
    MGR --> UC4
    MGR --> UC5
    MGR --> UC6
    MGR --> UC7
    MGR --> UC8

    ADM -.->|"örökli"| MGR
    ADM --> UC9

    SADM -.->|"korlátozatlan"| ADM
    SADM --> UC10
```

---

## 3. UML Osztálydiagram (Kiemelt osztályok)

```mermaid
classDiagram
    class AppContext {
        -DatabaseManager databaseManager
        -I18nManager i18n
        -SessionContext session
        -RoleModuleService roleModuleService
        -InventoryService inventoryService
        -TransportService transportService
        -OrderService orderService
        -InvoiceService invoiceService
        -EmailService emailService
        -ChatService chatService
        -NotificationService notificationService
        -TodoService todoService
    }

    class AppModule {
        <<enumeration>>
        DASHBOARD
        TODO
        INTAKE
        PUTAWAY
        STAGING
        SCANNER
        INVENTORY
        LOCATIONS
        DICTIONARIES
        CUSTOMERS
        ORDERS
        INVOICES
        NOTIFICATIONS
        MESSAGES
        EMAIL
        ADMIN
        +defaultEnabledFor(RoleCode) boolean
    }

    class RoleModuleService {
        +isModuleEnabled(RoleCode, AppModule) boolean
        +getPermissionMatrix() Map
        +setModuleEnabled(RoleCode, AppModule, boolean)
        +resetAllToDefaults()
    }

    class SessionContext {
        -User currentUser
        -RoleModuleService roleModuleService
        +canAccessModule(AppModule) boolean
        +canUseExternalEmail() boolean
        +canManageChatRooms() boolean
    }

    class InventoryService {
        +prepareDraftPackages(...) List~InventoryPackage~
        +commitPreparedPackages(drafts, userId) List~InventoryPackage~
        +relocatePackages(pkgIds, locId, note, userId) List~InventoryPackage~
    }

    class TransportService {
        +findPutawayQueue() List~InventoryPackage~
        +movePackagesToStorage(ids, locId, userId) int
        +findOrderStagingTasksForDriver(driverId) List~StagingTask~
        +scanAndCollectForOrder(qr, dockId, userId, driverOnly) StagingTask
    }

    class OrderService {
        +assignForkliftAndDock(orderId, driverId, dockId) Order
        +markCustomerArrived(orderId) Order
        +scanPackageForHandover(orderId, qr) OrderItem
        +completeVerifiedHandover(orderId, userId) Order
    }

    class MainFrame {
        -Map~String, Supplier~ viewFactories
        -Map~String, BadgeNavButton~ navButtons
        +navigateTo(viewKey)
        +rebuildSidebarNavigation()
    }

    AppContext --> SessionContext
    AppContext --> RoleModuleService
    AppContext --> InventoryService
    AppContext --> TransportService
    AppContext --> OrderService
    SessionContext --> RoleModuleService
    RoleModuleService ..> AppModule
    MainFrame --> AppContext
    MainFrame ..> AppModule
```

---

## 4. Fő folyamatok szekvenciadiagramjai

### 4.1 Operátori csomagfelvétel kötelező mentés előtti előnézettel és targoncás értesítéssel
```mermaid
sequenceDiagram
    actor Op as Válogató Operátor
    participant UI as PackageIntakeView
    participant Inv as InventoryService
    participant Prev as PrintPreviewDialog
    participant DB as SQLite (DAO)
    participant Notif as NotificationService

    Op->>UI: Kódelemek + súly megadása (saját ST-OP-01 állomáson)
    Op->>UI: "Előnézet és Mentés"
    UI->>Inv: prepareDraftPackages(...) (még NEM ír DB-be)
    Inv-->>UI: List<InventoryPackage> (előnézeti vázlatok)
    UI->>Prev: showPreSavePreview(drafts, preset)
    Op->>Prev: "Mentés és Nyomtatás" / "Csak mentés" jóváhagyása
    Prev->>Inv: commitPreparedPackages(drafts, opId)
    Inv->>DB: ATOMI TRANZAKCIÓ: INSERT inventory_packages + stock_movements (INTAKE)
    Inv->>Notif: notifyRoles(FORKLIFT, PUTAWAY, target=VIEW_PUTAWAY)
    Notif-->>UI: Targoncás élő piros jelvény + értesítés frissül
```

### 4.2 Targoncás QR-szkenneléses komissiózás, vevő érkezés és ellenőrzött kiadás
```mermaid
sequenceDiagram
    actor FL as Targoncás
    actor Mgr as Raktárvezető
    participant Stage as TransportView (STAGING)
    participant TrSvc as TransportService
    participant OrdSvc as OrderService
    participant Handover as OrderHandoverDialog
    participant Notif as NotificationService

    Mgr->>OrdSvc: assignForkliftAndDock(orderId, forkliftId, DOCK-01)
    OrdSvc->>Notif: Értesítés a targoncásnak (VIEW_STAGING)
    FL->>Stage: Csomag QR-kód beolvasása
    Stage->>TrSvc: scanAndCollectForOrder(qr, DOCK-01, forkliftId)
    TrSvc->>TrSvc: Áthelyezés DOCK-01-re + státusz DRAFT -> PACKING
    TrSvc->>Notif: Összes tétel a zónában -> "Kiadásra kész" értesítés Raktárvezetőnek
    Mgr->>OrdSvc: markCustomerArrived(orderId) ("Vevő megérkezett")
    OrdSvc->>Notif: Prioritásos riasztás Raktárvezetőknek + kijelölt Targoncásnak
    Mgr->>Handover: Minden csomag QR-szkenneléses ellenőrzése
    Handover->>OrdSvc: scanPackageForHandover(orderId, qr) -> handover_scanned=1
    Handover->>OrdSvc: completeVerifiedHandover(orderId, mgrId) -> SHIPPED + SHIP napló
```

---

## 5. Tesztelési terv és eredmények (JUnit 5)

A rendszer üzleti logikáját, 24 táblás adatbázis-integritását, többdevizás pénzügyi számításait, targoncás és átadási folyamatait, levelezését, belső chatjét, értesítéseit, modul-jogosultsági mátrixát és háttérszálú UI-futtatását **20 tesztosztály, összesen 50 automatizált JUnit 5 teszt** ellenőrzi (0 `-Xlint:all,-serial` fordítási figyelmeztetés):

| Tesztosztály | Tesztek | Ellenőrzött funkcionalitás |
|---|:---:|---|
| `I18nManagerTest` | 5 | EN/HU fordítások, paraméteres formázás, 3. nyelv (`de`) dinamikus CSV betöltése, export/újratöltés, teljes **553 kulcsos** `translations.csv` szerkezeti integritás-ellenőrzés |
| `ProductCodeServiceTest` | 4 | `YYYY-MM-DD-HH-MM-...` konvenció, tizedes súlyok, szegmens-normalizálás, 40 ütközésmentes hex végződés, hibás kódok elutasítása |
| `DatabaseLayerIntegrationTest` | 4 | 24 táblás séma + idempotens demo seed, BCrypt ellenőrzés, egyedi kódtörzsek, `UNIQUE` és `FOREIGN KEY` megszorítások, telítettség és statisztikák |
| `AuthServiceTest` | 4 | Szerepkörök és BCrypt hitelesítés, hibás jelszó / inaktív fiók elutasítása, jelszócsere, hordozható profil- és preferencia-perzisztencia |
| `Wave2MultiLineCreditNoteAndBulkTest` | 4 | Többsoros kézi számlák, 1-kattintásos jóváíró számla (`CN-`), kettős EUR/HUF készletértékelés & P&L PDF, atomi kötegelt áthelyezés |
| `V22CommsAndForkliftWorkflowTest` | 4 | Operátori állomás-korlátozás, előnézet-vázlat vs. végleges mentés, targoncás betárolás/komissiózás, vállalati domain/e-mail + belső-only szabály, Element-stílusú chat szobák & DM |
| `CompanyProfileOverdueAndPricingTest` | 3 | Szerkeszthető eladói cégprofil + devizánkénti IBAN PDF, automatikus `ISSUED → OVERDUE` + `mailto:` emlékeztető, rendelési tételár módosítás |
| `AsyncUiRunnerTest` | 3 | Háttérszálú (off-EDT) futtatás + EDT kézbesítés, kivétel továbbítás, `runLatest` elavult válaszok eldobása |
| `InventoryServiceAndLabelPdfTest` | 2 | Atomi kötegelt csomagfelvétel + áthelyezés + leltári ellenőrzés + magyar nyelvű Thermal 100×150 és A4 4-es PDF címke generálás |
| `ScannerAndOrderServiceTest` | 2 | Szöveges & képfájlos QR szkennelés + teljes rendelési életciklus (`DRAFT` → `PACKING` → `SHIPPED` és `CANCELLED` felszabadítás) |
| `CsvExportAndDictionaryGuardTest` | 2 | UTF-8 BOM magyar készlet és audit napló CSV export, hivatkozott kódtörzs törlésvédelme |
| `InvoiceAndReversibleOrderTest` | 2 | Rendeléshez kapcsolt kimenő számla + kombinált számla/szállítólevél PDF, kiszállított rendelés visszavonása |
| `MultiCurrencyPricingAndPnlTest` | 2 | Ország-alapú adótípus ajánlás (`HU 27%` vs `AT/DE 0%`), elkülönített `EUR`/`HUF`/`USD` főkönyvi P&L összesítés |
| `Wave3ReceiptVaultAndAgingTest` | 2 | Hordozható bizonylattár (`./data/receipts/`), partner korosítási egyenlegközlő (Current / 1–30 / 31–60 / 61–90 / 90+ nap) és A4 PDF |
| `V23NotificationsTodoAndStationTest` | 2 | Automatikus értesítési triggerek, kijelölt operátori állomás zárolás, szerepkör-specifikus rendszer- és személyes teendők |
| `V24OrderHandoverWorkflowTest` | 1 | Targoncáshoz rendelt QR-szkenneléses komissiózás, vevő érkezési értesítés és 100%-os QR-ellenőrzött raktárvezetői kiadás |
| `V25RoleModulePermissionsTest` | 1 | 16 modul alapértelmezett szerepkör-szabályai, Szuper Admin valós idejű ki-/bekapcsolás & visszaállítás, `SUPER_ADMIN` kizárás-védelem, jogosulatlan módosítás tiltása (`SecurityException`) |
| `QrCodeServiceTest` | 1 | ZXing QR generálás → PNG fájl mentés → dekódolás pontos round-trip ellenőrzése |
| `DeliveryNotePdfExporterTest` | 1 | Angol és magyar A4 PDF szállítólevél generálása rendelés QR-kóddal |
| `BackupServiceTest` | 1 | SQLite `VACUUM INTO` biztonsági mentés, visszaállítás, előzetes `.db.bak` mentés, érvénytelen fájl elutasítása |
