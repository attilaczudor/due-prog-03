package hu.due.rewear.dao;

import hu.due.rewear.config.DatabaseManager;
import hu.due.rewear.model.SmartFolder;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public final class SmartFolderDao {
    private final DatabaseManager db;

    public SmartFolderDao(DatabaseManager db) {
        this.db = db;
    }

    public List<SmartFolder> find(long owner, SmartFolder.Scope scope) {
        String sql = """
                SELECT f.id, f.name, f.icon, r.field, r.value
                FROM smart_folders f LEFT JOIN smart_folder_rules r ON r.folder_id = f.id
                WHERE f.owner_user_id = ? AND f.scope = ?
                ORDER BY f.id, r.field, r.value
                """;
        try (var conn = db.getConnection(); var ps = conn.prepareStatement(sql)) {
            bindOwner(ps, owner, scope);
            try (var rows = ps.executeQuery()) {
                List<SmartFolder> folders = new ArrayList<>();
                long id = 0;
                String name = null;
                String icon = null;
                var rules = new EnumMap<SmartFolder.Field, List<String>>(SmartFolder.Field.class);
                while (rows.next()) {
                    long nextId = rows.getLong("id");
                    if (id != nextId) {
                        if (id != 0) folders.add(new SmartFolder(id, name, icon, rules));
                        id = nextId;
                        name = rows.getString("name");
                        icon = rows.getString("icon");
                        rules.clear();
                    }
                    String field = rows.getString("field");
                    if (field != null) {
                        rules.computeIfAbsent(SmartFolder.Field.valueOf(field), key -> new ArrayList<>())
                                .add(rows.getString("value"));
                    }
                }
                if (id != 0) folders.add(new SmartFolder(id, name, icon, rules));
                return folders;
            }
        } catch (SQLException ex) {
            throw new DaoException("Could not load smart folders", ex);
        }
    }

    public long save(long owner, SmartFolder.Scope scope, SmartFolder folder) {
        return db.executeInTransaction(conn -> {
            long id = folder.id();
            if (id == 0) {
                try (var ps = conn.prepareStatement(
                        "INSERT INTO smart_folders (owner_user_id, scope, name, icon) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    bindOwner(ps, owner, scope);
                    ps.setString(3, folder.name());
                    ps.setString(4, folder.icon());
                    ps.executeUpdate();
                    try (var keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Missing smart folder ID");
                        id = keys.getLong(1);
                    }
                }
            } else {
                try (var ps = conn.prepareStatement("""
                        UPDATE smart_folders SET name = ?, icon = ?
                        WHERE owner_user_id = ? AND scope = ? AND id = ?
                        """)) {
                    ps.setString(1, folder.name());
                    ps.setString(2, folder.icon());
                    ps.setLong(3, owner);
                    ps.setString(4, scope.name());
                    ps.setLong(5, id);
                    if (ps.executeUpdate() != 1) throw new SQLException("Smart folder unavailable");
                }
                try (var ps = conn.prepareStatement("DELETE FROM smart_folder_rules WHERE folder_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO smart_folder_rules (folder_id, field, value) VALUES (?, ?, ?)")) {
                for (var rule : folder.rules().entrySet()) {
                    for (String value : rule.getValue()) {
                        ps.setLong(1, id);
                        ps.setString(2, rule.getKey().name());
                        ps.setString(3, value);
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
            return id;
        });
    }

    public void delete(long owner, SmartFolder.Scope scope, long id) {
        db.runInTransaction(conn -> {
            try (var ps = conn.prepareStatement(
                    "DELETE FROM smart_folders WHERE owner_user_id = ? AND scope = ? AND id = ?")) {
                bindOwner(ps, owner, scope);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
        });
    }

    private static void bindOwner(PreparedStatement ps, long owner, SmartFolder.Scope scope) throws SQLException {
        ps.setLong(1, owner);
        ps.setString(2, scope.name());
    }
}
