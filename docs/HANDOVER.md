# ReWear WMS — handover

**Checkpoint · 26 November 2026, after optimization pass 29 · `main`**

This is the document to read first in a new session. It says what the application is, how to build and run it,
what rules it holds itself to and who enforces them, how work is done here, what is finished, what is open and
who each open thing is waiting on. Where something is deliberately *not* built, it says so and why — that is
usually the more useful half.

---

## 1. What it is

A Java 17 Swing desktop application for a second-hand textile warehouse: material is bought by the lot,
received, sorted into graded bags by teams working to daily targets, packed, labelled with a QR code, put
away, reserved against customer orders, staged on route-sorted pick lists, handed over with verification and
invoiced in several currencies with the invoice reported to the Hungarian tax authority. Around that run the
shift rota, the payroll draft, stocktakes with quarantine, grade-yield reporting, the stoppage log, the shift
handover record, internal mail and chat, and a per-role module matrix.

One file, one process, no server. SQLite holds everything; documents (invoice PDFs, receipts, mail
attachments) live in folders beside it and travel together in one backup bundle.

| | |
|---|---|
| Java | 231 main sources, 49,451 lines |
| Tests | 365 tests in 64 test classes, 1 skipped (the load measurement runs on request) |
| Database | 60 tables, 38 indexes in `schema.sql` (two more are added by the upgrade steps), schema version recorded inside the database |
| Layers | 31 DAOs, 48 services, 62 model types, 21 screens |
| Roles | 11, with a database-driven module matrix |
| Languages | 1,112 translation keys, English and Hungarian, from one CSV |
| Dependencies | unchanged since the first pass: FlatLaf, SQLite JDBC, ZXing, OpenPDF, XChart, jBCrypt, SLF4J/Logback, JUnit 5 |

---

## 2. Build, run, test

```sh
export JAVA_HOME="$PWD/.runtime/jdk-17/Contents/Home"
./mvnw clean verify
```

- The Maven wrapper is script-only and fetches Maven 3.9.9 into `~/.m2/wrapper` on first use. No Maven
  installation is needed.
- `.runtime/jdk-17` is a **symlink into a disposable tool cache**, not an installation. If it vanishes, point
  `JAVA_HOME` at any JDK 17, or use a launcher (`run-mac.command`, `run-linux.sh`, `run-windows.bat`), which
  fetch a local Temurin 17 themselves.
- A full `clean verify` takes about eleven minutes, most of it the tests, each of which opens its own
  workspace. Running one class is `./mvnw -o test -Dtest=ClassName`.
- Headless works; the four test classes that need a display skip themselves.
- The load measurement is skipped unless asked for:
  `./mvnw test -Dtest=LoadMeasurementTest -Dload.rows=50000`. It reports rather than guesses, and fails only
  on figures a person would notice.
- Rendered screenshots of every screen for every role:
  `./mvnw test -Dtest=ScreenWalkthroughTest -Dshot.dir=/some/folder`. **Look at them.** Four defects in the
  last five passes were found this way and by nothing else.
- The build produces `target/due-prog-03.jar`. To rehearse a clean installation, copy that jar to an empty
  directory *outside* the checkout and run it there: `AppPaths` anchors a jar inside this checkout's `target`
  to the checkout itself, so running it in place reuses the development workspace.

---

## 3. How it is put together

`ui → service → dao → model`, with `config` holding startup, migrations and the application context.

- **`AppContext`** builds everything in one constructor and is the only wiring. A new DAO or service is
  created there and exposed with a getter.
- **`DatabaseManager`** opens a connection per call — there is no pool, and the 50,000-package measurement
  says one costs under a millisecond. `runInTransaction` / `executeInTransaction` wrap multi-statement work.
- **`SchemaInitializer`** runs at every startup: it applies `schema.sql`, then a sequence of idempotent
  upgrade steps, then the migration classes, then records the schema version, registers this installation as
  a device and installs the change-log triggers.
- **`SessionContext`** holds the signed-in user, answers module access, and tells the database who is at the
  keyboard so the change log can record it.
- **`MainFrame`** maps view keys to screens. **Its sidebar groups are hand-written lists** — see §6.

---

## 4. The rules this code holds itself to

Each of these is enforced by a test, not by good intentions. Breaking one fails the build.

| Rule | Enforced by |
|---|---|
| Every business date and moment comes from `BusinessClock` (Europe/Budapest). No `LocalDate.now()`, `Instant.now()`, `Clock.system…`, SQLite's `datetime('now')` — in code or in the schema file. | `BusinessClockUsageTest` |
| A **moment** is a UTC instant, whole seconds, twenty characters (`2026-11-26T07:12:00Z`), in a column ending `_at`. A **business date** is a local date in a column ending `_day`, `_date`, `_on`, `_from`, `_until`. One writer and one reader: `DaoDateUtil`. | `TimestampConventionTest` reads every value in the database |
| Money and mass are exact decimal text, never floating point. Grams and minutes are integers so totals are exact. | `ExactDecimalStorageTest` |
| Shares are basis points, floored, so parts never exceed the whole. | `GradeYieldTest`, `DemoSortingHistoryTest` |
| Every translation key asked for by name exists, and no screen shows a raw `[key.name]`. | `TranslationKeyUsageTest`, `ScreenWalkthroughTest` |
| Every module a role can reach has a sidebar button. | `ScreenWalkthroughTest` |
| Every role has an account in the demo, or the walkthrough says so. | `ScreenWalkthroughTest` |
| Every change to the floor's data is logged by trigger, with the machine and the person. | `ChangeLogTest` |
| Every logged table says how two copies of it merge; the insert-only ones really are insert-only. | `SyncPolicyTest` scans the source for an `UPDATE` |
| A local day is asked for as a range of moments, never as a text prefix. | `TimestampConventionTest` |

Two more conventions that are habits rather than tests: a guard clause states *why* in its message, and a
comment says why rather than what.

---

## 5. How work is done here

Twenty-nine passes have followed the same shape, and it is worth keeping:

1. **Build it properly.** No new dependency has been added since the first pass.
2. **Verify with tests**, then **break each new guard on purpose** and confirm a test fails. Roughly a dozen
   of these per pass. They have repeatedly found tests that only agreed with themselves — most recently a
   list of logged tables that was compared against itself and would have accepted anything.
3. **Look at the rendered screens.** Not a substitute for tests; a complement that finds different defects.
4. **Write the pass down** in `docs/Optimization-Plan.md`: what was built, how it was verified, the mistakes
   made on the way, and — the section that matters most — **what was deliberately not done, and why**.
5. **Update the Hungarian reports** (`Projektterv`, `Adatbazisterv`, `Programterv`) in the same pass. They are
   university deliverables and they are kept current, including the test table.
6. `graphify update .` **before every commit**, so `graphify-out/graph.json` matches the tree.
7. One pass, one commit, pushed to `main`. The message explains the reasoning, not the diff.

**Refuse rather than guess.** This is the house rule and it has shaped several passes: no yield target was
shipped because the research has no honest figure for this site; no OEE performance or quality figure, because
neither a target rate nor a rework count exists; availability returns *null* rather than zero when nothing was
rostered; and the sync conflict policy decides nothing it cannot decide correctly. When a decision belongs to
the business, the application asks instead of inventing.

---

## 6. Landmines

Things that will bite somebody who does not know them.

- **`MainFrame`'s sidebar groups are hand-written module lists.** A module added to `AppModule` and forgotten
  there draws perfectly and is reachable by nobody. This happened with the shift handover in pass 24; a test
  now catches it.
- **Two timestamp spellings existed until pass 26.** Everything is converted and a migration fixes older
  databases, but any new SQL that compares an `_at` column to a *date* is wrong by one or two hours. Use
  `DaoDateUtil.dayRange`.
- **The change log costs about 0.12 ms per logged write.** Irrelevant for one bag, visible for fifty thousand.
  Anything that writes in bulk should run inside `ChangeLog.withoutLogging`, as the seeder and the migrations
  do.
- **Grade value is priced from the garment type, with the grade's price only as a fallback.** Every garment in
  the demo has a price, so a kilogram of cream is valued exactly like a kilogram of rags. The dashboard's mix
  row is ordered by value and therefore still looks like a weight ordering. This is a pricing decision nobody
  has made — see §8.
- **The demo seeder is versioned** (`SortingDemoSeeder.CURRENT_VERSION`, now 8) with an upgrade step per
  version. A new seeded feature needs a new version and an upgrade path, or existing demo workspaces never
  get it.
- **Today's demo work is anchored to the hour the demo is first opened**, so which shift it falls in is only
  known at that moment. The handover sheet for today is written for the shift the work actually finished in.
- **The mutation checks are a per-pass discipline with no standing record.** A guard written in pass 15
  breaking today would not announce itself. Each pass's checks are listed in its record.
- **`./mvnw clean verify` takes eleven minutes.** Run a single class while working.

---

## 7. The demo workspace

First launch offers: own company, demo workspace, or restore from backup. The demo accounts exist only in a
demo workspace, and the login screen shows quick-fill buttons only there.

| Account | Password | Role |
|---|---|---|
| `superadmin` | `superadmin123` | Super administrator |
| `admin` | `admin123` | Administrator |
| `manager` | `manager123` | Warehouse manager |
| `prodlead` | `prodlead123` | Production lead |
| `shiftlead` | `shiftlead123` | Shift leader |
| `payroll` | `payroll123` | HR and payroll |
| `operator` | `operator123` | Station manager |
| `forklift` | `forklift123` | Supply driver |
| `forklift2` | `forklift2123` | Delivery driver |
| `sorter01`–`sorter20` | `sorter123` | Sorters, four to each of five teams |
| `helper01`–`helper05` | `helper123` | Sorting helpers, one per team |

The demo opens with a week of work behind it: six days of finished sorting with a grade cascade and a residue,
today half-finished, realistic daily targets, a packing backlog, the week's stoppages including one still
running, and the week's handovers with the newest one not yet picked up and three issues open — one of them
escalated by the application itself at startup.

### A demo walkthrough

| Step | What to show |
|---|---|
| Open as a sorter | `sorter01` → To-Do. An open stoppage covering their team, and the day against their target. Before pass 25 this screen was empty. |
| Call for material | Sorting → request a stored lot. Then `forklift` → To-Do: the sorter is named, with how long they have been waiting. |
| Sort and report | Deliver as `forklift`, report graded bags as `sorter01`, close the run with the input weight. `helper01` packs; `operator` registers the bag and previews the QR label. |
| Read the floor | `prodlead` → Dashboard: minutes lost yesterday with availability, kilograms sorted over seven days with the grade mix, open handover issues. Then Sorting → Grade yield and Stoppages. |
| Hand the shift over | `shiftlead` → Shift Handover: the sheet for the shift that ended, its frozen figures, and Pick It Up. |
| Fulfil and invoice | `manager` → import or create an order, reserve stock, assign `forklift2` and a dock, scan at the dock, complete handover, issue the invoice from Payments. |
| Payroll | `payroll` → To-Do shows the last completed day's gaps; Payroll shows the draft. |
| The plumbing | `superadmin` → Admin → System: schema version, this device, the size of the change log. |

---

## 8. What is open, and who it waits on

**Waiting on a decision from the business, not on code:**

- **Payroll policy**, on a worked split-shift day: how monthly gross interacts with daily rates, how absences
  count, what qualifies a sorter, a helper or a team for a bonus. Until that is agreed, the draft stays a
  draft. Record it in `docs/Payroll-Implementation-Plan.md`.
- **Grade pricing**: whether a quality grade carries a price of its own. It would make the yield screen and the
  inventory valuation disagree on purpose, which is why nobody should do it quietly.
- **Two questions for an accountant**: whether exported or re-used placements can be deducted from the EPR
  base — commercially the largest open question in the research — and the second-hand margin scheme
  (Áfa tv. §§ 213–233), which no source covered and which may change what an invoice must contain.
- **Whether the operation ships outside the EU.** If it does, the Article 22d(9) bale record and the export
  document pack displace other work, and 21 May 2027 is a hard date.
- **Whether any held sync conflict has an answer in practice** (for example "the handheld is right about where
  a package is, because the person holding it is standing there"). One line in `SyncPolicy` when somebody says
  it out loud.

**Code work that is decided and ready to start:**

- **The handheld client.** The last research-roadmap item. All four of its prerequisites are built — one
  timestamp convention, a schema version, an outbox with device identity, a merge policy — and the two halves
  left are the transport and the client itself. Several passes, not one.
- **The submission bundle**: the three Hungarian reports are current, but the diagrams still need rendering at
  300 DPI and a ten-minute defence deck is not written.
- **Smaller leftovers**: the dialogs that open a file chooser or need a scanner are still only
  compile-verified; NAV has never been pointed at its own test environment; the bank, webshop import and
  outbound email remain local demonstrations; a demo workspace still cannot become a company workspace without
  a backup restore; backups are not encrypted and old `pre-restore-*` copies are deleted by hand; an invoice
  number is still assigned at draft creation rather than at issue; an S-shaped pick route waits on the
  physical layout.

The ordered account of everything built, with the reasoning and the mistakes, is
[the optimization plan](Optimization-Plan.md). The current audit of strengths and gaps is
[the state of the application](State-of-the-Application.md). The next actions are on
[the task board](TASKS.md).

---

## 9. Where to work

- **The floor** — `SortingService` (raw lots, requests, bags, targets), `DowntimeService`, `GradeYieldService`,
  `ShiftHandoverService`, `WorkListDao` and `TodoService` for what each role is shown.
- **Stock and fulfilment** — `InventoryService`, `TransportService`, `OrderService`, `PickListService`,
  `StocktakeService`.
- **Money** — `InvoiceService` and its lifecycle guard, `NavReportingService` and the `hu.due.rewear.nav`
  package (real, switched off until an administrator enters a technical user; credentials live in
  `data/nav-credentials.properties`, outside the database and outside backups), `PayrollService`.
- **Synchronisation** — `ChangeLog` (the triggers), `DeviceRegistry`, `SyncPolicy` (the merge rule and its
  reasons), `SyncService` (the decision procedure), `SchemaVersion`.
- **Time** — `BusinessClock` and `DaoDateUtil`. Never anything else.
- **The interface** — `AppModule` is the module registry, `MainFrame` the view map, `translations.csv` the
  only source of text, `BrutalistStyle` and `ThemeManager` the look.
- **Backups** — `BackupService`. A full `.zip` holds a database snapshot plus the invoice, receipt and mail
  attachment folders with a SHA-256 manifest; restoring verifies every checksum and the database's integrity
  before touching anything, moves the current documents to `data/pre-restore-<time>-files/`, swaps state in,
  rolls back on failure and signs the user out. A bare `.db` from an older version still restores.

## 10. The local workspace

The live database, demo edits, documents, preferences, exports, logs, the local JDK, the Graphify output and
the private planning notes (`PRD.md`, `PHASES_AND_TASKS.md`, the root `HANDOVER.md`,
`ASSIGNMENT_AND_DOCS_BRIEF.md`) are workspace files and are not tracked by Git.

For architecture questions in a checkout that has `graphify-out/graph.json`, query the local graph and verify
what it says against the source. Refresh it with `graphify update .` after code changes — and always before a
commit.

---

## 11. Starting a new session

Paste this as the first message of a fresh session. It is deliberately short: it names the two files to read,
the way work is done here, and the one thing an agent must not do, which is decide something that belongs to
the business. Everything else is in the repository and should be read rather than recited.

```text
You are picking up ReWear WMS: a Java 17 Swing + SQLite desktop warehouse application for a
second-hand textile operation. I am its only developer.

Read AGENTS.md first, then docs/HANDOVER.md, before changing anything. Between them they have
the build commands, the rules the build enforces and the test that enforces each, the things
that will bite you, the demo accounts, and what is open. Verify what they say against the
source as you go — several of them are claims a test checks, and you should know which.

Where it stands: thirty passes are written up in docs/Optimization-Plan.md, newest last.
365 tests in 64 classes; `./mvnw clean verify` is green and takes about eleven minutes.
docs/TASKS.md is the task board and docs/State-of-the-Application.md is the standing audit of
what is strong and what is missing.

How work is done here, and what I expect of you:
- One pass, one commit. Build it properly, verify with tests, then break each new guard on
  purpose and confirm a test fails. For anything that touches a screen, render it
  (-Dshot.dir=...) and look at it.
- Write the pass up in docs/Optimization-Plan.md, including what you deliberately did not do
  and why. Keep the three Hungarian reports current in the same pass.
- Run `graphify update .` before every commit.
- No new dependencies without asking. There have been none in thirty passes.
- Where a decision belongs to the business — a target, a price, a payroll rule, who wins when
  two machines disagree — ask me rather than inventing one. Refusing to guess is a feature of
  this codebase, and docs/HANDOVER.md §8 lists what is waiting on me.

Start by telling me what you make of where it stands and what you would do next. Do not start
building until I have answered.
```

If the session is for one specific piece of work rather than a general pick-up, say which — "the handheld
client", "the submission bundle", "grade pricing" — and point it at the relevant pass record, which will say
what was deliberately left undone and why.
