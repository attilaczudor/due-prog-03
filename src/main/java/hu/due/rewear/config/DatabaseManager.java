package hu.due.rewear.config;

import hu.due.rewear.dao.DaoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Manages embedded SQLite JDBC connections with foreign key enforcement and transaction helpers.
 */
public final class DatabaseManager {

    public static final Path DEFAULT_DB_PATH = Path.of("data", "rewear-wms.db");

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection conn) throws SQLException;
    }

    private final Path databasePath;
    private final String jdbcUrl;

    public DatabaseManager() {
        this(DEFAULT_DB_PATH);
    }

    public DatabaseManager(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath();
        this.jdbcUrl = "jdbc:sqlite:" + this.databasePath;
        ensureParentDirectory();
    }

    private void ensureParentDirectory() {
        Path parent = databasePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new DaoException("Could not create database directory: " + parent, e);
            }
        }
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA busy_timeout = 5000;");
        }
        return conn;
    }

    public <T> T executeInTransaction(SqlFunction<T> action) {
        try (Connection conn = getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = action.apply(conn);
                conn.commit();
                return result;
            } catch (Exception ex) {
                try {
                    conn.rollback();
                } catch (SQLException suppressed) {
                    ex.addSuppressed(suppressed);
                }
                if (ex instanceof DaoException daoEx) {
                    throw daoEx;
                }
                throw new DaoException("Transaction failed: " + ex.getMessage(), ex);
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // Connection is closing
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Database connection error: " + e.getMessage(), e);
        }
    }

    public void runInTransaction(SqlConsumer action) {
        executeInTransaction(conn -> {
            action.accept(conn);
            return null;
        });
    }
}
