# Where the application stands

**Stock-taking · 26 November 2026, after optimization pass 24**

Twenty-four passes have been built against this application since 24 November, the last five of them from the
role-by-role research report. The research roadmap now has one item left on it, and it is the largest: a handheld
client. Before starting it, this is an honest account of what is here, what is actually verified, and what I would
do next — which is not the handheld.

---

## 1. What is here

| | |
|---|---|
| Java | 220 main classes, 46,939 lines; 57 test classes, 10,364 lines |
| Database | 55 tables, 37 indexes (36 in `schema.sql`, one created in code) |
| Layers | 28 DAOs, 47 services, 61 model types, 21 screens |
| Roles and menus | 11 roles, 21 modules, per-role module matrix |
| Languages | 1,074 translation keys, Hungarian and English |
| Tests | 277, of which 1 skipped (the 50,000-package load measurement) |
| Dependencies | unchanged since the first pass: Swing, FlatLaf, XChart, SQLite JDBC, JUnit 5 |

The business the application now covers, end to end: material is bought as a purchase lot with a supplier, a
country of origin, a price per kilogram and a named human judgement of whether it is waste or fit for re-use; it is
received as a collection line into a storage location; a sorter calls for it, sorts it against a daily target with a
team and helpers, and the graded output is weighed, labelled with a QR code, put away, reserved against an order,
staged on a route-sorted pick list, handed over with verification, and invoiced in one of several currencies with the
invoice reported to NAV and its lifecycle locked. Alongside that run the rota, the payroll draft, the stocktake with
quarantine, grade-yield reporting, the downtime log and the shift handover.

## 2. What is verified, and what is not

**Verified by test.** All 277 tests pass on `./mvnw clean verify`. Every screen each of the eleven roles can open
is rendered through the real main window and the rendered result looked at (`ScreenWalkthroughTest`); since pass 24
that test also asserts that every module a role may reach has a sidebar button, because one did not. Three modal
dialogs are driven too (`DialogWalkthroughTest`). One clock, one money representation and one weight representation
are enforced by guard tests rather than by convention.

**Verified by mutation.** Each pass since pass 15 deliberately broke each new guard to confirm a test caught it —
most recently fourteen checks across the downtime log and the handover. Two of this week's real defects were found
this way, and four more were found by looking at a rendered screenshot rather than by any test.

**Not verified.** The dialogs that open a file chooser or need hardware are still only compile-verified: first-time
setup, the invoice dialog's history table and status drop-down, the pick list's printed layout, opening a mail
attachment, and the Admin backup and restore buttons. NAV has been built properly but never pointed at NAV's own
test environment. The bank, the webshop import and outbound email are still local demonstrations. There is no
coverage measurement, and the mutation checks are a per-pass discipline with no record that older ones still hold —
a regression in a guard written in pass 15 would not announce itself.

---

## 3. Four findings, in the order I would act on them

### 3.1 Five of eleven roles open the application to an empty work list — *done in pass 25*

`TodoService.buildSystemTodosFor` branches on three cases: the two driver roles, the station manager, and
management. A production lead, a shift leader, an HR/payroll user, a sorter and a sorting helper fall through all
three and receive an empty list. Five of eleven roles, including the two the last two passes were built for and the
two that make up most of the workforce. Nothing is broken; the screen simply has nothing to say to them.

The dashboard has the same shape of problem. Its four figures are bales in stock, weight in stock, today's intake
and open orders, and its charts are stock on hand by quality grade and by garment type. Those measure the business
as it was understood before the research: throughput and stock. Nothing on the first screen anybody opens shows the
grade mix that came out of a lot, the minutes lost this shift, or that there is a handover issue nobody has picked
up. Every query needed for all three already exists and is already tested.

This is the cheapest work left and the most visible, and it is what makes the last five passes legible to the people
they were built for.

**Done on 26 November, pass 25.** Every role has a list; the supply driver gained the sorting floor, which was
missing from theirs as well; the dashboard gained a floor band for the roles that answer for the line; and the
walkthrough now takes one account per role instead of a hand-written list of six, which is what had hidden all of
it. One thing the pass could not finish: the mix row is ordered by what each grade earned, but grade value is
priced from the garment type, so cream is valued like rags and still does not appear. That is a pricing decision,
not a defect in the row.

### 3.2 The database has no version marker — *done in pass 26*

`SchemaInitializer` is 569 lines: it executes `schema.sql` idempotently, then adds eleven columns one by one if they
are missing, renames a role, inserts two others, migrates the NAV demo settings, and hands off to four separate
migration classes. It works, and every pass has added to it.

Two things follow. First, nothing records what version an installed database is, so the only way to know is to
inspect it for the columns a given pass added. Second, the DDL is applied by splitting the file on `;` — which is
safe today, because the schema contains no triggers, no views and no string literal with a semicolon in it, and
unsafe the first time it contains one.

**Done on 26 November, pass 26.** `schema_versions` records the shape the database is in, readable with one
query and no application, and the DDL is cut into statements by something that knows a semicolon inside a
string from one that ends a statement.

### 3.3 Two timestamp conventions coexist, and nothing enforces either — *done in pass 26*

The sorting module stores instants (`2026-11-26T07:12:00Z`). Purchase lots, the rota, the downtime log and the
handover store local date-times (`2026-11-26 07:12:00`). Both are defensible, and the application has one of each.

This has already cost twice this week. The demo seeder wrote sorting timestamps in local form and every sorting
screen threw a parse exception; and the handover's figures query has to convert its local shift window to UTC to
read the sorting half and keep it local to read the downtime half. `BusinessClock` has a guard test that stops a new
class reaching for `Instant.now()`. Nothing comparably stops a new table picking the wrong format, and the cost of
getting it wrong is a screen that throws rather than a figure that is slightly off.

**Done on 26 November, pass 26.** A moment is a UTC instant to the second and a business date is a local
date; one helper writes both, a migration converted everything already stored, and three guard tests hold it
— one reading every value in the database, one the code, one the schema file.

### 3.4 The research's headline finding is measured but not operative

The report's central claim is that premium "cream" grade is roughly a tenth of sorted weight and over half of
revenue, so grade mix rather than throughput is the profit lever. Pass 21 built the measurement: yield by grade per
lot, team, shift and sorter, with purchase and sale prices as editable inputs. It deliberately shipped no target,
because the research has no figure that would be honest for this site.

What has not changed is what anybody is measured on. The daily sorting target is still kilograms of input. The
payroll draft still shows output by worker in kilograms, and the bonus is a manually approved percentage of base
gross — it is not computed from throughput, which is the safe choice, but it is not computed from grade mix either.
So the floor still sees weight as the score.

This one is not blocked on code. It needs a few weeks of the site's own yield data to set a baseline against, and a
payroll policy decision about what a bonus is for. Both are listed as open in `TASKS.md` and both belong to the
business, not to me.

---

## 4. What the handheld client would need first

The handheld is the last roadmap item and was placed last on purpose: its evidence base is a mechanism and a
preference survey rather than a measured cost, and it brings the whole offline-synchronisation problem with it. Taking
stock sharpens that into something concrete. A handheld that works offline needs, at minimum:

- ~~**A schema version in the database**, so a device and the server can agree on what they are speaking.~~
  Done in pass 26: `schema_versions`, readable with one query.
- ~~**One timestamp convention**, because a record created on a device in a dead spot and merged an hour later
  is ordered by its timestamp and nothing else.~~ Done in pass 26: UTC instants to the second, held by three
  guard tests.
- **A change log the application writes to.** There is none. `invoice_events`, `nav_report_events`,
  `purchase_lot_events` and `downtime_events` are per-domain histories, not an outbox, and no table records that a
  row changed in a form another process could replay.
- **A device identity and a conflict policy**, neither of which exists in any form. Two sorters registering the same
  bag from two devices is the easy case; a stocktake count and a putaway racing over the same package is not.

Two of those four were findings 3.2 and 3.3, and they were built first on 26 November, which was the point:
they cost little outside a sync and a great deal inside one. The other two are untouched, and they are the
larger pair — an outbox changes how every write is recorded, and a conflict policy is a decision about whose
version of a row wins, which is a business question before it is a technical one.

## 5. Still open, and not mine to decide

- **The conditional swap.** If the operation ships outside the EU, the Article 22d(9) bale record and the export
  document pack displace other work. 21 May 2027 is a hard date.
- **Two questions for an accountant**: whether exported or re-used placements can be deducted from the EPR base —
  commercially the largest open question in the research — and the second-hand margin scheme (Áfa tv. §§ 213–233),
  which no source covered and which may change what an invoice must contain.
- **Payroll policy**, on a worked split-shift day: how monthly gross interacts with daily rates, how absences count,
  and what qualifies a sorter, a helper or a team for a bonus.
- **The rehearsal**: the handover walkthrough in a fresh portable installation, on the intended scanner and printer.
- **The submission bundle**: the three Hungarian reports are current, but the diagrams still need rendering at
  300 DPI, and `PRD.md` and the assignment brief still describe the v2.5 baseline.

## 6. Recommendation

Findings 3.1, 3.2 and 3.3 were all done on 26 November, in passes 25 and 26. What is left of this list is
3.4, which is a pricing decision rather than code, and of the handheld's prerequisites: a change log the
application writes to, a device identity, and a conflict policy. None of those exists in any form.

The handheld itself after that, with the offline problem stated rather than discovered.
