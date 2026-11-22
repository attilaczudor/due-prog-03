# Projektterv — ReWear WMS (`due-prog-03`)

**Tantárgy:** Programozás III. — Java GUI Projektfeladat  
**Intézmény:** Dunaújvárosi Egyetem  
**Projekt neve:** ReWear WMS — Válogatott Használt Ruha Raktár-, QR Készlet- és Többdevizás Számlázó Rendszer  
**Projektvezető:** Czudor Attila  
**Résztvevők:** Czudor Attila (egyéni projekt — teljes körű tervezés, adatbázis-tervezés, implementáció, tesztelés, dokumentáció)  
**Fejlesztési időszak:** 2026. november  
**Forráskód tárhely:** https://github.com/attilaczudor/due-prog-03  

---

## 1. A projekt rövid leírása és célkitűzése

A használt ruházati nagykereskedelmi és válogató üzemek napi működésében kritikus feladat a beérkező, kiválogatott és különböző csomagolási egységekbe (bála, zsák, kartondoboz, raklap) rendezett textíliák pontos, gyors és hibamentes nyomon követése, valamint a hazai (HUF / 27% ÁFA) és nemzetközi EU/export (EUR/USD / 0% ÁFA) B2B értékesítések és beszállítói költségszámlák elkülönített nyilvántartása.

A **ReWear WMS** egy többplatformos, asztali **Java 17 GUI alkalmazás**, amely erre a valós raktárlogisztikai és pénzügyi feladatra ad teljes körű megoldást:

1. **Strukturált QR termékkód konvenció:** Minden csomag egy beszédes, ember és gép által is azonnal értelmezhető, egyedi QR-kódot kap:
   ```text
   ÉÉÉÉ-HH-NN-ÓÓ-PP-<CÉLCSOPORT>-<TERMÉKTÍPUS>-<MINŐSÉG>-<CSOMAGOLÁS>-<SÚLY>-<HEX2>
   ```
   Példa: `2026-11-12-11-23-L-TSH-A-A-25-3F`  
   *(2026.11.12. 11:23-kor rögzített, Női (`L`) Póló (`TSH`), `A` minőségi osztály, `A` típusú préselt bála, `25 kg`, `3F` véletlen 2 jegyű hexadecimális egyediség-végződés).*
2. **Alkalmazáson belüli kódtörzs-kezelés és kettős EUR/HUF árazás:** Új célcsoportok, terméktípusok, minőségi osztályok és csomagolástípusok közvetlen felvitele (akár csomagfelvétel közben a `+` gombokkal is), EUR/kg és HUF/kg alapárakkal.
3. **Címkenyomtatási előnézet, fizikai nyomtatás, PDF/PNG és csoportos műveletek:** Beépített, nagyítható előnézet (`Hőcímke 100×150 mm`, `80×100 mm`, `A6`, `A4 4-es ív`), rendszer-nyomtatók támogatása (`PrinterJob`), 300-DPI OpenPDF címke export, PNG mentés, valamint többsoros kijelöléses atomi kötegelt áthelyezés és csoportos címkenyomtatás.
4. **QR szkenner állomás:** Valós USB/Bluetooth HID billentyűzet-emulációs szkennerek támogatása + QR-kód beolvasása képfájlból (PNG/JPG drag-and-drop ZXing dekóderrel) + bemutató gyors-szkennelés.
5. **B2B Partnerek (Vevők & Beszállítók), Rendelések és Többdevizás Számlázás:**
   - Vevők (`CUSTOMER`), beszállítók (`SUPPLIER`) és vegyes partnerek (`BOTH`) kezelése országkóddal és preferált devizával, 1-kattintásos **Partner egyenlegközlő és 30/60/90 napos korosítási PDF** riporttal.
   - Rendeléskezelés egyedi tételenként kialkudható árral (`Edit Item Price`), atomi állapotátmenetekkel és A4 PDF szállítólevéllel.
   - Külön kezelt deviza-főkönyvek (`EUR`, `HUF`, `USD`, egyedi devizák — átváltás nélkül), konfigurálható ország-alapú adótípusok (`HU 27%`, `EU B2B 0%`, `Export 0%`), szerkeszthető eladói cégprofil devizánkénti IBAN bankszámlaszámokkal, többsoros kézi kimenő/bejövő számlák, 1-kattintásos **Jóváíró számla (`CN-YYYY-NNNN`)**, automatikus lejárt számla detektálás (`OVERDUE`) + lokalizált `mailto:` fizetési felszólítás, hordozható **Bizonylattár (`./data/receipts/`)**, és nyomtatható A4 **Eredménykimutatás (P&L) + Készletértékelés PDF**.
6. **CSV-alapú többnyelvűség (EN / HU + jövőbeli nyelvek):** Mind a 400 felületi kulcs egyetlen UTF-8 `translations.csv` fájlból töltődik be; új nyelv hozzáadásához elegendő egy új oszlop felvétele újrafordítás nélkül.
7. **Brutalist monokróm világos és sötét téma + reszponzív asztali UX:** Kontrasztos ipari terminál megjelenés (`#FFFFFF` / `#000000`, `arc = 0`, vektoros SVG ikonok és logó, monospaced tipográfia), lusta betöltésű nézetek, EDT-t nem blokkoló `SwingWorker` (`AsyncUiRunner`), és billentyűparancsok (`Cmd/Ctrl+1..9/0`, `F5`/`Cmd/Ctrl+R`, `Esc`).

---

## 2. Szerepkörök és jogosultsági mátrix (RBAC)

A hitelesítés BCrypt jelszó-hash-eléssel történik. Az alkalmazás három szerepkört kezel:

| Funkció | `ADMIN` | `MANAGER` | `OPERATOR` |
|---|:---:|:---:|:---:|
| Bejelentkezés, nyelvválasztás (EN/HU), világos/sötét téma, profil szerkesztés | Igen | Igen | Igen |
| Dashboard (KPI-k, kettős EUR/HUF készletértékelés, XChart grafikonok) | Igen | Igen | Igen |
| Csomagfelvétel, élő QR-kód generálás, címkenyomtatás | Igen | Igen | Igen |
| QR szkenner állomás (ellenőrzés, áthelyezés, leltár, újranyomtatás) | Igen | Igen | Igen |
| Készlet böngészése, szűrése, kötegelt áthelyezés, csoportos nyomtatás | Igen | Igen | Igen |
| Raktárhelyek és kódtörzsek kezelése (EUR/HUF alapárakkal) | Igen | Igen | Nem |
| B2B Partnerek (Vevők & Beszállítók), korosítási egyenlegközlő PDF | Igen | Igen | Nem |
| Rendelések, egyedi tételárazás, QR-szkenneléses kiadás, PDF szállítólevél | Igen | Igen | Nem |
| Többdevizás számlák, jóváíró számlák, bizonylattár, P&L PDF, számlabeállítások | Igen | Igen | Nem |
| Készlet, számla és audit napló CSV exportálása | Igen | Igen | Nem |
| Felhasználókezelés, teljes audit napló, DB mentés/visszaállítás, i18n CSV, Rendszerdiagnosztika | Igen | Nem | Nem |

---

## 3. Alkalmazott technológiák sokszínűsége

| Terület | Technológia / Könyvtár | Szerepe a projektben |
|---|---|---|
| Nyelv és futtatás | Java 17 LTS, Apache Maven (NetBeans-kompatibilis) | Java rekordok, szabványos build, futtatható fat `.jar` (`maven-shade-plugin`), platformfüggetlen indító szkriptek (`run-mac.command`, `run-linux.sh`, `run-windows.bat`) |
| Grafikus felület (GUI) | Java Swing + FlatLaf 3.5.4 + `SwingWorker` | Egyedi Brutalist monokróm világos/sötét téma, vektoros SVG ikonok, háttérszálú I/O (`AsyncUiRunner`), lusta nézetbetöltés |
| Adatbázis-kezelés | Beágyazott SQLite + `sqlite-jdbc` | 17 táblás 3NF relációs séma, 14 index, külső kulcsok, atomi tranzakciók, nulla konfigurációs indulás |
| Biztonság | `jBCrypt` | Sóval ellátott BCrypt jelszó-hash-elés és ellenőrzés |
| QR-kód kezelés | Google ZXing (`core` + `javase`) | Valós idejű QR generálás, PNG mentés, QR dekódolás képfájlból |
| Nyomtatás és grafika | Java 2D (`Graphics2D`) + Java Print API (`PrinterJob`, `Printable`) | Vektoros hőcímke- és A4 dokumentum-rajzolás, nagyítható előnézet, fizikai nyomtatás |
| PDF generálás | OpenPDF 2.0.3 | Hőcímke/A4 4-es PDF címkék, többdevizás A4 PDF számlák/jóváírások, szállítólevelek, partner korosítási egyenlegközlők és P&L riportok |
| Adatvizualizáció | XChart 3.8.8 | Dinamikus monokróm kör- és oszlopdiagramok a készlet súlyeloszlásáról |
| Fájlkezelés & i18n | Saját RFC-4180 CSV motor + UTF-8 BOM CSV export + SQLite `VACUUM INTO` | Külső `translations.csv` betöltés/export, Excel-barát CSV riportok, hordozható bizonylattár (`./data/receipts/`), DB backup/restore |
| Naplózás és tesztelés | SLF4J + Logback, JUnit 5 | Napi rotált naplófájlok, 42 automatizált egység- és integrációs teszt (0 `-Xlint:all,-serial` figyelmeztetés) |

---

## 4. Projekt ütemterv és mérföldkövek

| Fázis | Tartalom | Státusz |
|---|---|---|
| **Fázis 0** | Maven projektstruktúra, függőségek, Logback, GitHub repó | Kész |
| **Fázis 1** | CSV-alapú többnyelvű i18n motor + QR termékkód generátor/elemző + tesztek | Kész |
| **Fázis 2** | Normalizált 3NF SQLite séma, modell osztályok, JDBC DAO-k, demo adatok + integrációs tesztek | Kész |
| **Fázis 3** | BCrypt belépés, RBAC munkamenet, beállítások mentése, főablak keretrendszer | Kész |
| **Fázis 4** | Csomagfelvétel (`+` gyors-hozzáadással), élő ZXing QR, címkenyomtatási előnézet, nyomtatás, PDF/PNG | Kész |
| **Fázis 5** | QR szkenner állomás (HID + képfájl drag-and-drop + demó szkennelés), `OrderService` | Kész |
| **Fázis 6** | Szűrhető készletböngésző, csoportos A4 nyomtatás, raktárhelyek, kódtörzsek, CSV export | Kész |
| **Fázis 7** | Brutalist monokróm világos/sötét felület, B2B ügyfelek & beszállítók, rendeléskezelés, PDF szállítólevél | Kész |
| **Fázis 8** | Dashboard KPI-k + XChart grafikonok, Admin konzol (felhasználók, audit, DB backup/restore, i18n CSV) | Kész |
| **Fázis 9 (v2.0)** | Többdevizás számlázás (elkülönített deviza-főkönyvek), konfigurálható adótípusok (`HU 27%` / `EU 0%`), kettős EUR/HUF árazás, hordozható beállítások | Kész |
| **Fázis 10 (Audit)** | Mély kód-audit (tranzakció-biztonság, N+1 megszüntetése, `Locale.ROOT` keményítés, lusta nézetbetöltés, 0 fordítási figyelmeztetés, CSV i18n integritás-teszt) | Kész |
| **Fázis 11 (v2.1)** | Cégprofil + devizánkénti IBAN-ok, automatikus `OVERDUE` + fizetési felszólítás, tételár-szerkesztés, többsoros számlák, jóváíró számlák, bizonylattár, partner korosítási PDF, P&L + készletértékelés PDF, kötegelt műveletek, `AsyncUiRunner`, indító szkriptek & rendszerdiagnosztika | Kész |

---

## 5. Kockázatok és kezelésük

- **Külső adatbázis-szerver vagy Java telepítés hiánya:** Beágyazott SQLite használatával és a mellékelt platformfüggetlen indító szkriptekkel (`run-mac.command`, `run-linux.sh`, `run-windows.bat`) kiküszöbölve — a futtatható `.jar` indításkor automatikusan létrehozza a sémát és a tesztadatokat.
- **Fizikai QR szkenner vagy hőnyomtató hiánya:** A rendszer egyszerre támogatja a valódi USB/Bluetooth HID szkennereket és OS nyomtatókat, valamint a képfájlból való QR-olvasást, demó gyors-szkennelést és PDF/PNG exportot.
- **Kódütközés azonos percen belüli rögzítésnél:** Minden csomag véletlen 2 jegyű hexadecimális végződést (`00`–`FF`) kap, adatbázis-szintű `UNIQUE` ellenőrzéssel és ütközés esetén automatikus újragenerálással.
- **UI fagyás nagy PDF export vagy adatbázis-szűrés közben:** Az `AsyncUiRunner` (`SwingWorker`) a háttérszálra viszi a 250–300 DPI PDF generálást, CSV exportot és készletszűrést, miközben a `runLatest` generáció-követéssel kiszűri az elavult lekérdezési válaszokat.
