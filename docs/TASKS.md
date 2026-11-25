# ReWear WMS task board

**Updated · 25 November 2026 (after the optimization passes)**

## Completed in this checkpoint

- [x] Preserve the station manager account and add named sorting operators, helpers, and a separate demo fulfilment driver. Allow flexible team sizes and a helper to work across teams.
- [x] Record raw intake, request and delivery timestamps, actual processed input, graded output, actual packer, registration, and putaway. Report daily sorting progress against the input target.
- [x] Capture scheduled order demand, import and deduplicate local webshop orders, reserve available packages, and verify loading before invoicing scheduled orders.
- [x] Add the Admin-only payroll draft with date-effective HUF rates, shared shifts, daily output by worker and department, and manual bonus approval.
- [x] Add invoice and receipt archives, order-first invoicing, expense capture, private smart folders, searchable filters, in-app document email, and consistent table sorting.
- [x] Move invoice settings into Admin, add offline NAV and bank demos, and update the Hungarian project, database, and program reports.
- [x] Make the build reproducible (`./mvnw clean verify` with a Maven wrapper) and run it in GitHub Actions on Linux and macOS (workflow written, not yet run on GitHub).
- [x] Harden backup and restore: integrity-checked, atomic database restore, and a full `.zip` backup (database plus invoices, receipts and mail attachments, SHA-256 manifest) restored through a staging folder with rollback.
- [x] Store mail attachments in their own vault with type and signature checks, and keep one business clock (Europe/Budapest) with a guard test.
- [x] Separate the demo workspace from real onboarding: a new installation starts empty and asks (own company, demo, or restore); default-password accounts and sample data are no longer recreated.
- [x] Guard the invoice lifecycle (no return to draft, cancel only through a credit note), record every step, link credit notes structurally, enforce the Invoices module in the service, and show the history in the invoice dialog.
- [x] Add route-sorted pick lists with collected/total progress to Order Staging.
- [x] Bring the three Hungarian reports and the README up to date with the code (42 tables, 24 indexes, 847 keys, 36 test classes, 131 tests); details and the audit trail are in the [optimization plan](Optimization-Plan.md).

## Next session

- [ ] Push the work and watch the first GitHub Actions run; fix anything the Linux or macOS runner shows (fonts, file systems, headless behaviour).
- [ ] Click through what was only compiled or painted, not seen: the first-time setup dialog, the invoice dialog (history table, disabled buttons, status drop-down), the Pick list button and printed layout, the mail attachment open flow, and the Admin backup and restore buttons.
- [ ] Agree on payroll policy using a worked split-shift day. Confirm how monthly gross interacts with daily rates, how time and absences count, and how sorter, helper, and team results qualify for bonuses. Record those decisions in the [payroll plan](Payroll-Implementation-Plan.md) before extending the draft calculation.
- [ ] Rehearse the [handover walkthrough](HANDOVER.md#demo-walkthrough) in a fresh portable installation, choosing the demo workspace at first launch. Include a shortage, a rejected or duplicate scan, a shared helper, and a split payroll day. Test on the intended scanner and printer when available.
- [ ] When requested, assemble the university submission from the three Hungarian Markdown reports (now current), the runnable bundle, and a ten-minute defense deck. The diagrams still need rendering to PNG. Keep sample data fictional and rehearse from a clean demo database.

## After the demo

- [ ] Design payroll approval history, closed periods, corrections, and controlled export after the business rules are agreed.
- [ ] Add stocktake sessions and explicit damage, quarantine, and reweighing workflows. Consider an S-shaped pick route once the physical layout is known.
- [ ] Move money from SQLite REAL to integer minor units (a migration touching many files).
- [ ] Decide which roles may create invoices (from an order, manually, from a receipt) and gate that too; assign invoice numbers at issue instead of at draft creation.
- [ ] Let a demo workspace become a company workspace without a backup restore; offer encrypted backups and clean-up of old `pre-restore-*` copies.
- [ ] Measure scan-to-feedback and refresh latency under representative data volumes before deciding on a connection pool.
- [ ] Define actual webshop, NAV, bank, and external email integration requirements separately when providers and deployment details are known.
