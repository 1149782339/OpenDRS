package io.opendrs.engine.schema.dialect;

import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.engine.schema.meta.ObjectType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MySQL source-side reads for export (MySQL 8.0+).
 *
 * <p>Table dependencies come from {@code information_schema.key_column_usage} (foreign keys); view
 * dependencies come from {@code information_schema.view_table_usage}, which the server maintains
 * against the data dictionary — no name-matching heuristics.
 *
 * <p>MySQL has no standalone sequences: {@link #getObjects} only enumerates base tables and views.
 */
public class MySqlSchemaDialect implements SchemaDialect {

    @Override
    public List<ObjectId> getObjects(Connection connection, ObjectType type, String schema)
            throws SQLException {
        String tableType = type == ObjectType.VIEW ? "VIEW" : "BASE TABLE";
        List<ObjectId> objects = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = ? AND table_type = ? ORDER BY table_name")) {
            ps.setString(1, schema);
            ps.setString(2, tableType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    objects.add(new ObjectId(TableId.of(schema, rs.getString(1)), type));
                }
            }
        }
        return objects;
    }

    @Override
    public List<ObjectId> getObjectDependencies(
            Connection connection, ObjectId id, boolean ignoreForeignKey) throws SQLException {
        if (id.type() == ObjectType.VIEW) {
            return viewReferences(connection, id);
        }
        if (ignoreForeignKey) {
            return List.of();
        }
        return foreignKeyReferences(connection, id);
    }

    @Override
    public String getObjectDdl(Connection connection, ObjectId id) throws SQLException {
        String command = id.type() == ObjectType.VIEW ? "SHOW CREATE VIEW " : "SHOW CREATE TABLE ";
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(command + quote(id))) {
            return rs.next() ? rs.getString(2) : null;
        }
    }

    private List<ObjectId> foreignKeyReferences(Connection connection, ObjectId id)
            throws SQLException {
        Set<ObjectId> references = new LinkedHashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT referenced_table_schema, referenced_table_name"
                        + " FROM information_schema.key_column_usage"
                        + " WHERE constraint_schema = ? AND table_name = ?"
                        + " AND referenced_table_name IS NOT NULL")) {
            ps.setString(1, id.tableId().getSchema());
            ps.setString(2, id.tableId().getTable());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    references.add(new ObjectId(TableId.of(rs.getString(1), rs.getString(2)), ObjectType.TABLE));
                }
            }
        }
        references.remove(id);
        return List.copyOf(references);
    }

    private List<ObjectId> viewReferences(Connection connection, ObjectId id) throws SQLException {
        Set<ObjectId> references = new LinkedHashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT vtu.table_schema, vtu.table_name, tab.table_type"
                        + " FROM information_schema.view_table_usage vtu"
                        + " LEFT JOIN information_schema.tables tab"
                        + " ON tab.table_schema = vtu.table_schema AND tab.table_name = vtu.table_name"
                        + " WHERE vtu.view_schema = ? AND vtu.view_name = ?")) {
            ps.setString(1, id.tableId().getSchema());
            ps.setString(2, id.tableId().getTable());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectType type =
                            "VIEW".equals(rs.getString(3)) ? ObjectType.VIEW : ObjectType.TABLE;
                    references.add(new ObjectId(TableId.of(rs.getString(1), rs.getString(2)), type));
                }
            }
        }
        references.remove(id);
        return List.copyOf(references);
    }

    private static String quote(ObjectId id) {
        return quoteIdent(id.tableId().getSchema()) + "." + quoteIdent(id.tableId().getTable());
    }

    private static String quoteIdent(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }
}
