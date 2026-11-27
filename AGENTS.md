# Working on ReWear WMS

Java 17 Swing + SQLite desktop warehouse application for a second-hand textile operation. Single maintainer.
Read [docs/HANDOVER.md](docs/HANDOVER.md) before changing anything substantial — it has the architecture, the
demo accounts, the landmines and what is open. This file is only the things you cannot work out by looking.

## Build and verify

```sh
export JAVA_HOME="$PWD/.runtime/jdk-17/Contents/Home"   # a symlink into a disposable cache; any JDK 17 works
./mvnw -o -q test -Dtest=ClassName                      # one class, seconds
./mvnw clean verify                                     # everything, about eleven minutes
```

- The Maven wrapper is script-only and downloads Maven on first use, so the first build needs the network.
- Headless works; the four display-dependent test classes skip themselves.
- The load measurement is opt-in: `./mvnw test -Dtest=LoadMeasurementTest -Dload.rows=50000`.
- **Look at UI changes**: `./mvnw -o test -Dtest=ScreenWalkthroughTest -Dshot.dir=/some/folder` writes a PNG
  of every screen for every role. Several defects in recent passes were found this way and by nothing else.

## Before every commit

Run `graphify update .` — the committed tree and `graphify-out/graph.json` are expected to match. For
architecture questions, query that graph first and verify what it says against the source.

## Rules the build enforces

Breaking one of these fails a test rather than a review. Before writing code that touches time, money, mass,
text or the database, read the relevant one.

| Rule | Test |
|---|---|
| All time comes from `BusinessClock` (Europe/Budapest). No `LocalDate.now()`, `Instant.now()`, `Clock.system…`, SQLite `datetime('now')` — in code or in `schema.sql`. | `BusinessClockUsageTest` |
| A moment is a UTC instant to the second in a `_at` column; a business date is a local date in a `_day`/`_date`/`_on`/`_from`/`_until` column. Write and read both through `DaoDateUtil`. Ask for a local day with `DaoDateUtil.dayRange`, never a text prefix. | `TimestampConventionTest` |
| Money and mass are exact decimal text. Grams and minutes are integers. | `ExactDecimalStorageTest` |
| Shares are basis points, floored. | `GradeYieldTest` |
| Every translation key exists and no screen shows `[key.name]`. All text comes from `translations.csv`. | `TranslationKeyUsageTest` |
| Every module a role can reach has a sidebar button, and every role has a demo account. | `ScreenWalkthroughTest` |
| Changes to the floor's data are logged by trigger; every logged table declares how two copies of it merge. | `ChangeLogTest`, `SyncPolicyTest` |
| Figures quoted in the current documents match the tree, and links between documents resolve. | `DocumentationFiguresTest` |

## Things that will bite you

- **`MainFrame`'s sidebar groups are hand-written module lists.** A module added to `AppModule` and forgotten
  there draws correctly and is reachable by nobody.
- **Bulk writes should run inside `ChangeLog.withoutLogging`** — the change-log triggers cost about 0.12 ms
  per write, which only matters in thousands.
- **The demo seeder is versioned** (`SortingDemoSeeder.CURRENT_VERSION`): a new seeded feature needs a new
  version and an upgrade step, or existing demo workspaces never get it.
- **No new dependencies** without asking. There have been none in thirty passes.

## Do not touch

`data/`, `.runtime/`, `graphify-out/`, `logs/`, `exports/` are local workspace, not source. `PRD.md`,
`PHASES_AND_TASKS.md`, `ASSIGNMENT_AND_DOCS_BRIEF.md` and the root `HANDOVER.md` are the maintainer's private
planning files and are not tracked. The figures inside `docs/Optimization-Plan.md` pass records are history:
do not "correct" them.

## How work is done here

Build it, verify it with tests, then **break each new guard on purpose and confirm a test fails**. Look at the
rendered screens. Write the pass up in `docs/Optimization-Plan.md` including what was deliberately *not* done
and why. Update the three Hungarian reports in the same pass. Refresh the graph. One pass, one commit.

Where a decision belongs to the business — a target, a price, a payroll rule, who wins a sync conflict — the
application asks instead of inventing. Refusing to guess is a feature of this codebase, not an omission.
