# ReWear WMS codebase review

**2026-11-24 · v2.5 · Baseline commit `699b245`**

This is the audit of the November 24 baseline. Later passes are recorded in the [optimization plan](Optimization-Plan.md). See the [November 25 handover](HANDOVER.md) for the current workflow and the [task board](TASKS.md) for open work.

ReWear’s strongest workflow is the traceable movement of a physical package from sorting station to shelf, dispatch dock and customer. The handoff between operator, forklift driver and manager gives the university demo a clear story. The desktop architecture is appropriate for that demo. SQLite keeps setup small, and the separation between Swing views, services and DAOs makes the core workflows testable without hardware.

The review used the existing Graphify map, the handover and assignment brief, and direct inspection of startup and migrations, role checks, inventory, dispatch, finance, communications, file handling and background refresh. The baseline passed 50 JUnit tests. The regression checks exercise failures and retries in the existing workflow tests.

## Changes in this pass

| Finding | Change |
|---|---|
| A failed scan could change an order to `PACKING` before moving the package. Any dispatch zone appeared staged, and each moved package could raise a ready alert. | Validate the assigned driver and dock, destination type and capacity. Commit the package movement, dock and packing state together. Notify managers when the last package reaches the assigned dock. |
| An assigned pickup could be shipped through the status control with incomplete verification. Repeated scans and arrival clicks could add duplicate events. | Gate shipment on complete scans for pickup orders. Verify each package is still reserved at its assigned dock. Reopening or cancelling clears the prior handover state. Direct shipments without a pickup assignment still work. |
| Inventory editing or discard could invalidate an active reservation and its pricing. | Handle reservation and shipment status through orders. Require release before changing an allocated package’s label or weight or discarding it. |
| Every startup could assign unassigned open orders to the demo driver, restore a cleared operator station, or reinterpret a saved partner country or currency. | Run these backfills when the corresponding fields are added during migration. A normal startup preserves existing selections. |
| Opening the order document preview could issue an invoice, and reopening an issued invoice could rewrite its amounts from the current order. Paid invoices could be credited more than once. | Preview existing documents without issuing anything. Issue invoices from the invoice form, require at least one package for a new order invoice, and preserve issued snapshots. Prevent duplicate credits using the original-invoice reference already stored on credit-note lines. |
| A failed receipt replacement could leave the old database link pointing to a deleted file. A failed backup could delete the previous snapshot. | Copy receipts to a new vault file before replacing the database link. Protect the link against competing updates and clean up after failure. Build a new SQLite snapshot before replacing a backup. |
| Every QR scan loaded the whole staging queue, putaway made a separate query per station, and several badges loaded complete objects. Scan dialogs interrupted keyboard-wedge input. | Look up the scanned package by QR, fetch putaway in one query and use SQL counts for putaway, staging and overdue badges. Show scan feedback inline and retain the selected shelf or dock across refreshes. |
| External email could appear to have been transmitted even though delivery is simulated. | Label simulated delivery in the bilingual UI and in new mailbox records. Internal messages still use the local mailbox. |
| The staging table and user administration used missing translation keys for several labels. | Reuse existing English and Hungarian keys for order number, customer and email address. |
| Super Admin could not open Maintenance because the Roles & Modules table requested headings before its context was initialized. The avatar footer could also cover menu items in a short window. | Initialize the table after its context, cover the Super Admin screen with a regression test, and scroll the menu independently of the fixed profile footer. |
| The horizontal tabs wrapped across multiple rows at smaller window widths. | Use the Email-style collapsible section menu in Admin & Maintenance, Code Dictionaries, and Invoices & Finance. |

## Priorities for the warehouse workflow

1. **Picking by order and shelf.** Group assigned work by order, sort the pick list by aisle and shelf, and show the assigned dock and a scanned/total count. Keep the QR field focused and make a wrong-order, wrong-dock or damaged-label scan easy to recover from.
2. **Stocktake and exceptions.** Add a stocktake session scoped to a shelf or zone, with expected, found, missing and unexpected packages. Record damage, reweighing and quarantine as explicit operations with an owner and audit entry. Apply capacity consistently across intake, putaway and manual moves.
3. **Backup and restore as a complete operation.** Add a bundle for the SQLite snapshot, archived invoice PDFs, and receipt files. Restore into a staged copy, validate both database integrity and schema, then activate it with writes quiesced and a fresh login. The current restore replaces the database file while the app is running and the current snapshot covers SQLite only.
4. **Separate demo setup from real onboarding.** Keep the [demo accounts](../README.md#demo-accounts) and sample stock for the defense. Give a real warehouse first-run administrator setup, an empty database and explicit sample-data import. Review which service operations and data views should honor the module matrix.
5. **Finance and issuance.** Make draft review, issue and correction explicit UI steps. Add a structured original-invoice link for credit notes, decide how purchase and sales documents should appear in aging and P&L, and verify deployed tax rules separately from demo defaults.
6. **Measure under realistic load before changing the architecture.** Seed an isolated database with representative package, order and invoice volumes. Measure scan-to-feedback and screen refresh time. If several stations must work simultaneously, decide on shared data and assignment ownership before expanding past the single-machine desktop model.

The submission plan remains the three Hungarian reports, a ten-minute defense deck and the runnable project bundle. A clean demo path is operator intake, forklift putaway and staging, then the manager’s verified pickup. The manual workflow check should cover a wrong QR, a rejected dock move, a final scan that triggers the ready alert and a restart with a deliberately unassigned order.
