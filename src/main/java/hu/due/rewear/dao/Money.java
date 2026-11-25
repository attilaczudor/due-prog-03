package hu.due.rewear.dao;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads and writes money as an exact decimal string.
 * <p>
 * SQLite's {@code REAL} is binary floating point, so an amount stored that way is not always the amount that
 * comes back, and summing many of them drifts. Every money column therefore holds the decimal text of the
 * amount, which {@link BigDecimal} round-trips exactly. The alternative, integer minor units, would need the
 * right scale at every read and write — and a currency has its own number of decimals — where one wrong scale
 * silently changes an amount a hundredfold.
 */
public final class Money {

    private Money() {
    }

    /** Writes an amount as its plain decimal text; a null amount is written as zero. */
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
     * Turns stored text into an amount. Older rows written before money became text hold a floating point
     * number, which SQLite hands back as something like {@code 1800.0}; that still parses exactly.
     */
    public static BigDecimal parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(stored.trim());
        } catch (NumberFormatException ex) {
            throw new DaoException("Stored amount is not a number: " + stored, ex);
        }
    }
}
