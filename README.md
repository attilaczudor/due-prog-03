# ReWear WMS (`due-prog-03`)

Second-hand clothing sorting & packaging warehouse management desktop application built in Java (Swing + FlatLaf + SQLite + ZXing + OpenPDF + XChart) for the **Programozás III** university project at Dunaújvárosi Egyetem.

## Highlights

- **Structured QR Product Codes:**
  `YYYY-MM-DD-HH-MM-<WHATFOR>-<TYPE>-<QUALITY>-<PACKAGETYPE>-<WEIGHT>-<HEX2>`
  Example: `2026-11-12-11-23-L-TSH-A-A-25-3F` (entered 2026-11-12 at 11:23, Ladies T-Shirts, Grade A, Package Type A, 25 kg, random hex suffix `3F`).
- **Dynamic Code Dictionaries:** Create and manage Target Groups, Garment/Product Types, Quality Grades, and Package Types directly in the app.
- **QR Ticket Print Preview & Printing:** Thermal (100×150 mm, 80×100 mm), A6, and A4 4-up print previews with system printer support (`PrinterJob`), PDF export, and PNG export.
- **QR Scanner Station:** Supports USB/Bluetooth HID keyboard-wedge scanners and QR image decoding via ZXing for instant inspection, relocation, stocktake verification, and order dispatch.
- **CSV-Driven Bilingual UI (EN / HU):** All UI strings live in `translations.csv`, allowing new languages to be added simply by adding a column.
- **Zero-Config Embedded Database:** 12-table 3NF SQLite schema with automatic initialization and demo seed data.

## Requirements

- **Java 17+** (JDK)
- **Apache Maven 3.9+** (or open directly as a Maven project in Apache NetBeans / IntelliJ IDEA)

## Build & Run

```bash
# Run unit & integration tests
mvn test

# Build standalone runnable JAR
mvn clean package

# Launch the application
java -jar target/due-prog-03.jar
```
