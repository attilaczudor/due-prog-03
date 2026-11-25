# Sorting to sale implementation plan

## What the workflow builds on

- Package intake creates a graded package, a QR label, and an intake movement. The existing `operator` account performs this station work. Its database role code is `OPERATOR` and it can have a labelling station assigned.
- Finished station packages enter the putaway queue. Forklift drivers relocate them to storage using the existing warehouse locations and stock movements.
- Orders already reserve individually identified packages, assign a driver and collection dock, track customer arrival, and support scanning each package before handover. Before this change, some order paths permitted unverified shipment and creation of invoices from open orders.
- Dedicated production records now cover raw textile receipts, rosters, raw requests, processed input, graded output before labelling, and daily target eligibility. Record raw weight when the material is physically received.

## Operating workflow

| Step | Owner | Evidence recorded |
| --- | --- | --- |
| Receive unsorted stock | Warehouse management | Raw lot reference, received weight, receipt time, receiver, remaining weight |
| Request more stock | Sorting operator | Request time, requested weight, sorter, team, rostered helpers, driver, and destination labelling station |
| Deliver stock | Supply forklift driver | Raw lot, actual delivered weight, driver, and delivery time |
| Grade and report output | Sorting operator | Raw delivery, product group and type, quality, net output weight, and reporting time |
| Pack and hand over | Sorting helper | Packaging type, actual helper, packing timestamp and workday |
| Register and label | Station manager | Station, finished package ID and QR code, registering user, timestamp, and inventory intake movement |
| Put away | Forklift driver | Source station, storage location, relocation scan, driver, and time |
| Fulfil an order | Warehouse management and fulfilment driver | Delivery date, demand, availability, allocation, assigned driver, collection dock, customer arrival, each load scan, and shipment |
| Invoice | Finance | Outgoing invoice generated from the fulfilled order and its locked package lines |

A team coordinates a product area such as kids, shirts, coats, or home textiles. Team rosters support multiple sorters and helpers without a size cap. Twenty sorters and five helpers provide a representative demo roster. A second forklift login makes it possible to demonstrate separate supply and order fulfilment assignments.

## Implementation steps

1. **Separate roles and preserve existing access.** Relabel `OPERATOR` as Station Manager / Állomásvezető while preserving role IDs, account handles, current station assignments, and module preferences. Add assignable `SORTER` and `SORTING_HELPER` roles to Users and the Super Admin permission matrix. Keep administrative and finance capabilities out of the default sorter and helper profiles. Seed the roster once in recognised demo databases.
2. **Build the raw-stock ledger and routing.** Give managers a raw receiving screen and team controls. Snapshot the responsible people and station when a sorter requests a batch. Notify the assigned supply driver in the same transaction. Delivery consumes weight from the selected lot and confirms actual weight and time. An undelivered request may be cancelled. A short delivery remains visible at its actual weight and any outstanding need can be requested separately.
3. **Track output separately from work performed.** Let a sorter report graded output during an open delivery. The helper selects the physical packaging and sends it to the station. The station manager registers each bag once using the existing QR intake and movement logic. The station can reprint an existing label. The registered bag enters the existing putaway queue. Scheduled orders allocate packages that have reached active storage.
4. **Close and reconcile each raw delivery.** Record the net raw weight actually processed. Return the unprocessed balance to the originating lot when the material is physically returned. The recorded output cannot exceed either the delivered or the final processed weight. The raw request history lists delivered, processed, returned, reported output, and other processed weight for reconciliation. Quantities are stored as integer grams. Finished packages use up to two decimal places in kilograms to match the existing QR format.
5. **Show daily progress and bonus eligibility.** Use the warehouse business day in Europe/Budapest and a default target of 1,000 kg of processed input per sorter. Count closed deliveries once on their completion day. Record the team target and bonus rate in effect when each request is placed. Use the highest applicable target if the operator switches policies during a day. Qualifying rows identify the sorter and the rostered helpers. The payroll draft counts each bag for its actual packer and applies approved shift bonuses. Any rules for sharing a bonus or limiting awards per day are open decisions.
6. **Capture order demand before fulfilment.** Store a scheduled date and the requested product combination and quantity. Show shortages against physically available, registered packages in active storage. Make packaging, group, product, and grade explicit. Reserve exact packages in a single transaction only when the order can be filled. A repeat webshop submission is matched by source and external ID. Use a local import format until a specific webshop API and credentials are supplied.
7. **Verify collection and handover before invoicing.** Assign a fulfilment driver and dock before collection. Only the assigned driver handles their scheduled staging work. Mark customer arrival and scan each reserved package at the assigned dock as it is loaded. Reject wrong-order and wrong-location QR codes. Duplicate scans are idempotent. Shipment requires all expected units and every scan. Outgoing order invoicing in Payments becomes available after shipment. Existing invoices remain readable. Unscheduled historical orders retain their original persistence compatibility, while shipping through Orders opens the verified handover dialog.
8. **Verify the workflow under realistic exceptions.** Cover insufficient raw stock, wrong driver, helper from another team, repeated registration, reversed or missing scans, duplicated webshop imports, reserved stock contention, and incomplete orders. Run a full worked example from raw receipt through invoice, then repeat against a copy of a pre-change database to verify migration and existing history.

## Worked example

A Kids sorter requests 400 kg. The supply driver delivers it from raw lot R-001 and the operator reports a 15 kg baby cream output using the existing product and grade dictionaries. The Kids helper selects a clear bag and sends it to the shared labelling station. The station manager registers the bag and prints its QR label. It reaches storage through a putaway scan.

The sorter can process 400 kg, then 400 kg, then 200 kg during one day and meet the 1,000 kg target even when the finished yield is much lower. The operator and assigned helpers appear on the sorting report. The payroll performance records identify the helper who packed each bag.

A scheduled order requests that graded product and its packaging. The stock check assigns the 15 kg bag only if it is available. The fulfilment driver collects it to the order dock. Once the customer arrives, the bag is scanned as it enters the vehicle. Completing the verified handover makes the order eligible for an invoice.

The [payroll plan](Payroll-Implementation-Plan.md) covers daily department assignments, wage rates and bonus approval.

## Local webshop import contract

In **Orders → Import webshop order**, select one UTF-8 `.properties` file per order. Use an active buyer ID and the existing dictionary codes. Each product row contains target group, product type, grade, packaging, and quantity in that order. Choose the fulfilment driver and dock after import.

```properties
source=example-webshop
external_id=SHOP-100
customer_id=1
currency=EUR
delivery_date=2026-11-25
product.1=B,MIX,CR,B,1
```

A second import with the same source and external ID opens the existing order. Confirm requested quantities before allocation. The parser resolves catalogue codes to active dictionary entries.

## Decisions for production rollout

- The first version uses one raw lot per delivery. When a request needs material from multiple lots, split the delivery into separate requests to preserve traceability.
- Bonus percentage is configurable. The app reports eligibility, with an unset rate visible. Payroll payment and mid-shift cutoff rules need a business decision. Close a delivery at shift end if it must contribute to that day.
- Product teams are work assignments. The dictionary defines the actual grade and product recorded for each bag.
- Webshop orders can be received via the local import contract. A live connector requires the chosen webshop platform, authentication method, and catalogue mapping.

## Code entry points

- Production records and actions live in [SortingService](../src/main/java/hu/due/rewear/service/SortingService.java) and the [Sorting workspace](../src/main/java/hu/due/rewear/ui/SortingView.java).
- Scheduled demand and reservation use [OrderService](../src/main/java/hu/due/rewear/service/OrderService.java) and [OrderDemandDao](../src/main/java/hu/due/rewear/dao/OrderDemandDao.java).
- Import, loading, and invoicing reuse the [Webshop order file reader](../src/main/java/hu/due/rewear/service/WebshopOrderFile.java), [handover dialog](../src/main/java/hu/due/rewear/ui/OrderHandoverDialog.java), and [invoice service](../src/main/java/hu/due/rewear/service/InvoiceService.java).
- The [sorting-to-sale regression test](../src/test/java/hu/due/rewear/service/SortingToSaleWorkflowTest.java) exercises a complete run and checks ownership, shortages, repeat imports and scans, and invoice eligibility.
