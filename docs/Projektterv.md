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
9. **CSV-alapú többnyelvűség (1112 EN/HU kulcs) és Brutalist monokróm UX:** Egyetlen UTF-8 `translations.csv` fájlból töltődő kétnyelvű felület, kontrasztos világos/sötét monokróm megjelenés (`#FFFFFF`/`#000000`, `arc = 0`), manuális Frissítés gombok nélküli azonnali és 10 mp-es háttér-frissítés, háttérszálú `AsyncUiRunner`, valamint gyorsbillentyűk (`Cmd/Ctrl+1..9/0`, `F5`, `Esc`).

10. **Nyersanyag-ellátás a begyűjtéstől a válogatóig:** A napi járatok az ország különböző gyűjtési területeiről
    hozzák be a használt ruhát; minden begyűjtés külön tételként kerül be az anyag és a terület megnevezésével.
    A targoncás betárolja egy polcra, és a tétel csak ettől kezdve igényelhető. A válogató a **Nyersanyag-tételek**
    nézetben tételt választ, nem súlyt ad meg: azt, hogy mennyit kap, a targoncás átadáskor rögzíti.
11. **Műszakbeosztás naptárral:** Önálló **Beosztás** menüpont hónap-, hét- és napnézettel. A beosztást a
    műszakvezető, a termelési vezető, a bérszámfejtés és a vezetőség írja; minden más dolgozó a saját napjait
    látja. A szabadnap is beosztás, így a pihenőnap tervezett. A demó munkaterület egy hónapnyi, hetente forgó
    műszakrendet tölt fel a tényleges dolgozókkal.
12. **Leltár:** Önálló **Leltár** menüpont. Egy leltár egyszerre fut, és egy polcra, egy zónára vagy a
    teljes raktárra szól. A számolás alatt a készlet nem változik; a lezárás egyetlen tranzakcióban helyezi
    át a rossz polcon talált csomagokat, javítja a mért súlyokat és leírja a sérülteket, mindegyikhez
    készletmozgást rögzítve. A sérültnek talált csomag **karanténba** kerül: sem eladni, sem készletértékbe
    számítani nem lehet, de leírva sincs, amíg valaki nem dönt — a Leltár nézet karantén szakaszában egy
    kattintással visszavehető készletre vagy véglegesen leírható, mindkettő nyomot hagy a mozgásnaplóban.
    A meg nem talált tételek hiányzóként jelennek meg, de a rendszer soha nem írja le őket magától.
13. **Első indítás saját céggel vagy demó munkaterülettel:** Egy új telepítés üresen indul, és az első indításkor a tulajdonos választ: saját cég (egyetlen, általa megadott szuper-adminisztrátori fiók, nyilvános vagy gyakori jelszavak tiltásával, opcionális szokásos ruházati kódtörzsekkel és kezdő polcrenddel), demó munkaterület (mintaraktár a nyilvánosan ismert demó belépőkkel) vagy korábbi mentés visszaállítása. Az alkalmazás indításkor már használatban lévő adatbázist nem tölt fel mintaadattal, és a demó belépő gombok csak demó munkaterületen jelennek meg.
14. **Teljes mentés és visszaállítás:** Az Adminban készített `.zip` az SQLite-pillanatképet és a számla-, bizonylat- és levélcsatolmány-mappákat is tartalmazza SHA-256 ellenőrző jegyzékkel. A visszaállítás először mindent átmeneti mappában ellenőriz, a régi mappákat félreteszi (nem törli), az adatbázist utoljára cseréli, hiba esetén visszavonja a mozgatásokat. A levélcsatolmányok saját, dátum szerinti mappában élnek, típus- és fájlfejléc-ellenőrzéssel.
15. **Számla-életciklus, előzmények és szedési lista:** A számla állapotváltásait egyetlen szabályrendszer korlátozza (kiállított számla nem lesz újra piszkozat, a sztornó csak jóváíró számlával lehetséges, a jóváíró számla végleges), minden váltás naplózott a felhasználóval és az idővel, az előzmények a számla részletei között láthatók, a műveletekhez az `INVOICES` modul jogosultsága szükséges a szolgáltatásban is. Az Összekészítés nézet **Szedési lista** gombja bejárási sorrendű (zóna, sor, polc), haladásjelzős, nyomtatható listát ad egy vagy több rendelésre.
16. **Egységes üzleti idő és ellenőrzött build:** Minden üzleti dátum a raktár budapesti idejét követi a gép időzónájától függetlenül, egy építési őrteszt tiltja a közvetlen rendszeróra-hívásokat. A projekt Maven wrapperrel (`./mvnw clean verify`) épül Maven-telepítés nélkül.

---

## 2. Szerepkörök és alapértelmezett modul-jogosultsági mátrix (RBAC)

A hitelesítés BCrypt jelszó-hash-eléssel történik. Az alkalmazás **11 szerepkört** és **20 menümodult** kezel. A `SUPER_ADMIN` mindig teljes, korlátozatlan hozzáféréssel rendelkezik, és az **Adminisztráció → Szerepkörök** nézetben módosíthatja a szerepköröknél engedélyezhető modulokat. A bérszámfejtéshez Adminisztrátor vagy Szuperadmin szerepkör szükséges.

| Modul (`AppModule`) | Szuperadmin | Adminisztrátor | Raktárvezető | Termelési vezető | Műszakvezető | Bérszámfejtés/HR | Állomásvezető | Alapanyag-targoncás | Kiszállítási targoncás | Válogató | Válogatósegéd |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Áttekintés és Teendők (`DASHBOARD`, `TODO`) | Igen | Igen | Igen | Igen | Igen | Igen | Igen | Igen | Igen | Igen | Igen |
| Beosztás (`ROTA`) | Igen | Igen | Igen | Szerkeszt | Szerkeszt | Szerkeszt | Saját | Saját | Saját | Saját | Saját |
| Válogatás (`SORTING`) | Igen | Igen | Igen | Igen | Igen | Nem | Igen | Igen | Nem | Igen | Igen |
| Csomagfelvétel és QR szkenner (`INTAKE`, `SCANNER`) | Igen | Igen | Igen | Igen | Igen | Nem | Igen | Nem | Nem | Nem | Nem |
| Betárolás (`PUTAWAY`) | Igen | Igen | Igen | Igen | Igen | Nem | Nem | Igen | Nem | Nem | Nem |
| Összekészítés (`STAGING`) | Igen | Igen | Igen | Igen | Igen | Nem | Nem | Nem | Igen | Nem | Nem |
| Készlet (`INVENTORY`) | Igen | Igen | Igen | Igen | Igen | Nem | Igen | Igen | Igen | Nem | Nem |
| Raktárhelyek és Kódtörzsek (`LOCATIONS`, `DICTIONARIES`) | Igen | Igen | Igen | Igen | Nem | Nem | Nem | Nem | Nem | Nem | Nem |
| Ügyfelek, Rendelések és Pénzügyek (`CUSTOMERS`, `ORDERS`, `INVOICES`) | Igen | Igen | Igen | Nem | Nem | Nem | Nem | Nem | Nem | Nem | Nem |
| Bérszámfejtés (`PAYROLL`) | Igen | Igen | Nem | Nem | Nem | Igen | Nem | Nem | Nem | Nem | Nem |
| Értesítések, Üzenetek és E-mail | Igen | Igen | Igen | Igen | Igen | Igen | Belső mail | Belső mail | Belső mail | Belső mail | Belső mail |
| Adminisztráció (`ADMIN`) | Igen | Igen | Nem | Nem | Nem | Nem | Nem | Nem | Nem | Nem | Nem |

**Vállalati hierarchia.** A rendszergazda fölött nincs szerepkör; alatta az adminisztrátor fogja össze az üzletet.
A raktárvezető a készletért, a partnerekért, a rendelésekért és a pénzügyekért felel. Vele egy szinten a
**termelési vezető** viszi a válogatóüzemet, alatta a **műszakvezető** egy műszakot és annak beosztását.
A **bérszámfejtés/HR** a béreket, a beosztást és a dolgozói adatokat látja, raktári műveleteket nem.
A targoncás munka két szerepkörre vált szét: az **alapanyag-targoncás** szolgálja ki a válogatót (betárolja a
beérkező nyersanyagot, kiadja a válogatóknak, és a kész, címkézett csomagokat az állomásról a polcra viszi),
a **kiszállítási targoncás** pedig a vevői rendeléseket (összekészítés a kiadó zónába, majd a jármű rakodása).


A számla állapotváltása és a jóváíró számla kiállítása a szolgáltatásban is csak aktív felhasználónak, az `INVOICES` modult használó szerepkörrel engedélyezett, így a mátrix módosítása azonnal érvényes. A Szerepkörök és az E-mail beállítások adminisztrációs aloldalai a Szuperadminhoz tartoznak. Az Állomásvezető meglévő fiókja megtartja az `OPERATOR` szerepkódot és a kijelölt állomást. A két targoncás munkafolyamaton belüli jogot a feladathoz rendelt személy határozza meg.

---

A kimenő számla a kereshető rendelésből veszi át a tételeket. A kézi bevételek és kiadások tételesen vagy nettó összeggel rögzíthetők, számlánként egy áfatípussal. A partner és a bizonylat a sikeres mentéshez kapcsolódik.

A számlázás és a levelezés névvel és ikonnal ellátott intelligens mappákat biztosít. A mappák felhasználónként mentett szabályok alapján jelenítik meg az aktuális egyezéseket.

## 3. Alkalmazott technológiák sokszínűsége

| Terület | Technológia / Könyvtár | Szerepe a projektben |
|---|---|---|
| Nyelv és futtatás | Java 17 LTS, Apache Maven (NetBeans-kompatibilis) | Java rekordok, szabványos build, futtatható fat `.jar` (`maven-shade-plugin`), platformfüggetlen indító szkriptek (`run-mac.command`, `run-linux.sh`, `run-windows.bat`) |
| Grafikus felület (GUI) | Java Swing + FlatLaf 3.5.4 + `SwingWorker` | Egyedi Brutalist monokróm világos/sötét téma, vektoros ikonok, összecsukható fő- és másodlagos oldalsáv (`SectionNavigation`), kereshető címzettválasztó és háttérszálú I/O (`AsyncUiRunner`) |
| Adatbázis-kezelés | Beágyazott SQLite + `sqlite-jdbc` | **60 táblás 3NF relációs séma**, **38 deklarált index**, külső kulcsok, atomi tranzakciók, automatikus séma-migráció és explicit első indítási választás (saját cég vagy demó) |
| Biztonság & RBAC | `jBCrypt` + dinamikus `role_module_permissions` | Sóval ellátott BCrypt jelszó-hash-elés, 11 szerepkör, adatbázis-vezérelt modul-jogosultsági mátrix |
| QR-kód kezelés | Google ZXing (`core` + `javase`) | Valós idejű QR generálás, PNG mentés, QR dekódolás képfájlból, szkenneléses komissiózás és átadás-ellenőrzés |
| Nyomtatás és grafika | Java 2D (`Graphics2D`) + Java Print API (`PrinterJob`, `Printable`) | Vektoros hőcímke- és A4 dokumentum-rajzolás, mentés előtti előnézet, fizikai nyomtatás |
| PDF generálás | OpenPDF 2.0.3 | Hőcímke/A4 4-es PDF címkék, többdevizás A4 PDF számlák/jóváírások, szállítólevelek, partner korosítási egyenlegközlők és P&L riportok |
| Adatvizualizáció | XChart 3.8.8 | Dinamikus monokróm kör- és oszlopdiagramok a készlet súlyeloszlásáról |
| Fájlkezelés & i18n | Saját RFC-4180 CSV motor + UTF-8 BOM CSV export + SQLite `VACUUM INTO` | Külső `translations.csv` (1112 kulcs) betöltés/export, Excel-barát CSV riportok, dátum szerint rendezett számlaarchívum, bizonylattár és levélcsatolmány-tár, teljes (`.zip`) és csak-adatbázis mentés/visszaállítás |
| Naplózás és tesztelés | SLF4J + Logback, JUnit 5 | Napi rotált naplófájlok, **365 automatizált egység- és integrációs teszt** 64 tesztosztályban, Maven wrapper (`./mvnw`) |

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
| **Ellenőrzés és megbízhatóság (2026. 11. 25.)** | Teljes kód- és hibaellenőrzés, reprodukálható build (Maven wrapper), biztonságosabb visszaállítás (integritás-ellenőrzés, atomi csere), levélcsatolmány-tár, egységes budapesti üzleti idő őrtesztettel | Kész |
| **Üzemszerű használat előkészítése (2026. 11. 25.)** | Teljes `.zip` mentés és visszaállítás, explicit első indítás (saját cég vagy demó), a beégetett alapértelmezett fiókok és mintaadat-utántöltés megszüntetése | Kész |
| **Pénzügyi és raktári finomítás (2026. 11. 25.)** | Számla-életciklus és előzmények, jóváíró számla szerkezeti kapcsolata, pénzügyi műveletek jogosultsága, bejárási sorrendű szedési lista | Kész |

---

## 5. Kockázatok és kezelésük

- **Külső adatbázis-szerver vagy Java telepítés hiánya:** Beágyazott SQLite használatával és a mellékelt platformfüggetlen indító szkriptekkel (`run-mac.command`, `run-linux.sh`, `run-windows.bat`) kiküszöbölve — a futtatható `.jar` indításkor automatikusan létrehozza vagy migrálja a 60 táblás sémát.
- **Fizikai QR szkenner vagy hőnyomtató hiánya:** A rendszer egyszerre támogatja a valódi USB/Bluetooth HID szkennereket és OS nyomtatókat, valamint a képfájlból való QR-olvasást, demó gyors-szkennelést és PDF/PNG exportot.
- **Kódütközés vagy téves csomagrögzítés:** Minden csomag ütközésmentes 2 jegyű hexadecimális végződést (`00`–`FF`) kap, és a kötelező mentés előtti előnézet megakadályozza a hibás csomagok adatbázisba írását.
- **Jogosultsági tévedés vagy adminisztrátori kizáródás:** A `SUPER_ADMIN` szerepkör nem zárható ki egyetlen modulból sem, az `Áttekintés` kezdőoldal pedig minden szerepkörnek aktív marad; minden más modul jogosultsága azonnali SQLite perzisztenciával kapcsolható.
- **UI fagyás nagy PDF export vagy háttérlekérdezések közben:** Az `AsyncUiRunner` (`SwingWorker`) a háttérszálra viszi a 250–300 DPI PDF generálást, CSV exportot, készletszűrést és az oldalsáv-jelvények számlálóit, míg a lusta nézetek első megnyitáskor még kirajzolás előtt frissülnek.
- **Adatvesztés visszaállításkor vagy költözéskor:** A teljes mentés a dokumentumokat is tartalmazza, a visszaállítás előbb mindent ellenőriz, a régi mappákat félreteszi, hiba esetén visszavon; így egy sérült vagy meghamisított mentés nem ír felül élő adatot.
- **Alapértelmezett belépők éles adatbázison:** Üres telepítés nem hoz létre fiókot magától. A saját cég fiókját a tulajdonos adja meg, nyilvános jelszó nem fogadható el, és a program indításkor sem építi újra a demó fiókokat, sem tölt fel mintaadatot használatban lévő adatbázisba.
- **Időzóna-eltérésből adódó téves napok:** Minden üzleti dátum egy budapesti órából készül; az automatikus teszt a gép időzónáját Los Angelesre állítja, és az építés megbukik közvetlen rendszeróra-hívásra.
- **Pénzügyi bizonylat utólagos módosítása vagy jogosulatlan művelet:** A kiállított számla nem szerkeszthető vissza, a javítás jóváíró számlával történik, minden váltás naplózott, a műveleteket a szolgáltatás a szerepkör-mátrix alapján engedi.

### Ismert korlátok
- A mentések nincsenek titkosítva, tárolásukkor ugyanolyan védelem szükséges, mint az élő adatmappánál; a visszaállítás után maradó `pre-restore-*` másolatokat kézzel kell törölni.
- A NAV, a banki, a webshop és a külső e-mail kapcsolat demó/szimuláció. A pénzösszegek pontos tizedes szövegként tárolódnak, így a tárolás nem veszít pontosságot.
- A demó munkaterületből nem lehet saját cég munkaterületet csinálni, csak mentés visszaállításával. A számlaszám a piszkozat létrehozásakor készül, nem a kiállításkor. Egy nagyon régi (partnertípus nélküli) adatbázis frissítése még mintapartnereket vehet fel.
- A szedési lista bejárási sorrendje növekvő zóna–sor–polc sorrend, az épület fizikai rajzát nem ismeri. (A leltárfolyamat, a sérült és karanténos áru kezelése és az újramérés azóta elkészült.)
- Az osztályonkénti érték a ruhatípus árából készül, a minőségi osztály ára csak tartalék. A demóban minden ruhatípusnak van ára, így egy kilogramm krém ugyanannyit ér, mint egy kilogramm rongy, a vezérlőpult összetétel-sora pedig érték szerint rendezve is súly szerinti sorrendet ad. Osztályonkénti ár bevezetése árazási döntés, amely a hozam- és a készletértékelő képernyőt szándékosan eltérítené egymástól.
- A változásnapló rögzíti, mi változott, és melyik gépen, de nem rögzíti, mire változott: a visszajátszónak a sort magát kell elolvasnia, így a napló egy kiszolgáló melletti szinkronhoz jó, offline adatbázisba való visszajátszáshoz nem. Ütközésfeloldási szabály szándékosan nincs: az ütközés felismerése kész, de hogy két gép közül melyiké az igazság, táblánként más, és üzleti döntés.
- A teendőlista szerepkörre szól, személyre nem: három műszakvezető esetén a termelési vezető minden nyitott átadási ügyet lát, nem csak a saját műszakáét.
