package hu.due.rewear.config;

import hu.due.rewear.dao.DaoDateUtil;
import hu.due.rewear.dao.DaoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which machine this is.
 *
 * <p>Nothing needed to ask while one application opened one file. A change that may have been made in two
 * places has to say which one it was made in, or the two cannot be told apart afterwards — so this
 * installation registers itself once and keeps the id beside the data.
 *
 * <p>The name is generated rather than taken from the computer: a machine name is the owner's, it ends up
 * in a backup bundle that may be sent somewhere, and nothing here needs it. An administrator can see the
 * name and tell one device from another, which is all it is for.
 */
public final class DeviceRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceRegistry.class);

    /** Where this installation's own device id is kept, so a trigger can read it without being told. */
    public static final String DEVICE_ID_SETTING = "device.id";

    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Kind { DESKTOP, HANDHELD }

    /** One device that may change this data. */
    public record Device(long id, String name, Kind kind, LocalDateTime registeredAt,
                         LocalDateTime lastSeenAt) { }

    private DeviceRegistry() {
    }

    /**
     * Registers this installation if it has not registered before, and notes that it is running.
     *
     * @return the id of this device
     */
    public static long registerThisInstallation(Connection conn) throws SQLException {
        Optional<Long> known = currentDeviceId(conn);
        if (known.isPresent() && exists(conn, known.get())) {
            touch(conn, known.get());
            return known.get();
        }
        String name = "DESKTOP-" + hex();
        long id;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO devices(name, kind, registered_at, last_seen_at) VALUES (?,?,?,?) RETURNING id")) {
            ps.setString(1, name);
            ps.setString(2, Kind.DESKTOP.name());
            ps.setString(3, DaoDateUtil.now());
            ps.setString(4, DaoDateUtil.now());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO app_settings(setting_key, setting_value) VALUES (?,?)"
                        + " ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value")) {
            ps.setString(1, DEVICE_ID_SETTING);
            ps.setString(2, Long.toString(id));
            ps.executeUpdate();
        }
        LOG.info("Registered this installation as device {} ({})", id, name);
        return id;
    }

    /** The id this installation registered itself under, if it has. */
    public static Optional<Long> currentDeviceId(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT setting_value FROM app_settings WHERE setting_key = ?")) {
            ps.setString(1, DEVICE_ID_SETTING);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(Long.parseLong(rs.getString(1).trim()));
            }
        } catch (SQLException | NumberFormatException ex) {
            throw new DaoException("Could not read this installation's device id", ex);
        }
    }

    public static Optional<Device> find(Connection conn, long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, kind, registered_at, last_seen_at FROM devices WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not read device " + id, ex);
        }
    }

    public static List<Device> all(Connection conn) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, kind, registered_at, last_seen_at FROM devices ORDER BY id")) {
            List<Device> devices = new ArrayList<>();
            while (rs.next()) {
                devices.add(map(rs));
            }
            return List.copyOf(devices);
        } catch (SQLException ex) {
            throw new DaoException("Could not read the devices", ex);
        }
    }

    private static Device map(ResultSet rs) throws SQLException {
        return new Device(rs.getLong("id"), rs.getString("name"), Kind.valueOf(rs.getString("kind")),
                DaoDateUtil.parse(rs.getString("registered_at")),
                DaoDateUtil.parse(rs.getString("last_seen_at")));
    }

    private static boolean exists(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM devices WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void touch(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE devices SET last_seen_at = ? WHERE id = ?")) {
            ps.setString(1, DaoDateUtil.now());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static String hex() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) {
            text.append(String.format("%02X", b));
        }
        return text.toString();
    }
}
