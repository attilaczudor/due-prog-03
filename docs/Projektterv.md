# Projektterv — ReWear WMS (`due-prog-03`)

**Tantárgy:** Programozás III. — Java GUI Projektfeladat  
**Intézmény:** Dunaújvárosi Egyetem  
**Projekt neve:** ReWear WMS — Válogatott Használt Ruha Raktár- és QR Készletkezelő Rendszer  
**Projektvezető:** Czudor Attila  
**Résztvevők:** Czudor Attila (egyéni projekt — teljes körű tervezés, adatbázis-tervezés, implementáció, tesztelés, dokumentáció)  
**Fejlesztési időszak:** 2026. november  
**Forráskód tárhely:** https://github.com/attilaczudor/due-prog-03  

---

## 1. A projekt rövid leírása és célkitűzése

A használt ruházati nagykereskedelmi és válogató üzemek napi működésében kritikus feladat a beérkező, kiválogatott és különböző csomagolási egységekbe (bála, zsák, kartondoboz, raklap) rendezett textíliák pontos, gyors és hibamentes nyomon követése. A kézi nyilvántartás vagy egyszerű táblázatkezelés nem biztosít egyedi azonosítást, valós idejű készletáttekintést, és megnehezíti a raktári mozgások, valamint a B2B kiszállítások visszakövetését.

A **ReWear WMS** egy többplatformos, asztali **Java GUI alkalmazás**, amely erre a valós raktárlogisztikai problémára ad komplex megoldást:

1. **Strukturált QR termékkód konvenció:** Minden csomag (bála/zsák/doboz) egy beszédes, ember és gép által is azonnal értelmezhető, egyedi QR-kódot kap:
   ```text
   ÉÉÉÉ-HH-NN-ÓÓ-PP-<CÉLCSOPORT>-<TERMÉKTÍPUS>-<MINŐSÉG>-<CSOMAGOLÁS>-<SÚLY>-<HEX2>
   ```
   Példa: `2026-11-12-11-23-L-TSH-A-A-25-3F`  
   *(2026.11.12. 11:23-kor rögzített, Női (`L`) Póló (`TSH`), `A` minőségi osztály, `A` típusú préselt bála, `25 kg`, `3F` véletlen 2 jegyű hexadecimális egyediség-végződés).*
2. **Alkalmazáson belüli kódtörzs-kezelés:** A felhasználó közvetlenül a felületen létrehozhat új célcsoportokat, terméktípusokat, minőségi osztályokat és csomagolástípusokat (akár csomagfelvétel közben a `+` gombokkal is), és az alkalmazás automatikusan ezekből építi fel a konvenciónak megfelelő QR-kódot.
3. **Címkenyomtatási előnézet, fizikai nyomtatás és PDF/PNG export:** Beépített, nagyítható nyomtatási előnézet (`Hőcímke 100×150 mm`, `80×100 mm`, `A6`, `A4 4-es ív`), rendszer-nyomtatók közvetlen támogatása (`PrinterJob`), valamint PDF és PNG mentés.
4. **QR szkenner állomás:** Valós USB/Bluetooth HID billentyűzet-emulációs szkennerek támogatása + QR-kód beolvasása képfájlból (PNG/JPG drag-and-drop ZXing dekóderrel) + bemutató gyors-szkennelés.
5. **CSV-alapú többnyelvűség (EN / HU + jövőbeli nyelvek):** A teljes felhasználói felület szövegei egyetlen UTF-8 `translations.csv` fájlból töltődnek be; új nyelv hozzáadásához elegendő egy új oszlop felvétele újrafordítás nélkül.
6. **Brutalist monokróm világos és sötét téma:** Kontrasztos, letisztult ipari terminál megjelenés (`#FFFFFF` / `#000000`, éles 1–2 px elválasztóvonalak, monospaced tipográfia), élő témaváltással.

---

## 2. Szerepkörök és jogosultsági mátrix (RBAC)

A hitelesítés BCrypt jelszó-hash-eléssel történik. Az alkalmazás három szerepkört kezel:

| Funkció | `ADMIN` | `MANAGER` | `OPERATOR` |
|---|:---:|:---:|:---:|
| Bejelentkezés, nyelvválasztás (EN/HU), világos/sötét téma | Igen | Igen | Igen |
| Áttekintő dashboard és XChart grafikonok | Igen | Igen | Igen |
| Csomagfelvétel, élő QR-kód generálás, címkenyomtatás | Igen | Igen | Igen |
| QR szkenner állomás (ellenőrzés, áthelyezés, leltár, újranyomtatás) | Igen | Igen | Igen |
| Készlet böngészése, szűrése, csoportos nyomtatási előnézet | Igen | Igen | Igen |
| Raktárhelyek és kódtörzsek kezelése | Igen | Igen | Nem |
| B2B ügyfelek, rendelések, QR-szkenneléses kiadás, PDF szállítólevél | Igen | Igen | Nem |
| Készlet CSV exportálása | Igen | Igen | Nem |
| Felhasználó- és jogosultságkezelés, teljes audit napló, DB mentés/visszaállítás, külső fordítási CSV betöltése | Igen | Nem | Nem |

---

## 3. Alkalmazott technológiák sokszínűsége

| Terület | Technológia / Könyvtár | Szerepe a projektben |
|---|---|---|
| Nyelv és futtatás | Java 17 LTS, Apache Maven (NetBeans-kompatibilis) | Modern Java rekordok, szabványos build és futtatható fat `.jar` (`maven-shade-plugin`) |
| Grafikus felület (GUI) | Java Swing + FlatLaf 3.5.4 | Egyedi Brutalist monokróm világos és sötét téma, reszponzív nézetek |
| Adatbázis-kezelés | Beágyazott SQLite + `sqlite-jdbc` | 12 táblás 3NF relációs séma, külső kulcsok, tranzakciók, nulla konfigurációs indulás |
| Biztonság | `jBCrypt` | Sóval ellátott BCrypt jelszó-hash-elés és ellenőrzés |
| QR-kód kezelés | Google ZXing (`core` + `javase`) | Valós idejű QR generálás, PNG mentés, QR dekódolás képfájlból |
| Nyomtatás és grafika | Java 2D (`Graphics2D`) + Java Print API (`PrinterJob`, `Printable`) | Vektoros hőcímke-rajzolás, nagyítható előnézet, fizikai nyomtatás |
| PDF generálás | OpenPDF 2.0.3 | Hőcímke/A4 4-es PDF címkék és B2B A4 PDF szállítólevelek (teljes UTF-8 magyar támogatással) |
| Adatvizualizáció | XChart 3.8.8 | Dinamikus kör- és oszlopdiagramok a készlet súlyeloszlásáról |
| Fájlkezelés & i18n | Saját RFC-4180 CSV motor + UTF-8 BOM CSV export + SQLite `VACUUM INTO` | Külső `translations.csv` betöltés/export, Excel-barát CSV riportok, DB biztonsági mentés/visszaállítás |
| Naplózás és tesztelés | SLF4J + Logback, JUnit 5 | Napi rotált naplófájlok, 24 automatizált egység- és integrációs teszt |

---

## 4. Projekt ütemterv és mérföldkövek

| Fázis | Tartalom | Státusz |
|---|---|---|
| **Fázis 0** | Maven projektstruktúra, függőségek, Logback, GitHub repó | Kész |
| **Fázis 1** | CSV-alapú többnyelvű i18n motor + QR termékkód generátor/elemző + tesztek | Kész |
| **Fázis 2** | 12 táblás 3NF SQLite séma, modell osztályok, 10 JDBC DAO, demo adatok + integrációs tesztek | Kész |
| **Fázis 3** | BCrypt belépés, RBAC munkamenet, beállítások mentése, főablak keretrendszer | Kész |
| **Fázis 4** | Csomagfelvétel (`+` gyors-hozzáadással), élő ZXing QR, címkenyomtatási előnézet, nyomtatás, PDF/PNG | Kész |
| **Fázis 5** | QR szkenner állomás (HID + képfájl drag-and-drop + demó szkennelés), `OrderService` | Kész |
| **Fázis 6** | Szűrhető készletböngésző, csoportos A4 nyomtatás, raktárhelyek, kódtörzsek, CSV export | Kész |
| **Fázis 7** | Brutalist monokróm világos/sötét felület, B2B ügyfelek, rendeléskezelés, PDF szállítólevél | Kész |
| **Fázis 8** | Dashboard KPI-k + XChart grafikonok, Admin konzol (felhasználók, audit, DB backup/restore, i18n CSV) | Kész |
| **Fázis 9** | Végleges tesztelés, önálló futtatható `.jar`, egyetemi dokumentációk (`Projektterv`, `Adatbázisterv`, `Programterv`) | Kész |
| **Fázis 10** | 10 perces prezentációs vázlat, demó QR anyagok, végső `.zip` beadási csomag | Következő |

---

## 5. Kockázatok és kezelésük

- **Külső adatbázis-szerver hiánya értékeléskor:** Beágyazott SQLite használatával kiküszöbölve — a futtatható `.jar` indításkor automatikusan létrehozza a sémát és a tesztadatokat.
- **Fizikai QR szkenner vagy hőnyomtató hiánya a bemutatón:** A rendszer egyszerre támogatja a valódi USB/Bluetooth HID szkennereket és OS nyomtatókat, valamint a képfájlból való QR-olvasást, demó gyors-szkennelést és PDF/PNG exportot.
- **Kódütközés azonos percen belüli rögzítésnél:** Minden csomag véletlen 2 jegyű hexadecimális végződést (`00`–`FF`) kap, adatbázis-szintű `UNIQUE` ellenőrzéssel és ütközés esetén automatikus újragenerálással.
