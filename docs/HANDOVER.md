# ReWear WMS session handover

**Checkpoint · 25 November 2026**

ReWear WMS is a Java 17 Swing desktop application for a second-hand textile warehouse. FlatLaf provides the bilingual Hungarian and English interface, SQLite persists the warehouse and finance records, and ZXing handles package QR codes. Start with the [task board](TASKS.md) for the next session and the [README](../README.md) for build instructions and the first-launch choice (the demo accounts exist only in a demo workspace).

## Build and verify

1. Use JDK 17. From the project root run `./mvnw clean verify` — the bundled Maven wrapper fetches Maven 3.9.9 on first use, so no Maven installation is needed. The checkpoint has 131 JUnit tests across 36 classes and produces `target/due-prog-03.jar`.
2. For a fresh installation, copy the JAR to an empty directory outside the checkout and launch it there with `java -jar due-prog-03.jar`. It shows the first-time setup (own company, demo workspace, or restore); pick the demo workspace for the accounts in the README. [AppPaths](../src/main/java/hu/due/rewear/config/AppPaths.java) anchors a JAR in this checkout's `target` directory to the checkout itself.
3. Use a copy of an earlier database when testing migrations. For a complete local backup, retain the database, invoice archive, receipt vault and mail attachment vault together. The in-app **Full Backup (.zip)** holds a database snapshot plus all three document directories with a SHA-256 manifest. Restoring a `.zip` verifies every checksum and the database integrity before touching anything, moves the current documents aside to `data/pre-restore-<time>-files/`, swaps the new state in (rolling back on failure) and signs the user out. A bare `.db` from an older version still restores the database only. The zips are not encrypted, so store them like the live data folder, and delete old `pre-restore-*` copies by hand.

## Demo walkthrough

| Step | What to show |
| --- | --- |
| Sort raw stock | As `manager`, receive a raw lot and inspect the roster. As `sorter01`, request stock and record graded output. Confirm delivery as `forklift`, close the batch, and compare processed input with the 1,000 kg default daily target. |
| Pack, label and store | As `helper01`, choose packaging and send the bag to the station. As `operator` (Állomásvezető), register it and preview the QR label. Use `forklift` to scan the package into storage. Teams can have multiple sorters and helpers. |
| Fulfil and invoice | As `manager`, create scheduled demand or import a local webshop order. Check availability, reserve stock, assign `forklift2` and a dock, and record customer arrival. Scan every package at the dock during loading. Complete handover and issue the outgoing invoice from Payments. |
| Record an expense | In Payments, choose or create a supplier, pick one VAT type and enter line items or the net total. Attach a receipt if available. Open the invoice preview to inspect the PDF, and use its internal email composer to attach it. Incoming and outgoing directions can be saved as smart folders. |
| Review payroll | As Admin or Super Admin, open Bérszámfejtés. Enter sample HUF rates and a split shift on the same day as the production activity. Observe the sorter input and helper packing under the linked department, then manually approve a draft shift bonus. Demo payroll starts without wage rates. |

## Where to work

- **Daily production and payroll.** Use [SortingService](../src/main/java/hu/due/rewear/service/SortingService.java), [SortingRosterMigration](../src/main/java/hu/due/rewear/config/SortingRosterMigration.java), and [PayrollService](../src/main/java/hu/due/rewear/service/PayrollService.java). A raw request snapshots its roster. Each bag records its actual packer. Sorting workdays and packing days use Europe/Budapest. The [payroll plan](Payroll-Implementation-Plan.md) documents the calculation and open business rules.
- **Scheduled fulfilment.** Use [OrderService](../src/main/java/hu/due/rewear/service/OrderService.java) and [OrderHandoverDialog](../src/main/java/hu/due/rewear/ui/OrderHandoverDialog.java). The [sorting-to-sale plan](Sorting-to-Sale-Implementation.md) contains the UTF-8 webshop import format. The scheduled path gates invoicing on verified shipment. Historical undated orders retain compatibility.
- **Finance and communication.** Start with [InvoiceService](../src/main/java/hu/due/rewear/service/InvoiceService.java), [InvoicesView](../src/main/java/hu/due/rewear/ui/InvoicesView.java), and [EmailService](../src/main/java/hu/due/rewear/service/EmailService.java). Seller settings and per-currency IBANs live in Admin. New invoice PDFs and receipts are stored separately by issue year and month under `data/invoices/` and `data/receipts/`. Internal email delivers to app mailboxes, and attachments are copied into `data/mail-attachments/` by [MailAttachmentVault](../src/main/java/hu/due/rewear/service/MailAttachmentVault.java). External SMTP and IMAP delivery, NAV reporting and bank connections are demo workflows. The bank ledger also supports manually entered accounts and transactions.
- **Time.** Business timestamps and business days come from [BusinessClock](../src/main/java/hu/due/rewear/util/BusinessClock.java), anchored to Europe/Budapest, never the machine's own zone. Use it instead of calling `LocalDate.now()` or `LocalDateTime.now()`. Tests can pin it with `useForTesting`.
- **UI and schema.** The [module registry](../src/main/java/hu/due/rewear/model/AppModule.java), [translations](../src/main/resources/i18n/translations.csv), and [schema](../src/main/resources/db/schema.sql) are the source of truth. The schema declares 41 app tables and 23 named indexes. The [university documentation](../README.md#university-documentation-docs) describes the current checkpoint.

## Local workspace

The live database, demo edits, documents, preferences, exports, logs, local JDK, Graphify output and private planning notes are workspace files. They are ignored by Git. For architecture questions in a checkout that has `graphify-out/graph.json`, query the local graph and verify details in source. Refresh it with `graphify update .` after code changes when the CLI is available.
