# Projektterv — ReWear WMS (`due-prog-03`)

**Tantárgy:** Programozás III. — Java GUI Projektfeladat  
**Intézmény:** Dunaújvárosi Egyetem  
**Projekt neve:** ReWear WMS — Válogatott Használt Ruha Raktár-, QR Készlet-, Targonca-Logisztikai, Belső Kommunikációs és Többdevizás Számlázó Rendszer  
**Projektvezető:** Czudor Attila  
**Résztvevők:** Czudor Attila (egyéni projekt — teljes körű tervezés, adatbázis-tervezés, implementáció, tesztelés, dokumentáció)  
**Fejlesztési időszak:** 2026. november  
**Forráskód tárhely:** https://github.com/attilaczudor/due-prog-03  

---

## 1. A projekt rövid leírása és célkitűzése

A használt ruházati nagykereskedelmi és válogató üzemek napi működésében kritikus feladat a beérkező, válogató állomásokon feldolgozott és különböző csomagolási egységekbe (bála, zsák, kartondoboz, raklap) rendezett textíliák gyors, hibamentes nyomon követése, a targoncakezelők betárolási és komissiózási feladatainak koordinálása, a belső csapatkommunikáció, valamint a hazai (HUF / 27% ÁFA) és nemzetközi EU/export (EUR/USD / 0% ÁFA) B2B értékesítések és beszállítói költségszámlák elkülönített nyilvántartása.

A **ReWear WMS** egy többplatformos, asztali **Java 17 GUI alkalmazás**, amely erre a valós raktárlogisztikai és vállalati feladatra ad teljes körű, nulla szerverigényű megoldást:

1. **Strukturált QR termékkód konvenció:** Minden csomag egy beszédes, ember és gép által is azonnal értelmezhető, egyedi QR-kódot kap:
   ```text
   ÉÉÉÉ-HH-NN-ÓÓ-PP-<CÉLCSOPORT>-<TERMÉKTÍPUS>-<MINŐSÉG>-<CSOMAGOLÁS>-<SÚLY>-<HEX2>
   ```
   Példa: `2026-11-12-11-23-L-TSH-A-A-25-3F`  
   *(2026.11.12. 11:23-kor rögzített, Női (`L`) Póló (`TSH`), `A` minőségi osztály, `A` típusú préselt bála, `25 kg`, `3F` véletlen 2 jegyű hexadecimális egyediség-végződés).*
2. **Alkalmazáson belüli kódtörzsek, dedikált operátori állomások és kötelező mentés előtti előnézet:**
   - Új célcsoportok, terméktípusok, minőségi osztályok és csomagolástípusok közvetlen felvitele (akár csomagfelvétel közben a `+` gombokkal is), EUR/kg és HUF/kg alapárakkal.
   - A raktárhelyek funkcionális típusra osztottak (`OPERATOR_STATION`, `STORAGE`, `DISPATCH_ZONE`). Az állomásvezetők a kijelölt címkézőállomásra (`ST-OP-01..03`) rögzítenek, és minden új csomag mentése előtt kötelező **Mentés előtti termék- és címke-előnézet** jelenik meg (csak jóváhagyáskor történik adatbázisba írás).
3. **Targonca-logisztika (`Betárolás` és `Összekészítés`) és QR-ellenőrzött vevői kiadás:**
   - Külön **`Betárolás`** modul a válogató állomásokról polcokra történő áthelyezéshez, és külön **`Összekészítés`** modul a targoncáshoz rendelt nyitott rendelések vonalkód/QR-szkenneléses összegyűjtéséhez a kiadó gyűjtőzónába (`DOCK-01`).
   - A raktárvezető targoncást és gyűjtőzónát rendel a megrendeléshez, jelzi a **Vevő megérkezett** eseményt (értesítéssel), majd az **`OrderHandoverDialog`** ablakban 100%-os QR-ellenőrzéssel adja át a csomagokat.
4. **Címkenyomtatás, QR szkenner állomás és kötegelt műveletek:** Beépített, nagyítható előnézet (`Hőcímke 100×150 mm`, `80×100 mm`, `A6`, `A4 4-es ív`), rendszer-nyomtató (`PrinterJob`), 300-DPI OpenPDF export, PNG mentés, többsoros kötegelt áthelyezés és csoportos nyomtatás; USB/Bluetooth HID és képfájlos (PNG/JPG drag-and-drop ZXing) QR-beolvasás.
5. **Szerepkör-specifikus Teendők, Automatikus Értesítések, Csapat-chat és Vállalati E-mail:**
   - **Teendők (`TodoView`):** Automatikusan összeállított, priorizált (P1–P4) rendszer-teendőlista 1-kattintásos ugrással + külön privát saját teendők.
   - **Értesítések (`NotificationsView`):** Automatikus munkafolyamat-értesítések (operátori bevétel → betárolás; rendelés/vevő érkezés → összekészítés; kiadásra kész → raktárvezető) és élő piros oldalsáv-jelvények.
   - **Belső Üzenetek (`MessagesView`):** Element-stílusú csoportszobák (`#általános`, `#raktár-logisztika`, menedzsment által létrehozható új szobák) és 1-az-1-ben közvetlen üzenetek (DM).
   - **Vállalati Levelező (`EmailView`):** Összecsukható mappasáv, kereshető **Címzett / CC** mezők, csatolmányok és helyi belső kézbesítés. A Szuper Admin beállíthatja a vállalati domaint és ellenőrizheti az SMTP/IMAP-kapcsolatot. A külső kézbesítés szimulált, az operátorok és targoncások belső levelezést használhatnak.
6. **B2B Partnerek, Rendelések és Többdevizás Számlázás:**
   - Vevők (`CUSTOMER`), beszállítók (`SUPPLIER`) és vegyes partnerek (`BOTH`) kezelése 1-kattintásos **Partner egyenlegközlő és 30/60/90 napos korosítási PDF** riporttal.
   - Elkülönített deviza-főkönyvek (`EUR`, `HUF`, `USD`, egyedi devizák), ország-alapú adótípusok (`HU 27%`, `EU B2B 0%`, `Export 0%`), Adminban szerkeszthető eladói cégprofil címmezőkkel és devizánkénti IBAN-okkal, többsoros számlák, 1-kattintásos **Jóváíró számla (`CN-YYYY-NNNN`)**, automatikus **`OVERDUE`** detektálás + `mailto:` felszólítás, hordozható **számlaarchívum és bizonylattár (`./data/invoices/ÉÉÉÉ/HH/`, `./data/receipts/ÉÉÉÉ/HH/`)**, és A4 **P&L + Készletértékelés PDF**.
7. **Szuper Admin modul-alapú jogosultságkezelés (`Szerepkörök`):** A **18 menüpont** külön modulként szerepel (`AppModule`), amelyeket a `SUPER_ADMIN` szerepkörönként valós időben ki- és bekapcsolhat az adatbázisban tárolt mátrixból.
8. **NAV- és banki integrációs demó:** Helyi NAV Online Számla 3.0 előnézet, számlánkénti iránnyal. Banki számlák és tranzakciók pontos, külön devizás egyenleggel.
9. **CSV-alapú többnyelvűség (810 EN/HU kulcs) és Brutalist monokróm UX:** Egyetlen UTF-8 `translations.csv` fájlból töltődő kétnyelvű felület, kontrasztos világos/sötét monokróm megjelenés (`#FFFFFF`/`#000000`, `arc = 0`), manuális Frissítés gombok nélküli azonnali és 10 mp-es háttér-frissítés, háttérszálú `AsyncUiRunner`, valamint gyorsbillentyűk (`Cmd/Ctrl+1..9/0`, `F5`, `Esc`).

---

## 2. Szerepkörök és alapértelmezett modul-jogosultsági mátrix (RBAC)

A hitelesítés BCrypt jelszó-hash-eléssel történik. Az alkalmazás **7 szerepkört** és **18 menümodult** kezel. A `SUPER_ADMIN` mindig teljes, korlátozatlan hozzáféréssel rendelkezik, és az **Adminisztráció → Szerepkörök** nézetben módosíthatja a szerepköröknél engedélyezhető modulokat. A bérszámfejtéshez Adminisztrátor vagy Szuperadmin szerepkör szükséges.

| Modul (`AppModule`) | Szuperadmin | Adminisztrátor | Raktárvezető | Állomásvezető | Válogató operátor | Válogatósegéd | Targoncás |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Áttekintés és Teendők (`DASHBOARD`, `TODO`) | Igen | Igen | Igen | Igen | Igen | Igen | Igen |
| Válogatás (`SORTING`) | Igen | Igen | Igen | Igen | Igen | Igen | Igen |
| Csomagfelvétel és QR szkenner (`INTAKE`, `SCANNER`) | Igen | Igen | Igen | Igen | Nem | Nem | Nem |
| Betárolás és Összekészítés (`PUTAWAY`, `STAGING`) | Igen | Igen | Igen | Nem | Nem | Nem | Igen |
| Készlet (`INVENTORY`) | Igen | Igen | Igen | Igen | Nem | Nem | Igen |
| Raktárhelyek és Kódtörzsek (`LOCATIONS`, `DICTIONARIES`) | Igen | Igen | Igen | Nem | Nem | Nem | Nem |
| Ügyfelek, Rendelések és Pénzügyek (`CUSTOMERS`, `ORDERS`, `INVOICES`) | Igen | Igen | Igen | Nem | Nem | Nem | Nem |
| Bérszámfejtés (`PAYROLL`) | Igen | Igen | Nem | Nem | Nem | Nem | Nem |
| Értesítések, Üzenetek és E-mail (`NOTIFICATIONS`, `MESSAGES`, `EMAIL`) | Igen | Igen | Igen | Belső mail | Belső mail | Belső mail | Belső mail |
| Adminisztráció (`ADMIN`) | Igen | Igen | Nem | Nem | Nem | Nem | Nem |

A Szerepkörök és az E-mail beállítások adminisztrációs aloldalai a Szuperadminhoz tartoznak. Az Állomásvezető meglévő fiókja megtartja az `OPERATOR` szerepkódot és a kijelölt állomást. A két targoncás munkafolyamaton belüli jogot a feladathoz rendelt személy határozza meg.

---

A kimenő számla a kereshető rendelésből veszi át a tételeket. A kézi bevételek és kiadások tételesen vagy nettó összeggel rögzíthetők, számlánként egy áfatípussal. A partner és a bizonylat a sikeres mentéshez kapcsolódik.

A számlázás és a levelezés névvel és ikonnal ellátott intelligens mappákat biztosít. A mappák felhasználónként mentett szabályok alapján jelenítik meg az aktuális egyezéseket.

## 3. Alkalmazott technológiák sokszínűsége

| Terület | Technológia / Könyvtár | Szerepe a projektben |
|---|---|---|
| Nyelv és futtatás | Java 17 LTS, Apache Maven (NetBeans-kompatibilis) | Java rekordok, szabványos build, futtatható fat `.jar` (`maven-shade-plugin`), platformfüggetlen indító szkriptek (`run-mac.command`, `run-linux.sh`, `run-windows.bat`) |
| Grafikus felület (GUI) | Java Swing + FlatLaf 3.5.4 + `SwingWorker` | Egyedi Brutalist monokróm világos/sötét téma, vektoros ikonok, összecsukható fő- és másodlagos oldalsáv (`SectionNavigation`), kereshető címzettválasztó és háttérszálú I/O (`AsyncUiRunner`) |
| Adatbázis-kezelés | Beágyazott SQLite + `sqlite-jdbc` | **41 táblás 3NF relációs séma**, **23 deklarált index**, külső kulcsok, atomi tranzakciók, automatikus séma-migráció és demo seed |
| Biztonság & RBAC | `jBCrypt` + dinamikus `role_module_permissions` | Sóval ellátott BCrypt jelszó-hash-elés, 7 szerepkör, adatbázis-vezérelt modul-jogosultsági mátrix |
| QR-kód kezelés | Google ZXing (`core` + `javase`) | Valós idejű QR generálás, PNG mentés, QR dekódolás képfájlból, szkenneléses komissiózás és átadás-ellenőrzés |
| Nyomtatás és grafika | Java 2D (`Graphics2D`) + Java Print API (`PrinterJob`, `Printable`) | Vektoros hőcímke- és A4 dokumentum-rajzolás, mentés előtti előnézet, fizikai nyomtatás |
| PDF generálás | OpenPDF 2.0.3 | Hőcímke/A4 4-es PDF címkék, többdevizás A4 PDF számlák/jóváírások, szállítólevelek, partner korosítási egyenlegközlők és P&L riportok |
| Adatvizualizáció | XChart 3.8.8 | Dinamikus monokróm kör- és oszlopdiagramok a készlet súlyeloszlásáról |
| Fájlkezelés & i18n | Saját RFC-4180 CSV motor + UTF-8 BOM CSV export + SQLite `VACUUM INTO` | Külső `translations.csv` (810 kulcs) betöltés/export, Excel-barát CSV riportok, dátum szerint rendezett számlaarchívum és bizonylattár, DB backup/restore |
| Naplózás és tesztelés | SLF4J + Logback, JUnit 5 | Napi rotált naplófájlok, **62 automatizált egység- és integrációs teszt** 24 tesztosztályban |

---

## 4. Projekt ütemterv és mérföldkövek

| Fázis | Tartalom | Státusz |
|---|---|---|
| **Fázis 0–8 (v1.0)** | Alap Maven/SQLite/Swing architektúra, CSV i18n motor, QR termékkód generátor/szkenner, címkenyomtatás, készletkezelés, B2B rendelések, Dashboard XChart, Admin konzol | Kész |
| **Fázis 9–10 (v2.0)** | Brutalist monokróm dizájnrendszer, többdevizás számlázás (elkülönített főkönyvek, ország-alapú ÁFA), kettős EUR/HUF árazás, mély kód-audit és tranzakció-keményítés | Kész |
| **Fázis 11 (v2.1)** | Cégprofil + IBAN-ok, automatikus `OVERDUE` + `mailto:`, tételár-alku, többsoros & jóváíró számlák, bizonylattár, korosítási egyenlegközlő, P&L PDF, kötegelt műveletek, `AsyncUiRunner`, indító szkriptek | Kész |
| **Fázis 12 (v2.2)** | `LocationType`, kijelölt operátori állomások, kötelező mentés előtti előnézet, `FORKLIFT` szerepkör, targoncaműveletek, vállalati E-mail kliens + SMTP/IMAP beállítások, Element-stílusú belső chat | Kész |
| **Fázis 13 (v2.3–v2.4)** | Automatikus értesítési motor, szerepkör-specifikus Teendők központ, targoncáshoz rendelt QR-szkenneléses komissiózás, vevő érkezési riasztás és QR-ellenőrzött raktárvezetői kiadás | Kész |
| **Fázis 14 (v2.5)** | Külön `Betárolás` / `Összekészítés` modulok, élő oldalsáv-jelvények, manuális Frissítés gombok nélküli azonnali/automatikus frissítés, összecsukható e-mail mappasáv + gyorskereséses To/CC választó, Szuper Admin **Szerepkörök és modulok** jogosultsági mátrix | Kész |
| **Válogatástól a kiszállításig** | Rugalmas csapatok, alapanyagigény és napi teljesítmény, helyi webshopimport, ütemezett rendelés és csomagonként ellenőrzött kiadás | Kész |
| **Bérszámfejtési előkészítés** | Hatályos díjak, részlegváltás egy munkanapon belül, jóváhagyott bónusz és napi bruttó tervezet | Kész |

---

## 5. Kockázatok és kezelésük

- **Külső adatbázis-szerver vagy Java telepítés hiánya:** Beágyazott SQLite használatával és a mellékelt platformfüggetlen indító szkriptekkel (`run-mac.command`, `run-linux.sh`, `run-windows.bat`) kiküszöbölve — a futtatható `.jar` indításkor automatikusan létrehozza vagy migrálja a 41 táblás sémát.
- **Fizikai QR szkenner vagy hőnyomtató hiánya:** A rendszer egyszerre támogatja a valódi USB/Bluetooth HID szkennereket és OS nyomtatókat, valamint a képfájlból való QR-olvasást, demó gyors-szkennelést és PDF/PNG exportot.
- **Kódütközés vagy téves csomagrögzítés:** Minden csomag ütközésmentes 2 jegyű hexadecimális végződést (`00`–`FF`) kap, és a kötelező mentés előtti előnézet megakadályozza a hibás csomagok adatbázisba írását.
- **Jogosultsági tévedés vagy adminisztrátori kizáródás:** A `SUPER_ADMIN` szerepkör nem zárható ki egyetlen modulból sem, az `Áttekintés` kezdőoldal pedig minden szerepkörnek aktív marad; minden más modul jogosultsága azonnali SQLite perzisztenciával kapcsolható.
- **UI fagyás nagy PDF export vagy háttérlekérdezések közben:** Az `AsyncUiRunner` (`SwingWorker`) a háttérszálra viszi a 250–300 DPI PDF generálást, CSV exportot, készletszűrést és az oldalsáv-jelvények számlálóit, míg a lusta nézetek első megnyitáskor még kirajzolás előtt frissülnek.
