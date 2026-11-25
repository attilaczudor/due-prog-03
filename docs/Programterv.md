# Programterv — ReWear WMS (`due-prog-03`)

Ez a dokumentum a **ReWear WMS** szoftverarchitektúráját, használati eseteit (Use-Case), UML osztálymodelljét, főbb futásidejű folyamatait (szekvenciadiagramok) és automatizált tesztelési eredményeit mutatja be.

---

## 1. Rétegzett architektúra (Layered MVC)

Az alkalmazás szigorúan elkülönített rétegekre épül a `hu.due.rewear` gyökércsomag alatt (**161 fő Java forrásfájl**, **24 JUnit 5 tesztosztály**, **62 teszt**):

```mermaid
flowchart TD
    UI["UI Réteg (hu.due.rewear.ui)<br/>LoginFrame, MainFrame (modul-alapú oldalsáv, élő jelvények, auto-refresh),<br/>18 oldalmenümodul (TransportView PUTAWAY/STAGING, TodoView,<br/>NotificationsView, MessagesView, EmailView + RecipientMultiSelectField,<br/>SortingView, PayrollView, AdminModulesPanel, AdminEmailSettingsPanel, OrderHandoverDialog,<br/>PrintPreviewDialog, DocumentPreviewDialog, AsyncUiRunner, SectionNavigation)"]
    SVC["Üzleti Logika & PDF Réteg (hu.due.rewear.service)<br/>AuthService, SessionContext, RoleModuleService, ProductCodeService,<br/>QrCodeService, InventoryService, TransportService, SortingService, OrderService, PayrollService,<br/>ScannerService, InvoiceService, ReceiptVaultService, PartnerStatementService,<br/>EmailService, ChatService, NotificationService, TodoService,<br/>PDF/CSV exportálók, BackupService"]
    I18N["Nyelvi & Téma Réteg (i18n / config)<br/>I18nManager (810 EN/HU kulcs), CsvTranslationLoader,<br/>ThemeManager, AppPreferences, AppContext"]
    DAO["Adatelérési Réteg (hu.due.rewear.dao)<br/>JDBC DAO-k, például RoleDao, UserDao, RoleModulePermissionDao, SettingsDao,<br/>FinanceConfigDao, DictionaryDao, WarehouseLocationDao, InventoryPackageDao,<br/>StockMovementDao, CustomerDao, OrderDao, OrderItemDao, InvoiceDao,<br/>EmailDao, ChatDao, NotificationDao, TodoDao"]
    MODEL["Domain Modellek (hu.due.rewear.model)<br/>Java 17 Record POJO-k és Enumok (AppModule, RoleCode, LocationType,<br/>InventoryPackage, Order, Invoice, EmailMessage, ChatRoom, AppNotification, ...)"]
    DB[("Beágyazott SQLite 3 (41 tábla, 23 deklarált index)<br/>data/rewear-wms.db + data/invoices/ + data/receipts/")]

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
- **Dependency Container (`AppContext`):** Központi konténer példányosítja és köti össze az adatbázist, a DAO-kat, a szolgáltatásokat és az i18n kezelőt.
- **Observer / Listener minta:** `LanguageChangeListener`, `ThemeManager.ThemeChangeListener`, `NotificationService` és `RoleModuleService` változás-figyelők biztosítják a nyelv, téma, élő jelvények és modul-jogosultságok újraindítás nélküli, azonnali frissítését.
- **Strategy / Document Source minta:** A `LabelPreset` enum (`THERMAL_100X150`, `THERMAL_80X100`, `A6_CARD`, `A4_4UP`) vezérli a címkenyomtatást (beleértve a kötelező mentés előtti előnézetet is), a `DocumentPageSource` interfész pedig egységesíti az A4 PDF számlák, jóváíró számlák, szállítólevelek, partner korosítási egyenlegközlők és P&L riportok kezelését.
- **Lazy View Factory & Off-EDT Worker minta:** A `MainFrame` `Supplier<NavigableView>` gyárakkal lustán hozza létre a nézeteket (kirajzolás előtti azonnali `refreshData()` hívással, üres képkocka nélkül), az `AsyncUiRunner` pedig `SwingWorker` segítségével a háttérszálra viszi a 250–300 DPI PDF generálást, CSV exportot, készletszűrést és oldalsáv-jelvény lekérdezéseket.
- **Oldalsáv:** Kis ablakmagasságnál a menüpontok külön görgethetők. Az avatar és a fiókvezérlők az oldalsáv alján maradnak.
- **Másodlagos oldalsáv:** Az adminisztráció, a kódtörzsek és a számlázás szakaszai a levelező mappamenüjéhez hasonlóan görgethetők és ikonokra csukhatók. A `SectionNavigation` a kiválasztott panelt `CardLayout` segítségével jeleníti meg.
- **E-mail beállítások:** Az űrlap a rendelkezésre álló szélességhez igazodik. Szűkebb nézetben a domain, az üzemmód és a szinkronizálás vezérlői több sorba rendeződnek. Az SMTP- és IMAP-mezők rugalmas szélességűek, a lap szükség esetén függőlegesen görgethető.
- **Fiókszerkesztés:** A Felhasználók listában dupla kattintás vagy Enter nyitja meg a szerkesztőt. Az opcionális jelszó-visszaállításhoz két egyező mező szükséges. Az egyedi e-mail címet csak Szuperadmin módosíthatja a szerkesztőben. A saját profilban a cím csak olvasható. Az Admin közös menüsorrendje Felhasználók, Számlabeállítások, NAV-integráció, Banki integráció, Adatbázis, Fordítások, Log és Rendszer. Szuperadminnál a Szerepkörök a Számlabeállítások fölött, az E-mail beállítások alatta jelenik meg. A módosult vagy elérhetetlen külső fordítási CSV értesítést és jelvényt ad az Admin menüben.

- **Válogatás:** A válogató alapanyagot igényel és rögzíti a válogatott tételeket. A segéd csomagol, az állomásvezető címkéz és készletre vesz. A napi cél a ténylegesen feldolgozott alapanyagból számítódik. A [megvalósítási terv](Sorting-to-Sale-Implementation.md) a folyamatrögzítést és a webshopimportot is leírja.
- **Bérszámfejtés:** Az Adminisztrátor és a Szuperadmin választhat munkanapot, rögzíthet hatályos havi és részlegenkénti bruttó díjakat, illetve megoszthat egy napot több részleg között. A `PayrollService` a válogató feldolgozott súlyát és a segéd tényleges csomagolását mutatja. A jóváhagyott műszakbónuszt a bruttó tervezethez adja. A számítás szabályait a [bérszámfejtési terv](Payroll-Implementation-Plan.md) rögzíti.
- **Számlák és intelligens mappák:** A számlaoldal az összes számlával indul. A jobb oldali szűrősávban kereshetők a partnerek és szállítók. Az aktív partner-, irány-, deviza-, állapot- és kategóriaszűrőkből intelligens mappa hozható létre. Kizárólag bejövő vagy kimenő irány esetén a megfelelő ikon az alapértelmezett. A fogaskerék csak kibontott menüben jelenik meg. A fejléc hárompontos menüjében elérhető pénzügyi PDF-riport a kijelölt teljes devizafőkönyveket mutatja. A bezárt szűrősáv aktív feltételeit piros pont jelzi. A CSV export a látható listát követi. Keskeny nézetben a lista a legfontosabb oszlopokat jeleníti meg. Dupla kattintás vagy Enter nyitja meg a teljes számlabontást és a műveleteket.
- **Számlabeállítások:** Az Admin oldalon külön szerkeszthető az utca, a város és az irányítószám, a számlázási e-mail-címet a szuperadmin módosíthatja. Minden konfigurált devizához külön IBAN mező tartozik. A számla és a fizetési emlékeztető a hozzá tartozó deviza IBAN-ját jeleníti meg, ha van ilyen. Az áfa- és devizasor szerkesztője dupla kattintással vagy Enterrel nyílik meg. A törlés megerősítést kér.
- **Dokumentumküldés és rendezés:** A PDF-előnézet az alkalmazás saját levélszerkesztőjét nyitja meg a csatolt dokumentummal és a fájlmappa megnyitására szolgáló gombbal. A táblázatok oszlopfejlécein a nyilak a rendezési irányt jelzik.
- **Számlafelvétel:** A kimenő számla rendeléskeresőből indul. A rendelési tételek csak olvashatók, és mentéskor újra ellenőrzésre kerülnek. Kézi bevétel vagy kiadás esetén a partner és a számla egy tranzakcióban mentődik. Tételsorok vagy nettó összeg adható meg, számlánként egy konfigurált áfatípussal és opcionális PDF/PNG/JPEG bizonylattal.
- **Számlaarchívum és bizonylattár:** A mentés PDF-t készít a `data/invoices/ÉÉÉÉ/HH/` mappába. A feltöltött bizonylat a `data/receipts/ÉÉÉÉ/HH/` mappába kerül. A fájlnév a számla kiállítási dátumát, a fájlnévhez igazított partnernevet és a bruttó összeget tartalmazza. Névütközéskor utótag különbözteti meg a fájlokat. A logós indítóablak jelzi a tárhely előkészítését, az adatbázis megnyitását és a szolgáltatások betöltését. A munkaterületek fejlécét egypixeles vonal választja el.
- **NAV- és banki integráció:** A NAV Online Számla 3.0 beállítási űrlapja és számla-előnézete helyi demó. A kimenő számláknál kibocsátói adatszolgáltatást, a bejövőknél számlalekérdezést mutat. A banki oldal kézi számlákat és jóváírásokat vagy terheléseket kezel. A könyvelt egyenleg a nyitó egyenleg és az előjeles tranzakciók összege, `BigDecimal` pontossággal és devizánként elkülönítve.
- **Mentett szűrések:** A `SmartFolderDao` felhasználónként tárolja a névvel és ikonnal mentett számla- és e-mail-szűréseket. A szabályok az eredeti rekordokat jelenítik meg. A mappasor fogaskereke megnyitja az átméretezhető szerkesztőt és a megerősítést kérő törlést. Minden választható szűrőcsoport első eleme az Összes. A szerkesztő közvetlenül megnyílik, az SVG-ikonkészlet kereshető és két soronként bővíthető.

---

## 2. Use-Case diagram és használati esetek

```mermaid
flowchart LR
    OP(("Állomásvezető<br/>(OPERATOR)"))
    SORTER(("Válogató operátor<br/>(SORTER)"))
    HELPER(("Válogatósegéd<br/>(SORTING_HELPER)"))
    FL(("Targoncás<br/>(FORKLIFT)"))
    MGR(("Raktárvezető<br/>(MANAGER)"))
    ADM(("Adminisztrátor<br/>(ADMIN)"))
    SADM(("Szuper Admin<br/>(SUPER_ADMIN)"))

    subgraph WMS["ReWear WMS"]
        UC1["UC-01 Bejelentkezés, Profil, Teendők, Értesítések, Belső Chat & E-mail"]
        UC2["UC-02 Dashboard, XChart Analitika & EUR/HUF Készletértékelés"]
        UC3["UC-03 Csomagfelvétel (saját állomás), Mentés Előtti Előnézet & QR Nyomtatás"]
        UC4["UC-04 Betárolás (Állomás -> Polc) & Összekészítés (QR Szkennelés -> Kiadó Zóna)"]
        UC5["UC-05 Készletböngészés, jogosultság szerint QR Szkenner Állomás és kötegelt műveletek"]
        UC6["UC-06 Kódtörzsek (EUR/HUF), Raktárhelyek & B2B Partnerek / Korosítási PDF"]
        UC7["UC-07 Rendelések, Targoncás Kiosztás, Vevő Érkezés & QR-Ellenőrzött Átadás"]
        UC8["UC-08 Többdevizás Számlák, Jóváírások, Bizonylattár, Lejárt Emlékeztető & P&L PDF"]
        UC9["UC-09 Felhasználók, Audit Napló, DB Backup/Restore, i18n CSV & Diagnosztika"]
        UC11["UC-11 Alapanyag, válogatás, csomagolás és címkézés"]
        UC12["UC-12 Napi teljesítmény és bruttó bértervezet"]
        UC10["UC-10 Vállalati E-mail Domain/SMTP/IMAP & Szerepkör x Modul Jogosultsági Mátrix"]
    end

    OP --> UC1
    OP --> UC2
    OP --> UC3
    OP --> UC5
    OP --> UC11

    SORTER --> UC1
    SORTER --> UC11
    HELPER --> UC1
    HELPER --> UC11

    FL --> UC1
    FL --> UC2
    FL --> UC4
    FL --> UC5
    FL --> UC11

    MGR --> UC1
    MGR --> UC2
    MGR --> UC3
    MGR --> UC4
    MGR --> UC5
    MGR --> UC6
    MGR --> UC7
    MGR --> UC8
    MGR --> UC11

    ADM -.->|"örökli"| MGR
    ADM --> UC9
    ADM --> UC12

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
        +buildPreviewPackages(...) List~InventoryPackage~
        +commitPreviewedPackages(drafts, location, userId, role) List~InventoryPackage~
        +relocatePackages(pkgIds, locId, note, userId) List~InventoryPackage~
    }

    class TransportService {
        +findPutawayQueue() List~InventoryPackage~
        +putawayToStorage(ids, locId, userId) int
        +findOrderStagingTasksForDriver(driverId) List~StagingTask~
        +scanAndCollectForOrder(qr, dockId, userId, driverOnly) StagingTask
    }

    class OrderService {
        +assignForkliftAndDock(orderId, driverId, dockId) Order
        +markCustomerArrived(orderId) Order
        +scanOrderItemForHandover(orderId, qr, mgrId) OrderItem
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
    actor Op as Állomásvezető
    participant UI as PackageIntakeView
    participant Inv as InventoryService
    participant Prev as PrintPreviewDialog
    participant DB as SQLite (DAO)
    participant Notif as NotificationService

    Op->>UI: Kódelemek + súly megadása (saját ST-OP-01 állomáson)
    Op->>UI: "Előnézet és Mentés"
    UI->>Inv: buildPreviewPackages(...) (még NEM ír DB-be)
    Inv-->>UI: List<InventoryPackage> (előnézeti vázlatok)
    UI->>Prev: showPreSavePreview(drafts, preset)
    Op->>Prev: "Mentés és Nyomtatás" / "Csak mentés" jóváhagyása
    Prev->>Inv: commitPreviewedPackages(drafts, station, opId, role)
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
    Stage->>TrSvc: scanAndCollectForOrder(qr, DOCK-01, forkliftId, true)
    TrSvc->>TrSvc: Zóna és kapacitás ellenőrzése, atomi áthelyezés és státuszváltás
    TrSvc->>Notif: Az utolsó csomag megérkezésekor kiadásra kész értesítés
    Mgr->>OrdSvc: markCustomerArrived(orderId) ("Vevő megérkezett")
    OrdSvc->>Notif: Prioritásos riasztás Raktárvezetőknek + kijelölt Targoncásnak
    Mgr->>Handover: Minden csomag QR-szkenneléses ellenőrzése
    Handover->>OrdSvc: scanOrderItemForHandover(orderId, qr, mgrId) -> handover_scanned=1
    Handover->>OrdSvc: completeVerifiedHandover(orderId, mgrId) -> SHIPPED + SHIP napló
```

A kijelölt átvételnél az állapotválasztó is ellenőrzi az összes QR-beolvasást és a kiadási zónát. Ismételt beolvasás esetén egyetlen ellenőrzési mozgás marad a naplóban. Újranyitáskor törlődnek a korábbi átadás jelölései.

A rendelési dokumentumok előnézete a szállítólevelet és a már meglévő számlát mutatja. Új számla a számlázási űrlapon állítható ki.

---

## 5. Tesztelési terv és eredmények (JUnit 5)

A rendszer üzleti logikáját, 41 táblás adatbázis-integritását, többdevizás pénzügyi számításait, targoncás és átadási folyamatait, levelezését, belső chatjét, értesítéseit, modul-jogosultsági mátrixát és háttérszálú UI-futtatását **24 tesztosztály, összesen 62 automatizált JUnit 5 teszt** ellenőrzi:

| Tesztosztály | Tesztek | Ellenőrzött funkcionalitás |
|---|:---:|---|
| `SortingToSaleWorkflowTest` | 2 | Alapanyag, válogatás, csomagolás és készletre vétel. Webshopimport, készletfoglalás, ellenőrzött kiadás és számlázás. |
| `PayrollWorkflowTest` | 1 | Rugalmas csapatlétszám, tényleges segédteljesítmény, megosztott műszak és dátumfüggő díjtételek. |
| `OfflineIntegrationTest` | 2 | A NAV demó adatainak mentése hitelesítő adatok nélkül. Több bankszámla, új deviza és pontos egyenleg újranyitás után. |
| `I18nManagerTest` | 5 | EN/HU fordítások, paraméteres formázás, 3. nyelv (`de`) dinamikus CSV betöltése, export/újratöltés, teljes **810 kulcsos** `translations.csv` szerkezeti integritás-ellenőrzés |
| `ProductCodeServiceTest` | 4 | `YYYY-MM-DD-HH-MM-...` konvenció, tizedes súlyok, szegmens-normalizálás, 40 ütközésmentes hex végződés, hibás kódok elutasítása |
| `DatabaseLayerIntegrationTest` | 4 | 41 táblás séma + idempotens demo seed, BCrypt ellenőrzés, egyedi kódtörzsek, `UNIQUE` és `FOREIGN KEY` megszorítások, telítettség és statisztikák |
| `SmartFolderTest` | 2 | Mentett szűrések perzisztenciája, tulajdonos és nézet szerinti elkülönítése, valamint többfeltételes számla- és levélegyezés |
| `AuthServiceTest` | 5 | BCrypt-hitelesítés, jelszócsere, profil- és preferenciamentés, valamint az adminisztrátori fiókszerkesztés és e-mail-módosítás jogosultságai |
| `Wave2MultiLineCreditNoteAndBulkTest` | 4 | Többsoros kézi számlák, 1-kattintásos jóváíró számla (`CN-`), kettős EUR/HUF készletértékelés & P&L PDF, atomi kötegelt áthelyezés |
| `V22CommsAndForkliftWorkflowTest` | 4 | Operátori állomás-korlátozás, előnézet-vázlat vs. végleges mentés, targoncás betárolás/komissiózás, vállalati domain/e-mail + belső-only szabály, Element-stílusú chat szobák & DM |
| `CompanyProfileOverdueAndPricingTest` | 3 | Szerkeszthető eladói cégprofil + devizánkénti IBAN PDF, automatikus `ISSUED → OVERDUE` + `mailto:` emlékeztető, rendelési tételár módosítás |
| `AsyncUiRunnerTest` | 3 | Háttérszálú (off-EDT) futtatás + EDT kézbesítés, kivétel továbbítás, `runLatest` elavult válaszok eldobása |
| `InventoryServiceAndLabelPdfTest` | 2 | Atomi kötegelt csomagfelvétel + áthelyezés + leltári ellenőrzés + magyar nyelvű Thermal 100×150 és A4 4-es PDF címke generálás |
| `ScannerAndOrderServiceTest` | 2 | Szöveges & képfájlos QR szkennelés + teljes rendelési életciklus (`DRAFT` → `PACKING` → `SHIPPED` és `CANCELLED` felszabadítás) |
| `CsvExportAndDictionaryGuardTest` | 2 | UTF-8 BOM magyar készlet és audit napló CSV export, hivatkozott kódtörzs törlésvédelme |
| `InvoiceAndReversibleOrderTest` | 4 | Rendelésből kiállított számlák és PDF-ek, rendelési módosítás felismerése, visszavonás, kézi partnermentés, áfa és bizonylat visszagörgetése |
| `MultiCurrencyPricingAndPnlTest` | 2 | Ország-alapú adótípus ajánlás (`HU 27%` vs `AT/DE 0%`), elkülönített `EUR`/`HUF`/`USD` főkönyvi P&L összesítés |
| `Wave3ReceiptVaultAndAgingTest` | 2 | Hordozható bizonylattár (`./data/receipts/`), partner korosítási egyenlegközlő (Current / 1–30 / 31–60 / 61–90 / 90+ nap) és A4 PDF |
| `V23NotificationsTodoAndStationTest` | 2 | Automatikus értesítési triggerek, kijelölt operátori állomás zárolás, szerepkör-specifikus rendszer- és személyes teendők |
| `V24OrderHandoverWorkflowTest` | 1 | Targoncáshoz rendelt QR-szkenneléses komissiózás, vevő érkezési értesítés és 100%-os QR-ellenőrzött raktárvezetői kiadás |
| `V25RoleModulePermissionsTest` | 3 | Moduljogosultságok, Admin menüsorrend és szerepkörök, valamint a külső fordítási fájl értesítési állapotai |
| `QrCodeServiceTest` | 1 | ZXing QR generálás → PNG fájl mentés → dekódolás pontos round-trip ellenőrzése |
| `DeliveryNotePdfExporterTest` | 1 | Angol és magyar A4 PDF szállítólevél generálása rendelés QR-kóddal |
| `BackupServiceTest` | 1 | SQLite `VACUUM INTO` biztonsági mentés, visszaállítás, előzetes `.db.bak` mentés, érvénytelen fájl elutasítása |
