package io.opendrs.engine.schema.dialect;

import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.engine.schema.meta.ObjectType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PostgreSQL source-side reads for export.
 *
 * <p>View DDL comes from {@code pg_get_viewdef} — exact. Table DDL is reconstructed from the
 * system catalogs ({@code information_schema.columns}, {@code pg_get_constraintdef},
 * {@code pg_indexes}); there is no {@code SHOW CREATE TABLE} equivalent. The reconstruction
 * covers the common cases — column types (incl. arrays and user-defined types such as enums and
 * domains), defaults, identity/serial, NOT NULL, primary/foreign/unique/check constraints, and
 * non-constraint indexes — but does not reproduce everything {@code pg_dump} would: table and
 * column comments, storage parameters, tablespace, partitioning setup, inheritance, foreign
 * tables, triggers and row-level security are omitted.
 *
 * <p>Table dependencies come from {@code information_schema.table_constraints} (foreign keys).
 * View dependencies (regular and materialized) come from {@code pg_depend} over the view's
 * {@code pg_rewrite} rule — the catalog the server itself maintains, world-readable like all
 * {@code pg_catalog} tables, so no owner filtering like the {@code information_schema} usage
 * views have.
 */
public class PostgreSqlSchemaDialect implements SchemaDialect {

    @Override
    public List<ObjectId> getObjects(Connection connection, ObjectType type, String schema)
            throws SQLException {
        String sql = type == ObjectType.VIEW
                ? "SELECT viewname FROM pg_catalog.pg_views WHERE schemaname = ?"
                        + " UNION SELECT matviewname FROM pg_catalog.pg_matviews WHERE schemaname = ?"
                        + " ORDER BY 1"
                : "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = ? ORDER BY tablename";
        List<ObjectId> objects = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            if (type == ObjectType.VIEW) {
                ps.setString(2, schema);
            }
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
        return id.type() == ObjectType.VIEW ? viewDdl(connection, id) : tableDdl(connection, id);
    }

    private List<ObjectId> foreignKeyReferences(Connection connection, ObjectId id)
            throws SQLException {
        Set<ObjectId> references = new LinkedHashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT ccu.table_schema, ccu.table_name"
                        + " FROM information_schema.table_constraints tc"
                        + " JOIN information_schema.constraint_column_usage ccu"
                        + " ON ccu.constraint_schema = tc.constraint_schema"
                        + " AND ccu.constraint_name = tc.constraint_name"
                        + " WHERE tc.constraint_type = 'FOREIGN KEY'"
                        + " AND tc.table_schema = ? AND tc.table_name = ?")) {
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
                "SELECT DISTINCT nref.nspname, ref.relname, ref.relkind"
                        + " FROM pg_catalog.pg_depend d"
                        + " JOIN pg_catalog.pg_rewrite r ON r.oid = d.objid"
                        + " JOIN pg_catalog.pg_class v ON v.oid = r.ev_class"
                        + " JOIN pg_catalog.pg_namespace nv ON nv.oid = v.relnamespace"
                        + " JOIN pg_catalog.pg_class ref ON ref.oid = d.refobjid"
                        + " JOIN pg_catalog.pg_namespace nref ON nref.oid = ref.relnamespace"
                        + " WHERE nv.nspname = ? AND v.relname = ? AND v.relkind IN ('v', 'm')"
                        + " AND d.classid = 'pg_rewrite'::regclass"
                        + " AND d.refclassid = 'pg_class'::regclass"
                        + " AND d.deptype = 'n'"
                        + " AND ref.relkind IN ('r', 'p', 'v', 'm')")) {
            ps.setString(1, id.tableId().getSchema());
            ps.setString(2, id.tableId().getTable());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectType type =
                            switch (rs.getString(3)) {
                                case "v", "m" -> ObjectType.VIEW;
                                default -> ObjectType.TABLE;
                            };
                    references.add(new ObjectId(TableId.of(rs.getString(1), rs.getString(2)), type));
                }
            }
        }
        references.remove(id);
        return List.copyOf(references);
    }

    private String viewDdl(Connection connection, ObjectId id) throws SQLException {
        String keyword = null;
        String definition = null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT rel.relkind, pg_catalog.pg_get_viewdef(rel.oid, true)"
                        + " FROM pg_catalog.pg_class rel"
                        + " JOIN pg_catalog.pg_namespace ns ON rel.relnamespace = ns.oid"
                        + " WHERE ns.nspname = ? AND rel.relname = ? AND rel.relkind IN ('v', 'm')")) {
            ps.setString(1, id.tableId().getSchema());
            ps.setString(2, id.tableId().getTable());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    keyword = "m".equals(rs.getString(1)) ? "MATERIALIZED VIEW" : "VIEW";
                    definition = rs.getString(2);
                }
            }
        }
        if (definition == null) {
            return null;
        }
        return "CREATE " + keyword + " " + quote(id) + " AS\n" + definition;
    }

    private String tableDdl(Connection connection, ObjectId id) throws SQLException {
        List<String> elements = new ArrayList<>();
        columns(connection, id, elements);
        constraints(connection, id, elements);
        StringBuilder ddl = new StringBuilder("CREATE TABLE ")
                .append(quote(id))
                .append(" (\n  ")
                .append(String.join(",\n  ", elements))
                .append("\n);");
        indexes(connection, id, ddl);
        return ddl.toString();
    }

    private void columns(Connection connection, ObjectId id, List<String> elements)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT column_name, data_type, udt_schema, udt_name, is_nullable, column_default,"
                        + " is_identity, identity_generation, character_maximum_length,"
                        + " numeric_precision, numeric_scale, datetime_precision"
                        + " FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = ?"
                        + " ORDER BY ordinal_position")) {
            ps.setString(1, id.tableId().getSchema());
            ps.setString(2, id.tableId().getTable());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    elements.add(columnDdl(rs));
                }
            }
        }
    }

    private static String columnDdl(ResultSet rs) throws SQLException {
        StringBuilder column = new StringBuilder(quoteIdent(rs.getString("column_name")))
                .append(' ')
                .append(typeText(rs));
        if ("YES".equals(rs.getString("is_identity"))) {
            column.append(" GENERATED ")
                    .append(rs.getString("identity_generation"))
                    .append(" AS IDENTITY");
        } else {
            String defaultValue = rs.getString("column_default");
            if (defaultValue != null) {
                column.append(" DEFAULT ").append(defaultValue);
            }
        }
        if ("NO".equals(rs.getString("is_nullable"))) {
            column.append(" NOT NULL");
        }
        return column.toString();
    }

    private static String typeText(ResultSet rs) throws SQLException {
        String dataType = rs.getString("data_type");
        if ("ARRAY".equals(dataType)) {
            String udt = rs.getString("udt_name");
            return (udt.startsWith("_") ? udt.substring(1) : udt) + "[]";
        }
        if ("USER-DEFINED".equals(dataType)) {
            String udt = quoteIdent(rs.getString("udt_name"));
            String udtSchema = rs.getString("udt_schema");
            return "pg_catalog".equals(udtSchema) ? udt : quoteIdent(udtSchema) + "." + udt;
        }
        long charLength = rs.getLong("character_maximum_length");
        if (!rs.wasNull()) {
            return dataType + "(" + charLength + ")";
        }
        if (dataType.equals("numeric") || dataType.equals("decimal")) {
            long precision = rs.getLong("numeric_precision");
            if (rs.wasNull()) {
                return dataType;
            }
            StringBuilder type = new StringBuilder(dataType).append('(').append(precision);
            long scale = rs.getLong("numeric_scale");
            if (!rs.wasNull()) {
                type.append(", ").append(scale);
            }
            return type.append(')').toString();
        }
        if (dataType.startsWith("timestamp") || dataType.startsWith("time")) {
            long precision = rs.getLong("datetime_precision");
            if (rs.wasNull()) {
                return dataType;
            }
            int split = dataType.indexOf(' ');
            String base = split < 0 ? dataType : dataType.substring(0, split);
            String rest = split < 0 ? "" : dataType.substring(split);
            return base + "(" + precision + ")" + rest;
        }
        return dataType;
    }

    private void constraints(Connection connection, ObjectId id, List<String> elements)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT con.conname, pg_catalog.pg_get_constraintdef(con.oid)"
                        + " FROM pg_catalog.pg_constraint con"
                        + " JOIN pg_catalog.pg_class rel ON con.conrelid = rel.oid"
                        + " JOIN pg_catalog.pg_namespace ns ON rel.relnamespace = ns.oid"
                        + " WHERE ns.nspname = ? AND rel.relname = ?"
                        + " AND con.contype IN ('p', 'f', 'u', 'c')"
                        + " ORDER BY con.conname")) {
            ps.setString(1, id.tableId().getSchema());
            ps.setString(2, id.tableId().getTable());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    elements.add("CONSTRAINT "
                            + quoteIdent(rs.getString(1))
                            + " "
                            + rs.getString(2));
                }
            }
        }
    }

    private void indexes(Connection connection, ObjectId id, StringBuilder ddl)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT indexdef FROM pg_catalog.pg_indexes"
                        + " WHERE schemaname = ? AND tablename = ?"
                        + " AND NOT EXISTS ("
                        + " SELECT 1 FROM pg_catalog.pg_class rel"
                        + " JOIN pg_catalog.pg_namespace ns ON rel.relnamespace = ns.oid"
                        + " JOIN pg_catalog.pg_constraint con ON con.conrelid = rel.oid"
                        + " WHERE ns.nspname = pg_indexes.schemaname"
                        + " AND rel.relname = pg_indexes.tablename"
                        + " AND con.conname = pg_indexes.indexname)"
                        + " ORDER BY indexname")) {
            ps.setString(1, id.tableId().getSchema());
            ps.setString(2, id.tableId().getTable());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append('\n').append(rs.getString(1)).append(';');
                }
            }
        }
    }

    private static String quote(ObjectId id) {
        return quoteIdent(id.tableId().getSchema()) + "." + quoteIdent(id.tableId().getTable());
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
