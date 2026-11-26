package hu.due.rewear.config;

import hu.due.rewear.dao.DowntimeDao;
import hu.due.rewear.model.LossGroup;
import hu.due.rewear.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The stoppage reasons an installation starts with.
 *
 * <p>Nineteen reasons across the eight loss groups, at most three to a group. The shortness is the point:
 * lean practice on downtime coding is consistent that a list of around eight groups with fewer than ten
 * reasons each is what gets used honestly, and that a plant running two hundred reasons on a touchscreen gets
 * operators picking the first plausible option while the line is still stopped. The wording here is a
 * starting point in plain floor language, and the production lead is expected to rewrite it — the list is
 * meant to be reviewed every couple of weeks, which is why it lives in the database and not in this file.
 *
 * <p>Only an empty list is filled. A company that has retired half of these and written its own is never
 * topped back up with ours.
 */
public final class DowntimeReasonStarterList {
    private static final Logger LOG = LoggerFactory.getLogger(DowntimeReasonStarterList.class);

    /**
     * @param attributable whether the minutes are the crew's own. Three of these are: a break that ran over,
     *                     re-grading their own bags, and a changeover they control. Waiting for a forklift,
     *                     a power cut and a stocktake are not, and holding those against a bonus is how a
     *                     scheme stops being believed.
     */
    private record Starter(String code, LossGroup group, String nameEn, String nameHu, boolean attributable) { }

    private static final List<Starter> STARTERS = List.of(
            new Starter("SUP-WAIT", LossGroup.SUPPLY, "No material at the bench",
                    "Nincs anyag a munkahelyen", false),
            new Starter("SUP-LATE", LossGroup.SUPPLY, "Forklift has not brought it yet",
                    "A targonca még nem hozta oda", false),
            new Starter("SUP-WRONG", LossGroup.SUPPLY, "Wrong line delivered",
                    "Rossz tétel érkezett", false),
            new Starter("EQP-SCALE", LossGroup.EQUIPMENT, "Scale not working",
                    "A mérleg nem működik", false),
            new Starter("EQP-LABEL", LossGroup.EQUIPMENT, "Scanner or label printer down",
                    "Szkenner vagy címkenyomtató áll", false),
            new Starter("EQP-BALE", LossGroup.EQUIPMENT, "Baler jammed",
                    "A bálázó beakadt", false),
            new Starter("QUA-REGRADE", LossGroup.QUALITY, "Re-grading our own bags",
                    "Saját zsákok újraosztályozása", true),
            new Starter("QUA-DIRTY", LossGroup.QUALITY, "Material wet or contaminated",
                    "Nedves vagy szennyezett anyag", false),
            new Starter("CHG-LINE", LossGroup.CHANGEOVER, "Changing material or target group",
                    "Anyag- vagy célcsoportváltás", true),
            new Starter("CHG-CLEAR", LossGroup.CHANGEOVER, "Clearing the bench between lots",
                    "Munkahely ürítése tételek között", true),
            new Starter("FAC-POWER", LossGroup.FACILITY, "Power or lighting failure",
                    "Áram- vagy világításkimaradás", false),
            new Starter("FAC-COLD", LossGroup.FACILITY, "Heating, cold or water",
                    "Fűtés, hideg vagy víz", false),
            new Starter("STA-BREAK", LossGroup.STAFFING, "Break ran over",
                    "A szünet túlnyúlt", true),
            new Starter("STA-SHORT", LossGroup.STAFFING, "Short-handed, somebody absent",
                    "Létszámhiány, hiányzás", false),
            new Starter("STA-TRAIN", LossGroup.STAFFING, "Showing a new sorter the work",
                    "Új válogató betanítása", false),
            new Starter("ADM-MEET", LossGroup.ADMIN, "Shift meeting or instruction",
                    "Műszakértekezlet vagy eligazítás", false),
            new Starter("ADM-COUNT", LossGroup.ADMIN, "Stocktake or count",
                    "Leltár vagy számlálás", false),
            new Starter("EXT-DOCK", LossGroup.EXTERNAL, "Dock blocked, loading or unloading",
                    "A kapu foglalt, be- vagy kirakodás", false),
            new Starter("EXT-VISIT", LossGroup.EXTERNAL, "Inspection or visitors on the floor",
                    "Hatósági ellenőrzés vagy látogatók", false));

    private final DatabaseManager db;

    public DowntimeReasonStarterList(DatabaseManager db) {
        this.db = db;
    }

    public void ensure() {
        DowntimeDao dao = new DowntimeDao(db);
        db.runInTransaction(conn -> {
            if (hasAny(conn)) {
                return;
            }
            var now = BusinessClock.now();
            for (Starter starter : STARTERS) {
                dao.insertReason(conn, starter.code(), starter.group(), starter.nameEn(), starter.nameHu(),
                        starter.attributable(), null, now);
            }
            LOG.info("Seeded {} downtime reasons", STARTERS.size());
        });
    }

    private static boolean hasAny(java.sql.Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1 FROM downtime_reasons LIMIT 1")) {
            return rs.next();
        }
    }
}
