# ReWear WMS optimization and cleanup plan

**Opened · 25 November 2026 · baseline commit `e7af6a2`**

This board tracks the deep-clean pass: audit findings, bug fixes, and planned improvements.
Audit scope was the full checkout — Graphify graph (3,324 nodes / 13,123 links), 161 main
sources, 24 test classes, 41-table schema, and the handover and planning documents.

## Baseline verified before any change

| Check | Result |
| --- | --- |
| `mvn clean verify` | BUILD SUCCESS, 2m55s |
| JUnit 5 | 62 / 62 passing across 24 classes |
| Java compiler warnings (`-Xlint:all,-serial`) | 0 |
| Shaded artifact | `target/due-prog-03.jar`, 26 MB |
| Schema vs docs | 41 tables / 23 indexes — matches README and Adatbázisterv |
| Translation keys | 810, no duplicates, no blank cells, no ragged rows |
| Enum i18n coverage | 44 constants across 9 enums, 0 missing keys |
| Dead classes | 0 unreferenced source files |
| `printStackTrace` / `System.out` / empty catches | 0 / 0 / 0 |
| Unbalanced transactions, JDBC outside try-with-resources | 0 / 0 |

The codebase is in good condition. The findings below are specific and narrow, not structural.

---

## Phase 1 — Bugs and correctness

*All five fixed in this pass. Changes: `InvoiceDao.findOverdue()`, `TodoDao.countOpenByUser()`,
`TodoService` (SQL filtering + `countOpenPersonalTodos`), `MainFrame` (cheap count + debug log),
`App.pruneStaleMailExports()`, shade filters in `pom.xml`, and a committed Maven wrapper.*

- [x] **B1 · Sidebar badge refresh scans the whole database every 10 seconds.**
  `MainFrame.refreshSidebarBadges()` runs on a 10 s timer and calls
  `TodoService.buildSystemTodosFor()`, which for a Manager / Admin / Super Admin executes
  `invoiceDao.findAll()` and `orderDao.findAll()`, materialises every invoice and order,
  formats a localized title and detail string for each, and then discards all of it except
  `.size()`. Personal to-dos are loaded in full and counted in Java. Cost grows linearly with
  total invoices and orders, forever, on a timer.
  *Fix:* filter in SQL — use the existing `OrderDao.findOpenOrders()`, add
  `InvoiceDao.findOverdue()` and `TodoDao.countOpenByUser()`.

- [x] **B2 · Background badge failures are silently swallowed.**
  The `AsyncUiRunner.runLatest` error callback in `refreshSidebarBadges()` is an empty lambda
  with only a comment. A database lock, migration fault or DAO regression makes badges silently
  stop updating with no log line anywhere.
  *Fix:* log at debug level so the failure is diagnosable without becoming a popup.

- [x] **B3 · Email attachment exports accumulate forever.**
  `DocumentPreviewDialog.handleSendEmail()` calls `Files.createTempDirectory(exportsDir, "mail-")`
  for every send and never removes it. This checkout already holds three orphaned `exports/mail-*`
  directories, 476 KB, from a single session's testing.
  *Pass 1 fix, later found wrong:* prune `exports/mail-*` older than 7 days. That file is not
  scratch — it is the only copy of the attachment, and the Sent and Inbox mail rows point at it by
  absolute path. Age-based pruning would have silently deleted attachments from every email older
  than a week, leaving a `[PDF]` button that opens nothing. It was caught in pass 2 before any
  commit, so nothing shipped. The three folders seen on this machine really were orphans (no mail
  row referenced them), which is why the flaw did not show up in testing.
  *Corrected fix:* `MailExportJanitor` removes a folder only when it is **both** older than seven
  days **and** referenced by no mail row, matched by folder name so moving the application
  directory cannot make a live attachment look orphaned. Runs after the database is initialised
  and never blocks startup. See `MailExportJanitorTest`, including the end-to-end case.

- [x] **B4 · A fresh clone cannot be built.**
  `.runtime/jdk-17` is a symlink into a disposable Gemini/Antigravity scratch directory, and the
  only Maven on this machine lives in another scratch directory under the same tree. There is no
  Maven wrapper. `HANDOVER.md` itself says to "check that it still exists" — the toolchain is
  one cache cleanup away from unbuildable, which is a real risk for a university submission.
  *Fix:* commit a Maven wrapper so `./mvnw clean verify` works from a clean clone with only a JDK.

- [x] **B5 · Shaded jar emits 24 build warnings.**
  Overlapping `META-INF/DEPENDENCIES`, `META-INF/LICENSE`, `META-INF/NOTICE` and
  `module-info.class` across pdfbox, fontbox, flatlaf, slf4j, commons-logging and sqlite-jdbc.
  *Fix:* exclude the duplicated metadata in the existing shade filter. 24 warnings down to 9.
  The remaining 9 are the `META-INF/MANIFEST.MF` overlap and the plugin's own explanatory
  footer — not removable, since the uber-jar needs exactly one manifest and the existing
  `ManifestResourceTransformer` already produces it.

---

## Verification after the Phase 1 and 2 changes

| Check | Before | After |
| --- | --- | --- |
| `./mvnw clean verify` | n/a (no wrapper existed) | BUILD SUCCESS |
| JUnit 5 | 62 / 62 | **66 / 66** (4 new in `BadgeQueryOptimizationTest`) |
| Java compiler warnings | 0 | 0 |
| Shade plugin warnings | 24 | **9** — only the irreducible `META-INF/MANIFEST.MF` overlap plus the plugin's generic explanatory block. Every uber-jar has one manifest; `ManifestResourceTransformer` already writes the correct one. |
| Shaded jar | 26 MB | 26 MB |
| Badge refresh reads per tick (Manager) | every invoice + every order + every personal to-do | overdue invoices + open orders + one `COUNT(*)` |

### After pass 2

| Check | Pass 1 | Pass 2 |
| --- | --- | --- |
| `./mvnw clean verify` | BUILD SUCCESS | BUILD SUCCESS, 2m58s |
| JUnit 5 | 66 / 66 across 25 classes | **74 / 74 across 26 classes** (+5 `MailExportJanitorTest`, +3 `BackupServiceTest`) |
| Java compiler warnings | 0 | 0 |
| Shade warnings | 9 | 9 (unchanged, irreducible) |
| Translation structure guard | passing | passing, with the edited restore message |

### After pass 3

| Check | Pass 2 | Pass 3 |
| --- | --- | --- |
| `./mvnw clean verify` | BUILD SUCCESS | BUILD SUCCESS, 3m31s (after fixing one compile error, see above) |
| JUnit 5 | 74 / 74 across 26 classes | **94 / 94 across 30 classes** |
| Direct system-clock calls in main | 72 | **0**, enforced by `BusinessClockUsageTest` |
| Java compiler warnings | 0 | 0 |
| Shade warnings | 9 | 9 (unchanged, irreducible) |

Pass 4 raised the totals to **103 tests across 31 classes**, pass 5 to **113 across 33**, pass 6 to **120 across 34**, pass 7 to **127 across 35**, pass 8 to **131 across 36**, pass 10 to **136 across 37**, pass 11 to **145 across 40** (details in their sections).

## Phase 2 — Improvements

- [x] **I1 · Badge query budget.** The per-tick badge work now uses SQL filters and one `COUNT(*)`
  instead of loading whole tables. No query-count instrumentation was added, so the saving is
  described by what is read, not measured.
- [x] **I2 · Regression test for the cheap queries.** `BadgeQueryOptimizationTest` pins each new
  SQL query to the Java filtering it replaced, so a filtered query and the displayed list cannot
  drift apart.
- [x] **I3 · Export hygiene test.** `MailExportJanitorTest` (5 tests) covers age, references,
  relocation and the sent-then-aged-then-deleted scenario end to end.
- [x] **I4 · Document the real toolchain** in `HANDOVER.md` once the wrapper lands, replacing the
  scratch-directory Maven path.

---

## Pass 2 — deeper audit (25 November 2026)

Pass 1 was pattern scans plus one hot path. Pass 2 read the business-logic code itself: auth,
file handling, backup and restore, stock reservation, credit notes and time handling.

### Fixed

- [x] **B6 · A partly damaged backup passed validation and replaced the live database.**
  Restore checked only the SQLite header and four table names. Both live on the first pages, so
  damage anywhere else slipped through. Verified empirically: a backup with one zeroed page in the
  middle was accepted by the old check and rejected by `PRAGMA integrity_check`. (Truncated
  backups were already rejected, because reading the schema hits the missing pages — an earlier
  suspicion that they passed was wrong.)
  *Fix:* run `PRAGMA integrity_check` on a read-only connection before touching anything.
- [x] **B7 · Restore overwrote the live database in place.** The safety copy was a raw file copy,
  which can capture a write in flight, and the restored file was copied directly over the live
  one, so a failure part-way (full disk) left it half written. A stale `-journal` sidecar would
  also have been replayed by SQLite into the freshly restored file.
  *Fix:* safety copy via `VACUUM INTO`; restored file staged beside the live one and swapped in
  with one atomic move; stale `-journal`/`-wal`/`-shm` removed first; copy size verified; restoring
  the live file onto itself refused. Three new tests in `BackupServiceTest`.
- [x] **B8 · The session survived a restore.** The signed-in user object kept its pre-restore
  state while the 10 s refresh carried on. An older backup may lack that account or assign a
  different role. Services hold no data caches (checked), so a fresh login is sufficient.
  *Fix:* sign out after a successful restore, with the message updated in both languages.
  *Not covered by an automated test:* this is Swing UI code (`AdminView`), verified by reading and
  compiling only.

### Audited, no change needed

- `AuthService`: BCrypt, password arrays zeroed, self-disable and Super Admin guards present.
- `InvoiceFiles.resolve`: rejects absolute and non-normalised paths and symlink escapes.
- `OrderService` allocation: single transaction, all-or-nothing, shortages checked up front.
- Credit notes: guarded, but duplicate detection matches a text prefix on line descriptions
  (already on the product roadmap as a structured original-invoice link).

### Observations carried forward

- **Mail attachments are references, not copies.** *(Resolved in pass 3, item V1.)* The mail row stores an absolute path, the
  composer's *Browse* accepts any file on disk, and `EmailView` opens it with `Desktop.open()`.
  The only caller today attaches PDFs the app exported, so this is not exploitable through the UI,
  but the service layer trusts any string. The vault described under pass 3 closes this.
- **`order_items` is `UNIQUE (order_id, package_id)`.** That stops a package being added to the
  same order twice, not to two orders. The `IN_STOCK` check plus SQLite's write lock protects
  against this in a single-machine app. A stricter constraint would break re-reservation after a
  cancel, so it needs a status-aware design rather than a simple index.
- **Money is stored as SQLite `REAL`.** Java uses `BigDecimal` throughout and values are rounded to
  the currency's scale before storage, so round-tripping is clean in practice. Treated as a design
  smell, not a bug.
- **Time zones.** *(Resolved in pass 3, item C1.)* Sorting and payroll use an explicit Europe/Budapest clock; the other 55
  `now()` calls in services and DAOs use the system zone. Harmless while the machine runs on
  Budapest time (this one is CET), but a consistency risk elsewhere, and tests cannot control time.

---

## Pass 3 — mail attachment vault and one business clock (25 November 2026)

### Built

- [x] **V1 · Mail attachment vault.** `MailAttachmentVault` copies each attachment into
  `data/mail-attachments/YYYY/MM/` when a message is sent or saved as a draft, and the mail rows now
  hold that portable relative path. This removes the root cause behind the pass-1 B3 mistake: an
  attachment no longer depends on where the composer happened to point.
  - Only PDF, PNG, JPEG, DOCX, XLSX, PPTX, CSV and TXT are accepted, up to 25 MB, each checked against
    its file signature where one exists. A text file renamed `.pdf` is refused.
  - Resolution cannot leave the vault: parent segments, non-normalised paths and symlinks that lead out
    are all refused (the same traversal-safe lookup the invoice archive uses).
  - The Sent copy and every Inbox copy share one stored file. It is deleted only when the last message
    referencing it is permanently deleted. Startup also sweeps vault files no row references after a
    day, covering a crash between the copy and the database write.
  - A refused send leaves nothing behind: the copy is made only after every check that can reject the
    message, and is undone if the database write then fails.
  - **Viewer hardening.** `EmailView` no longer opens `Path.of(row.attachmentPath())` directly. It asks
    the service, which returns only vault files or legacy files of an allowed type, so a message can
    never make the viewer launch a script or application. The button label now shows the real file type
    instead of always `[PDF]`.
  - **Compatibility.** Messages sent before the vault keep working: a legacy absolute path still opens if
    the file exists and is an allowed type, and `MailExportJanitor` keeps protecting the `exports/`
    folders those rows point into. New sends no longer reference `exports/` at all, so those folders
    become pure scratch and are reclaimed after seven days.
  - 9 tests in `MailAttachmentVaultTest` and 6 in `EmailAttachmentFlowTest`, the latter driving the real
    service, database and vault together.
- [x] **C1 · One business clock.** `BusinessClock` (Europe/Budapest) is now the only source of business
  time. 72 direct `LocalDate.now()` / `LocalDateTime.now()` calls in 35 files were replaced, and the four
  separate Budapest constants (sorting, payroll, roster migration, sorting view) collapsed into one.
  Sorting still stores UTC instants; only where "now" comes from changed, not any storage format.
  - **SQL-side UTC clock removed.** `role_module_permissions.updated_at` used SQLite's `datetime('now')`,
    which is UTC, while every other timestamp is local. The DAO now supplies the business time.
  - **Guard test.** `BusinessClockUsageTest` scans the main sources and fails the build if a direct
    system-clock call or SQLite UTC clock is reintroduced, in the same spirit as the translation guard.
  - **Proof, not assertion.** Four tests in `BusinessClockTest` move the JVM to Los Angeles and pin an
    instant that is already 25 November in Budapest but still 24 November locally, then check sorting,
    payroll, the permission timestamp and the vault's dated folder all follow Budapest. One more test in
    `MailAttachmentVaultTest` covers the month boundary.
  - Existing tests were moved onto the same clock so their expectations match the code under test.

### Mistakes made and caught during this pass

- The scripted replacement turned a fully-qualified `java.time.LocalDate.now()` in `OrdersView` into the
  nonexistent `java.time.BusinessClock.today()`. The first full build failed to compile on it. Fixed, and
  every `BusinessClock` reference in main and test code was then checked for a missing import or a
  mangled qualifier.

### Not covered by an automated test

- `EmailView` is Swing code: the new open-attachment flow and the `[TYPE]` label are verified by reading
  and compiling only, as is the sign-out after a restore from pass 2.

### Documentation numbers to carry into the Hungarian reports

Deferred with the rest of the submission documents, but the figures are now:
**847** translation keys (reports say 810), **172** main Java sources (Programterv says 161), **42** tables and **24** indexes (reports say 41 and 23),
**36** test classes (says 24), **131** tests (says 62), **172** main sources, **847** translation keys (reports say 810; passes 5, 6 and 8 added 36), **42** tables, **24** indexes.

---

## Pass 4 — full backup bundle (25 November 2026)

### Built

- [x] **Full backup (`.zip`).** `BackupService.backupBundle` writes the `VACUUM INTO` snapshot plus every
  file under `invoices/`, `receipts/` and `mail-attachments/`, with `manifest.txt` listing each entry's
  SHA-256 and size. The zip is staged beside the destination and moved into place, so a failed backup
  never appears under its final name. Scratch files (`.rewear-*`, `*.tmp`) and symlinks are skipped, and a
  destination inside one of the document folders is refused (the backup would contain itself).
- [x] **Staged restore (`restoreBundle`).** The bundle is verified and unpacked into a hidden staging folder
  first. Checks, all before anything live changes: manifest present and well formed; every entry listed
  and every listed entry present; names confined to `database.db` and `files/<known folder>/...`
  (no `..`, empty segments, backslashes, control characters or absolute paths); each file's size and
  SHA-256 verified while unpacking, with the read capped one byte past the declared size; enough free
  disk for the declared total; then the usual database integrity check.
- [x] **Swap with rollback.** Current document folders are moved aside to `pre-restore-<time>-files/`
  (never deleted), the staged folders move in, then the database is swapped last through the existing
  `restoreDatabase`. Any failure undoes the folder moves newest-first.
- [x] **UI.** Admin → "Full Backup (.zip)" and "Restore Backup (.zip / .db)". A `.db` from before this pass
  still restores, database only. The confirmation text says which parts each kind restores. No new
  translation keys (existing values reworded), so the count stays 811.

### Verification

`./mvnw clean verify`: BUILD SUCCESS, **103 / 103** across 31 classes (+9 in `BackupBundleTest`). Two
mutation checks confirmed the tests can fail: removing the checksum and name checks broke the tamper and
traversal tests, and removing the rollback call broke the post-swap failure test.

### Known limits

- Backups are not encrypted; the zip holds invoices, mail and customer data and should be stored as
  carefully as the live data folder.
- `pre-restore-*` copies (database and documents) accumulate. Nothing prunes them yet.
- The database snapshot is taken first, then the folders are read. A document saved in between can be
  in the zip without a row, which is harmless. A file deleted while the zip is being written fails the
  backup instead of being skipped. The backup does not cross-check rows against files, so a row whose
  file was already missing before the backup stays missing after a restore.
- Restore is meant for an idle application on a single workstation; it does not coordinate with other
  running instances.
- `AdminView` button flow is verified by compiling and reading only, not by a UI test.

---

## Pass 5 — demo workspace vs real company (25 November 2026)

### What was wrong

Demo data was not a first-run choice; it was part of every start, and some of it was a security problem
for any real installation:

- A schema migration recreated `superadmin` / `superadmin123` on every start whenever that account was
  missing, so a company's administrator could remove it and it would come back with a public password.
  Another recreated `forklift` / `forklift123` the same way.
- The same migration re-added the demo stations and dock on every start, bringing back locations a company
  had retired.
- Demo invoices, mail and chat rooms were inserted at every start wherever those tables were empty. A real
  company with no invoices yet would have woken up with four sample invoices, three mails and three rooms.
- Demo bank accounts were seeded on every start. The five base roles existed only because the demo seeder
  created them, so a database without it had no roles at all.
- The login screen always showed one-click buttons carrying the default passwords.

### Built

- [x] **Roles belong to the schema.** All seven are created with `INSERT OR IGNORE`; no migration creates
  an account any more. The v2.2 upgrade step now runs only in the run that actually adds its columns.
- [x] **`OnboardingService`.** A fresh database stays empty until one explicit choice:
  `startDemoWorkspace()` or `createCompanyWorkspace(...)`. The company path creates one SUPER_ADMIN
  account in a single transaction, stores the company name, and optionally adds the standard catalogues
  and a starter shelf layout. Both calls refuse to run on an installation that already has users.
- [x] **Password and input rules for the company owner.** At least 8 characters and at most 72 bytes
  (BCrypt ignores the rest), not a published demo password, not a common one, not containing the
  username; username 3-32 characters of letters, digits, dot, dash, underscore; company name required.
- [x] **`StarterData`.** The dictionaries and shelf layout moved out of the demo seeder so the demo and a
  company use one definition of them.
- [x] **Startup rules.** The application opens with `InitialData.AWAIT_SETUP`; databases that already have
  users are never topped up with sample records. Tests keep `DEMO_IF_EMPTY` through the 3-argument
  constructor. Existing demo workspaces still get the sorting-roster upgrade.
- [x] **Existing databases are classified once.** A database with no recorded mode counts as a demo
  workspace if any demo account still has its published password, otherwise as a company.
- [x] **UI.** A first-time setup dialog (company, demo, or restore from a backup) and login quick-fill
  buttons that appear only in a demo workspace.

### Verification

`./mvnw clean verify`: BUILD SUCCESS, **113 / 113** across 33 classes (+9 `OnboardingServiceTest`,
+1 `SetupWizardDialogTest`). Mutation check: putting the old top-up call back made the regression test fail
with exactly the leak above (4 invoices, 3 mails, 3 chat rooms in a company workspace); disabling the
forbidden-password check broke the validation test. A scratch copy of the jar, started in an empty
directory, created a database with 0 users, 0 invoices and all 7 roles.

### Not verified or deliberately left

- The setup dialog was never seen on screen: a screenshot of the scratch run came back black. It is
  covered by a construction test in both languages only, so check layout and wording by eye once.
- A demo workspace cannot be converted into a company workspace; the way out is a backup restore.
- A new company still gets the default internal mail domain (`rewear-wms.hu`) until it is changed in
  Admin, and the partner-type upgrade step still adds sample partners to a very old database it upgrades.
- Existing real installations that kept the demo accounts keep them; deleting them is up to the owner.

---

## Pass 6 — pick lists, and a CI workflow that was dropped (25 November 2026)

### CI (decided against)

A GitHub Actions workflow (`./mvnw -B -ntp clean verify` on Ubuntu and macOS) was written, and the same command
was verified locally headless. The push was then rejected because the token had no `workflow` scope, and the
decision was **not to use CI**, so the workflow was discarded and is not in the repository. The build is run
locally with `./mvnw clean verify`.

### Pick lists

- [x] **`PickListService`.** Built on the open order staging tasks, nothing new is stored. A package counts
  as collected when it is at the dispatch zone, which is exactly what scanning it for an order does, so
  progress cannot drift from the real state. Lines still to collect come first in walking order (zone,
  then aisle, then shelf, then package code); staged ones follow. Numbers inside labels compare by value, so
  aisle 2 precedes aisle 10. Lists exist for one order, a selection of orders (one walk across several), or
  every order assigned to a driver; progress is available overall and per order.
- [x] **`PickListPdfExporter`.** A4 document in the delivery-note style: collected/total and remaining weight,
  per-order progress, a tick box per package, staged packages crossed out and last. Previewable and
  printable through the existing document preview.
- [x] **Order Staging screen.** A "Pick list" button: the selected rows' orders, or everything the user may
  see when nothing is selected (a forklift driver only ever sees their own orders). Six new translation keys.

### Verification

`./mvnw clean verify`: BUILD SUCCESS, **120 / 120** across 34 classes (+7 `PickListServiceTest`). Mutation
checks: removing "staged last" broke the progress test. The first attempt at the numeric-sort check did
**not** fail with plain text order, because the test's zero-padded aisles (`02`, `10`) sort correctly as
text; the test now uses `2` and `10`, and the mutation fails as it should.

### Not done

- The walking route is plain ascending order. It does not alternate direction between aisles (an S-shaped
  walk) and does not know the physical layout; it assumes labels run in the order a person walks them.
- The Order Staging button and the printed layout were checked by compiling and painting to an image, not
  by looking at them on screen.
- Stocktake sessions and damage/quarantine/reweighing operations are still open.

---

## Pass 7 — invoice lifecycle (25 November 2026)

### What was wrong

`InvoiceService.changeStatus` accepted any move between any two statuses, and the invoice dialog offered
all five in a drop-down. In practice that allowed:

- Setting an **issued invoice back to DRAFT**. A draft's lines and amounts are regenerated from its order,
  so saving the order again would silently rewrite figures the customer had already received.
- **Cancelling by hand**, which skipped the credit note, so a cancelled invoice had nothing reversing it
  in the ledger.
- Reopening a **cancelled** invoice, changing a **credit note's** status, and **withdrawing the payment**
  of an invoice a credit note had already reversed (leaving the credit note's cash movement unmatched).
- No record of who did any of it. Duplicate credit-note detection also read the first line of an item's
  text instead of a real link.

### Built

- [x] **`InvoiceLifecycle`.** The only hand moves: DRAFT to ISSUED, ISSUED or OVERDUE to PAID, PAID back to
  ISSUED. Never back to DRAFT, never to CANCELLED (only a credit note does that), OVERDUE is set by the
  due date, CANCELLED and credit notes are final, and a PAID invoice with a credit note stays paid.
  Refusals say what to do instead (for example "correct it with a credit note").
- [x] **`changeStatus(id, target, userId)`** enforces it in one transaction and records an event. Issuing a
  draft stamps today as the issue date, keeps the payment terms (days between issue and due), and refreshes
  the archived PDF; the archive of an already issued invoice is never rewritten. The old two-argument form
  is gone, so no caller can skip the user.
- [x] **Audit trail.** New table `invoice_events` (issued, paid, payment withdrawn, credit note issued, credit
  note for) with the acting user and time; `InvoiceService.history(id)`.
- [x] **Structured credit-note link.** New column `invoices.credit_for_invoice_id`, set when a credit note is
  issued and used by the duplicate check. Older credit notes are linked once on startup by reading their
  `CREDIT NOTE [Ref: ...]` prefix; the old text check remains as a fallback.
- [x] **UI.** The status drop-down lists only the current state and the moves allowed from it, and the
  Apply button is disabled when there are none.

### Verification

`./mvnw clean verify`: BUILD SUCCESS, **127 / 127** across 35 classes (+7 `InvoiceLifecycleTest`, including a
test of every status against every target). Three mutation checks all failed as they should: disabling the
guard (4 failures), allowing withdrawal after a credit note, and skipping the legacy backfill.

### Not done

- No role check in the service and no history in the UI: both were done in pass 8.
- Invoice numbers are still assigned when a draft is created, not when it is issued, so drafts issued out
  of order produce numbers that do not follow issue dates; a draft that is abandoned leaves a gap.
- The dialog change was checked by compiling only, not by clicking through it.
- Money is still stored as SQLite REAL (rounded before storage); moving to integer minor units remains open.

---

## Pass 8 — who may act on invoices, and the history on screen (25 November 2026)

- [x] **Access enforced in the service.** `changeStatus` and `issueCreditNote` now refuse (with a
  `SecurityException`, before anything is read or written) unless the acting user is active and their role has
  the Invoices module in the role matrix. The service takes an `Access` rule at construction, and the
  application wires it to the matrix, so the rule is the same one the menu uses: by default Admin, Manager and
  Super Admin; granting or revoking the module for a role changes who can act, immediately. A deactivated or
  unknown user is refused. There is no permissive default constructor.
- [x] **History in the invoice dialog.** A table under the items lists each event with time, what happened
  (with the status change), who did it and any note. It refreshes after every action. The Apply and
  credit-note buttons are also disabled for a user without access. Ten new translation keys (English and
  Hungarian).

### Verification

`./mvnw clean verify`: BUILD SUCCESS, **131 / 131** across 36 classes (+4 `InvoiceAccessTest`). Mutation checks:
making the check a no-op failed 3 of 4 tests; dropping the "active user" condition failed the deactivated-user
test.

### Not done

- Creating invoices (from an order, manually or from a receipt) is not gated by this rule. An order's invoice
  is created from the Orders screen, which has its own module, and gating it by Invoices could stop a legitimate
  Orders-only role; that needs a decision about the intended roles first.
- The dialog was checked by compiling only; the history table and disabled buttons were not seen on screen.

---

## Pass 9 — Hungarian reports and todo files (25 November 2026)

The three Hungarian reports and the task board were brought up to date and checked against the code, not
just renumbered.

### Method

A script compared `schema.sql` (plus the columns added by migrations) with `docs/Adatbazisterv.md`: every
table is documented, every column of the checked tables appears, every identifier in the report exists in the
schema, the logical groups add up to 42, and the index count matches. The Programterv test table is generated
from the surefire reports (36 classes, 131 tests, matching the build).

### Errors in the reports that predate this work (found by that check)

- The roles table listed 5 of the 7 roles (`SORTER` and `SORTING_HELPER` were missing).
- `chat_rooms.room_type` was documented as `GROUP`/`DIRECT`; the schema says `ROOM`/`DIRECT`.
- `chat_room_members` was documented with `last_read_message_id` and `joined_at`; the schema has only `last_read_at`.
- `user_todos` was documented with priority `NORMAL` and a `completed_at` column; the schema has `MEDIUM`, no
  `completed_at`, and an undocumented `due_date`.
- `emails.is_external` was undocumented.

### Updated

- **Adatbázisterv:** 42 tables, 24 indexes, `invoice_events`, `invoices.credit_for_invoice_id`, the corrections
  above, the full backup, the mail attachment vault and the time handling.
- **Programterv:** counts, architecture diagram, design patterns, use cases (new UC-13), an invoice state
  diagram, UML additions, two new sequence diagrams (first launch, staged restore) and the regenerated test table.
- **Projektterv:** counts, four new feature items, three new phase rows, four new risks and a list of known limits.
- **`docs/TASKS.md`**, the private `PHASES_AND_TASKS.md` and `HANDOVER.md`: completed work, new next steps,
  the long-term list.

### Not done

- The new Mermaid diagrams were reviewed by eye but not rendered; no Mermaid renderer is installed here.
- The Google Docs and Slides, the 300-DPI diagram PNGs and the screenshots are still to be made when requested.
- The private `PRD.md` and the assignment brief still describe the v2.5 baseline (697 keys, 26 tables, 59 tests).
- A headless run of the full build (the CI setting) passed with 131 run and 1 skipped.

---

## Pass 10 — raw material supply (25 November 2026)

### The real process this models

Transit drivers collect used clothing daily from drop-off points across the country, each driver covering their
own area, and bring it to the facility. A forklift driver stores each collection somewhere in the warehouse.
Sorters then call for material from what is stored. A sorter cannot know in advance how much they will get:
that is decided when the driver delivers.

### Built

- [x] **Raw material lines.** A lot now carries the material, the collection area it came from, and the storage
  location it was put away at. `sorting_raw_lots` gained `material`, `source_area` and `location_id`.
- [x] **Receive, then store.** Receiving a collection notifies the supply drivers (not the fulfilment driver,
  who serves customer orders) and leaves the line unstored. It becomes usable only once a forklift driver
  stores it on an active storage shelf.
- [x] **Call for a line, not a weight.** `requestSupply(lotId)` replaces `requestSupply(kg)`. The request stores
  `requested_lot_id` and no weight; `sorting_requests.requested_grams` became nullable, which needed a table
  rebuild (`SortingRawMaterialMigration`) because SQLite cannot drop `NOT NULL` in place. Older weight-based
  requests keep working, and for those the driver still chooses the lot at delivery.
- [x] **Delivery decides the amount.** The driver records what was actually brought; it cannot exceed what the
  line holds, and the called line cannot be swapped for another.
- [x] **Raw materials is the first tab of Sorting**, showing material, area, location (or "awaiting storage"),
  available and received amounts in tonnes with the exact kilograms beside them. Sorters see only lines they can
  actually call for. Twelve new translation keys.
- [x] **Demo data:** six daily collections from six collection areas, four stored and two still awaiting storage.
  An existing demo workspace is upgraded to them in sequence (version 1 → 2 → 3).

### Verification

`./mvnw clean verify`: BUILD SUCCESS, **136 / 136** across 37 classes (+5 `RawMaterialSupplyTest`). Mutation
checks: allowing an unstored line to be called for, showing unstored lines to sorters, and letting the driver
swap the called line each broke a test. One real bug was caught by an existing test while building this: the
first version of `receive` notified every forklift driver, including the fulfilment driver who has nothing to do
with raw supply.

### Not done

- The transit drivers themselves are not users or a role; a collection is recorded by warehouse management, with
  the area as free text. If drivers should log in and record their own collections, that is a separate change.
- A delivery still comes from exactly one line.
- The screens were checked by compiling and by service tests, not by clicking through them.

---

## Pass 11 — company roles and the shift rota (25 November 2026)

### Roles

The company now has a hierarchy rather than a flat list. New: **PRODUCTION_LEAD** (termelési vezető) over the
sorting operation, **SHIFT_LEAD** (műszakvezető) under them for one shift, and **HR_PAYROLL** for pay, rota and
worker records. The single **FORKLIFT** role split in two, because one role was doing two different jobs and the
code could only tell them apart by looking at team and order assignments:

- **SUPPLY_DRIVER** (alapanyag-targoncás) serves the sorting floor in both directions: stores arriving
  collections, delivers raw material to the stations, and takes finished labelled packages to the shelves.
- **DELIVERY_DRIVER** (kiszállítási targoncás) serves customer orders: collecting to the dock and loading.

A one-off migration renames the old role (so its module permissions carry over) and moves each driver by what
they actually did: supplying a sorting team keeps the supply job, everyone else becomes a delivery driver. It is
guarded so it never runs twice, which a mutation check confirms. Module defaults follow the hierarchy: money
stays with management, payroll with HR and the administrators, reference data with the production lead.

### Rota

A new **ROTA** module and top-level menu with month, week and day calendar views. A shift is one person's day,
including a planned rest day, so `rota_shifts` has `UNIQUE (user_id, work_day)` and planning the same day again
corrects it. The planning roles see and write the whole company; everyone else sees their own days read-only.
The demo workspace is filled with a month of shifts that rotate weekly through morning, afternoon and night for
the real sorters, helpers, station manager and drivers, with weekends off for most and a small night crew.

### Verification

`./mvnw clean verify`: BUILD SUCCESS, **145 / 145** across 40 classes (+3 `RoleHierarchyTest`, +5
`RotaServiceTest`, +1 `RotaViewTest`). The rota view is painted to an image in the test and checked for actual
ink, which caught a first version that laid out only its top level and rendered nearly blank. The month and week
views were also rendered to PNG and looked at.

### Not done

- The rota has no shift templates per team, no copy-last-week, and no conflict rules beyond one shift per day.
- The calendar is read and planned from the same screen; there is no printable rota sheet yet.
- The primary "Plan shift" button does not paint its background in an offscreen render; its text and colours
  were checked directly and are correct, and the real application paints primary buttons normally.
- Transit drivers are still not users; collections are recorded by management with the area as free text.

---

## Phase 3 — Carried forward from the November 24 review

These remain open. They are product work, not cleanup. Passes 2 and 3 only prepared the ground for the backup bundle; the rest are untouched.

- [x] Picking by order and shelf, with route-sorted pick lists and collected/total progress — done in pass 6, see below.
- [ ] Stocktake sessions plus explicit damage, quarantine and reweighing operations.
- [x] Backup and restore as one validated bundle — done in pass 4, see below.
- [x] Separate demo seeding from real first-run onboarding — done in pass 5, see below.
- [x] Explicit draft / issue / correction steps in finance — done in pass 7, see below.
- [ ] Measure scan-to-feedback and refresh latency under representative data volumes.

---

## Working agreement

Refresh the Graphify graph with `graphify update .` before every commit and push, so
`graphify-out/graph.json` always matches the committed tree.
