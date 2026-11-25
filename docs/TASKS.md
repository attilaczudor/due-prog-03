# ReWear WMS task board

**Updated · 25 November 2026 (after the optimization passes)**

## Completed in this checkpoint

- [x] Preserve the station manager account and add named sorting operators, helpers, and a separate demo fulfilment driver. Allow flexible team sizes and a helper to work across teams.
- [x] Record raw intake, request and delivery timestamps, actual processed input, graded output, actual packer, registration, and putaway. Report daily sorting progress against the input target.
- [x] Capture scheduled order demand, import and deduplicate local webshop orders, reserve available packages, and verify loading before invoicing scheduled orders.
- [x] Add the Admin-only payroll draft with date-effective HUF rates, shared shifts, daily output by worker and department, and manual bonus approval.
- [x] Add invoice and receipt archives, order-first invoicing, expense capture, private smart folders, searchable filters, in-app document email, and consistent table sorting.
- [x] Move invoice settings into Admin, add offline NAV and bank demos, and update the Hungarian project, database, and program reports.
- [x] Make the build reproducible (`./mvnw clean verify` with a Maven wrapper; no CI service is used, the build is run locally, and the headless run was checked once).
- [x] Harden backup and restore: integrity-checked, atomic database restore, and a full `.zip` backup (database plus invoices, receipts and mail attachments, SHA-256 manifest) restored through a staging folder with rollback.
- [x] Store mail attachments in their own vault with type and signature checks, and keep one business clock (Europe/Budapest) with a guard test.
- [x] Separate the demo workspace from real onboarding: a new installation starts empty and asks (own company, demo, or restore); default-password accounts and sample data are no longer recreated.
- [x] Guard the invoice lifecycle (no return to draft, cancel only through a credit note), record every step, link credit notes structurally, enforce the Invoices module in the service, and show the history in the invoice dialog.
- [x] Add route-sorted pick lists with collected/total progress to Order Staging.
- [x] Bring the three Hungarian reports and the README up to date with the code (42 tables, 24 indexes, 847 keys, 36 test classes, 131 tests); details and the audit trail are in the [optimization plan](Optimization-Plan.md).

- [x] Track raw material as daily collection lines (material, collection area, storage location). A sorter now calls for a line instead of typing kilograms, since the delivered weight is only known when the driver brings it.

- [x] Give the company a role hierarchy: production manager and shift leader above the floor, an HR/payroll role, and the single forklift role split into supply (sorting floor) and delivery (customer orders). Menus follow from the role.

- [x] Add the shift rota as its own main menu with month, week and day calendar views, written by the planning roles and visible to everyone for their own days. The demo workspace is filled with a month of rotating shifts.

## Next session

- [x] Walk every screen each role can open, through the real main window, and look at the rendered result. Covered by `ScreenWalkthroughTest`; remaining dialogs (invoice details, pick list preview, package intake preview) are still only compile-verified.
- [x] Drive the modal dialogs too (`DialogWalkthroughTest`): invoice details with its history and permission-dependent buttons, and the pick list preview.
- [ ] Still only compile-verified, because they open a file chooser or need a scanner: the first-time setup dialog, the invoice dialog (history table, disabled buttons, status drop-down), the Pick list button and printed layout, the mail attachment open flow, and the Admin backup and restore buttons.
- [ ] Agree on payroll policy using a worked split-shift day. Confirm how monthly gross interacts with daily rates, how time and absences count, and how sorter, helper, and team results qualify for bonuses. Record those decisions in the [payroll plan](Payroll-Implementation-Plan.md) before extending the draft calculation.
- [ ] Rehearse the [handover walkthrough](HANDOVER.md#demo-walkthrough) in a fresh portable installation, choosing the demo workspace at first launch. Include a shortage, a rejected or duplicate scan, a shared helper, and a split payroll day. Test on the intended scanner and printer when available.
- [ ] When requested, assemble the university submission from the three Hungarian Markdown reports (now current), the runnable bundle, and a ten-minute defense deck. The diagrams still need rendering to PNG. Keep sample data fictional and rehearse from a clean demo database.

## Roadmap from the 25 November research

The full reasoning, evidence and per-role detail is in [the research report](../reports/Warehouse%20system%20improvements%20by%20role.md).
Its headline finding: the application sets and bonuses against kilograms processed, but premium "cream" grade is
roughly a tenth of sorted weight and over half of revenue — grade mix, not throughput, is the profit lever.

Build in this order:

- [x] **Exact-decimal weights.** Done 25 November, pass 18. Smallest of the five and a precondition for three of the others. Money was fixed
      the same way; weight carries an EPR penalty of half the fee deficiency, a 100 g stocktake tolerance and
      contractual bale tolerances of a few percent.
- [ ] **A real NAV Online Számla integration** replacing the demonstration. Immediate XML submission on issue, a
      per-invoice reporting state machine with the transaction id stored as evidence, retry on outage, an operator
      queue for errors, and technical annulment modelled apart from a commercial credit note. Penalty up to
      HUF 1,000,000 per invoice; fifteen warnings became blocking errors on 15 September 2025.
- [ ] **A purchase lot and intake assessment record.** The schema keystone: supplier, country of origin, collection
      channel, purchase price per kilogram, gross and net weight, customs classification, and the named human
      judgement of whether material is waste or fit for re-use at collection. Retires the free-text collection area.
- [ ] **Grade-yield reporting and a target that uses it.** Yield by grade per intake lot, team, shift and sorter,
      with purchase and sale prices as editable inputs. Learn the site's own baseline rather than shipping a target
      from the literature.
- [ ] **Shift handover record with 48-hour escalation.** The cheapest high-value item: six structured fields at
      shift close, escalation through the notification infrastructure that already exists.
- [ ] *Conditional swap:* if the operation ships outside the EU, the Article 22d(9) bale record and export document
      pack displace the handover record — 21 May 2027 is a hard date and a handover sheet is not.
- [ ] A handheld client is the right sixth item, not the first: it brings the whole offline-synchronisation problem
      with it, and its evidence base is a mechanism and a preference survey rather than a measured cost.

Two questions for an accountant before any further invoicing work: whether exported or re-used placements can be
deducted from the EPR base (the most commercially significant open question in the research), and the second-hand
margin scheme (Áfa tv. §§ 213–233), which no source covered and which may change invoice content.

## After the demo

- [ ] Design payroll approval history, closed periods, corrections, and controlled export after the business rules are agreed.
- [x] Add stocktake sessions with damage, reweighing and quarantine. Damaged stock is held aside under its own status until somebody returns it to stock or writes it off.
- [ ] Consider an S-shaped pick route once the physical layout is known. Consider an S-shaped pick route once the physical layout is known.
- [x] Store money exactly. Chose decimal text over integer minor units, because minor units need the right scale at every read and write and one wrong scale changes an amount a hundredfold.
- [ ] Decide which roles may create invoices (from an order, manually, from a receipt) and gate that too; assign invoice numbers at issue instead of at draft creation.
- [ ] Let a demo workspace become a company workspace without a backup restore; offer encrypted backups and clean-up of old `pre-restore-*` copies.
- [x] Measure under a realistic warehouse (50,000 packages). No connection pool is needed; the inventory screen now loads a bounded slice with exact totals, which took it from 1.9 s to 0.08 s.
- [ ] Define actual webshop, NAV, bank, and external email integration requirements separately when providers and deployment details are known.
