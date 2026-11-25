# Payroll and daily performance

## First version

**Bérszámfejtés / Payroll** is available to Admin and Super Admin. It includes all workers, date-effective monthly gross pay, department and duty rates, daily shift allocations, and production recorded on the selected date. Gross amounts use HUF. The starting tables contain no wage or bonus amounts.

| Record | What it represents |
| --- | --- |
| Worker and monthly gross | The worker's effective gross monthly reference and its start date |
| Department and duty rate | Gross pay for a full workday, bonus percentage, and effective date |
| Daily shift | Worker, workday, department, duty, share of the day and bonus approval |
| Sorter production | The sum of raw kilograms processed on closed deliveries by worker, department and workday |
| Helper production | Bags and finished weight recorded against the helper who packed them, by department and packing date |
| Draft gross | Prorated department base plus any approved percentage bonus for that shift |

A worker can cover several departments on the same day. For example, 50% of a 16,000 HUF Kids shift and 50% of an 18,000 HUF Home Textiles shift gives 17,000 HUF in draft base pay. If the Kids shift has an approved 10% bonus, the draft total is 17,800 HUF. The amounts are examples only.

The draft applies rates effective on the selected workday. It rounds each shift's base and approved bonus to whole HUF. A missing daily rate leaves the draft amount blank. The monthly gross reference remains available for payroll review. The draft rate is entered independently from that reference.

## Teams and attribution

- Sorting teams support any number of sorters and helpers. A helper can be rostered to multiple teams. A sorter keeps a home team for raw requests.
- Requests capture the assigned helpers at the time of the request. Each bag records the helper who actually packed it and the warehouse date. That makes it possible to count a helper's work in multiple departments without giving the same packing credit to everyone on the team.
- A payroll day entry records the department and duty actually worked. Management can compare allocation, sorter kilograms, and helper output before approving a bonus.
- Existing single-helper assignments migrate to team and request memberships. Packing history from that version is attributed to the helper recorded on the request.

## Decisions before production payroll

1. Confirm whether monthly salaried and daily paid workers use different contracts. Define how to derive daily pay for salaried staff when no department rate applies and whether a department rate replaces or supplements that amount.
2. Decide how shifts are confirmed. The draft uses an entered share of a standard day and caps the daily total at 100%. Agree on start/end times, paid breaks, overtime, night work, absences and the shift crossing midnight before adding those calculations.
3. Decide which performance earns each bonus. Keep a sorter's input target, a helper's packing work and any team bonus as distinct evidence. Define credit when several helpers support the same work and when someone changes departments during the day.
4. Confirm approval and correction rules. Use separate approved records and adjustments, freeze rates and evidence when closing a pay period, and record who approved each change. Enforce payroll visibility for authorized staff.
5. Validate agreed gross examples with payroll before adding net-pay calculations, statutory rules, payslips, exports or payment processing.
