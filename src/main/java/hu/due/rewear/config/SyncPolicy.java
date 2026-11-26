package hu.due.rewear.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What to do when two machines have changed the same row since they last spoke.
 *
 * <p>There are two answers here and deliberately not three. The third — "the owner's change wins" — is the
 * one everybody reaches for, and it cannot be applied from what the log records: a trigger knows which
 * machine a change came from, and a machine is not a person. A sorter's own run edited by the shift leader
 * from the office is not the same case as the same run edited twice by two sorters, and nothing in the log
 * tells the two apart. So the log was given the person as well as the machine, and even then the rule for
 * who outranks whom is the site's to state, not this application's to assume.
 *
 * <p>What is left is a question about the data rather than about the company, and that one does have an
 * answer:
 *
 * <ul>
 *   <li>{@link Merge#APPEND} — rows that are inserted and never updated afterwards. Two machines cannot
 *       disagree about such a row, because neither of them changes one: they each add their own. Bringing
 *       the two sets together is the whole of the merge, and a guard test holds these tables to it by
 *       refusing any {@code UPDATE} statement against them anywhere in the application.</li>
 *   <li>{@link Merge#HELD} — everything else. Where two machines have genuinely changed the same row, no
 *       rule this application could ship would be right every time: two sorters reporting work against one
 *       run are both telling the truth, while a stocktake count and a putaway that disagree about where a
 *       package is cannot both be right. The change is recorded as a conflict and a person decides.</li>
 * </ul>
 *
 * <p>Holding a conflict for a person is a worse answer than resolving it automatically, and a far better
 * one than resolving it wrongly without saying so. Last-write-wins would quietly throw away one of two
 * correct answers every time it met the first case above, and the site would find out months later from a
 * figure that did not add up.
 */
public final class SyncPolicy {

    public enum Merge {
        /** Insert-only: bring both sets together and nothing is lost. */
        APPEND,
        /** Two machines changed the same row: record it and let somebody look. */
        HELD
    }

    /** Why a table is classified the way it is, in the words somebody resolving a conflict would want. */
    public record Rule(Merge merge, String reason) { }

    private static final Map<String, Rule> RULES = rules();

    private SyncPolicy() {
    }

    private static Map<String, Rule> rules() {
        Map<String, Rule> rules = new LinkedHashMap<>();
        rules.put("stock_movements", new Rule(Merge.APPEND,
                "A movement is a record of something that happened. Nobody edits one; both machines' movements are true."));
        rules.put("sorting_request_helpers", new Rule(Merge.APPEND,
                "Who helped on a run is added, never changed. Two machines naming different helpers both named real ones."));
        rules.put("stocktake_counts", new Rule(Merge.APPEND,
                "A count is what one person found at one shelf at one time. Two counts are two findings, and the "
                        + "stocktake is the place that already knows what to do with a disagreement."));

        rules.put("inventory_packages", new Rule(Merge.HELD,
                "A package is in one place. If two machines moved it, one of them is describing a shelf it is not on."));
        rules.put("orders", new Rule(Merge.HELD,
                "An order's state is the shared story of a customer. Two machines advancing it differently is a "
                        + "disagreement about what has been promised."));
        rules.put("order_items", new Rule(Merge.HELD,
                "A line's price or scanned state is a fact about one order line, and two machines cannot both be right."));
        rules.put("purchase_lots", new Rule(Merge.HELD,
                "The legal assessment of material is a named human judgement. Two of them is a question for the "
                        + "person whose name is on it, not an arithmetic problem."));
        rules.put("sorting_raw_lots", new Rule(Merge.HELD,
                "What is left of a collection is a running total, and the log carries what changed rather than by "
                        + "how much. Two machines each taking 500 kg from 2,000 would both record 1,500."));
        rules.put("sorting_requests", new Rule(Merge.HELD,
                "A run is one sorter's, and the input weight it is measured by is entered once. Two answers to "
                        + "'how much went through' is the one number nobody may guess at."));
        rules.put("sorting_bags", new Rule(Merge.HELD,
                "A bag is reported once and then packed and registered by other people. Two machines at different "
                        + "steps of the same bag need looking at."));
        rules.put("downtime_events", new Rule(Merge.HELD,
                "A stoppage is opened by one person and closed by another, with a reason. Two closings are two "
                        + "different accounts of why the line stood still."));
        rules.put("shift_handovers", new Rule(Merge.HELD,
                "One sheet per shift, and the database says so. Two machines writing it means two people each "
                        + "think they handed the shift over."));
        rules.put("shift_handover_issues", new Rule(Merge.HELD,
                "An issue has one owner and one resolution. Two resolutions means somebody closed something that "
                        + "was not theirs, or two people fixed it twice."));
        rules.put("stocktake_sessions", new Rule(Merge.HELD,
                "A stocktake is opened, counted and closed as one piece of work."));
        return Map.copyOf(rules);
    }

    /** How a table merges, and why. Every logged table has a rule; nothing else has one. */
    public static Rule rule(String table) {
        Rule rule = RULES.get(table);
        if (rule == null) {
            throw new IllegalArgumentException("No merge rule for " + table
                    + ": a table that is logged must say how two copies of it come back together");
        }
        return rule;
    }

    public static boolean isAppendOnly(String table) {
        return rule(table).merge() == Merge.APPEND;
    }

    /** Every table a rule has been written for, which must be every table the change log covers. */
    public static java.util.Set<String> tables() {
        return RULES.keySet();
    }
}
