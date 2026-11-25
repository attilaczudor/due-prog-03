package hu.due.rewear.dao;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads and writes a quantity that must be exact — money, or a mass in kilograms — as a decimal string.
 * <p>
 * SQLite's {@code REAL} is binary floating point, so a value stored that way is not always the value that
 * comes back, and summing many of them drifts. These columns therefore hold the decimal text of the value,
 * which {@link BigDecimal} round-trips exactly. The alternative, integer minor units, would need the right
 * scale at every read and write — and a currency has its own number of decimals — where one wrong scale
 * silently changes a figure a hundredfold.
 * <p>
 * Mass is here for the same reason money is: the Hungarian extended-producer-responsibility fee is charged on
 * kilograms placed on the market, and the penalty for under-reporting is half the shortfall on top of the fee
 * itself, so a recorded mass is a financial control and not merely a measurement.
 */
public final class ExactDecimal {

    private ExactDecimal() {
    }

    /** Writes a value as its plain decimal text; null is written as zero. */
    public static void set(PreparedStatement ps, int index, BigDecimal amount) throws SQLException {
        ps.setString(index, (amount == null ? BigDecimal.ZERO : amount).toPlainString());
    }

    public static BigDecimal get(ResultSet rs, String column) throws SQLException {
        return parse(rs.getString(column));
    }

    public static BigDecimal get(ResultSet rs, int index) throws SQLException {
        return parse(rs.getString(index));
    }

    /**
     * Turns stored text into a value. Rows written before a column became text hold a floating point number,
     * which SQLite hands back as something like {@code 1800.0}; that still parses exactly.
     */
    public static BigDecimal parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(stored.trim());
        } catch (NumberFormatException ex) {
            throw new DaoException("Stored value is not a number: " + stored, ex);
        }
    }
}
