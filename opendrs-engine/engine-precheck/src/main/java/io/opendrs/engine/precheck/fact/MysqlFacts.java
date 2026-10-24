package io.opendrs.engine.precheck.fact;

import io.opendrs.engine.precheck.Side;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MySQL facts. Parameters come from one {@code SHOW VARIABLES}, privileges from one
 * {@code SHOW GRANTS}, table metadata from two {@code information_schema} queries - never one query
 * per parameter or per table.
 */
public final class MysqlFacts extends EndpointFacts {

    public MysqlFacts(Side side, JdbcConnectionConfig config, JdbcConnectionFactory factory) {
        super(side, config, factory);
    }

    @Override
    protected Map<String, String> loadParameters(JdbcConnection conn) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] row : conn.queryList(
                "SHOW VARIABLES", rs -> new String[] {rs.getString(1), rs.getString(2)})) {
            if (row[0] != null) {
                out.put(row[0].toLowerCase(Locale.ROOT), row[1]);
            }
        }
        return out;
    }

    @Override
    protected CharsetInfo loadCharset(JdbcConnection conn) {
        return new CharsetInfo(parameters().get("character_set_server"), parameters().get("collation_server"));
    }

    @Override
    protected TimezoneInfo loadTimezone(JdbcConnection conn) {
        String zone = parameters().get("time_zone");
        if (zone == null || "system".equalsIgnoreCase(zone)) {
            String system = parameters().get("system_time_zone");
            zone = system == null ? zone : system + " (SYSTEM)";
        }
        Integer offset = conn.queryOne(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())", rs -> rs.getInt(1));
        return new TimezoneInfo(zone, offset == null ? 0 : offset);
    }

    @Override
    protected InstanceState loadInstanceState(JdbcConnection conn) {
        Integer readOnly = conn.queryOne("SELECT @@GLOBAL.read_only", rs -> rs.getInt(1));
        Integer superReadOnly = conn.queryOne("SELECT @@GLOBAL.super_read_only", rs -> rs.getInt(1));
        boolean writable = (readOnly == null || readOnly == 0)
                && (superReadOnly == null || superReadOnly == 0);
        return new InstanceState(
                writable, "read_only=" + readOnly + ", super_read_only=" + superReadOnly);
    }

    @Override
    protected List<String> loadMissingPermissions(
            JdbcConnection conn, Collection<String> schemas, List<String> required) {
        MysqlGrants grants = MysqlGrants.parse(conn.queryList("SHOW GRANTS", rs -> rs.getString(1)));
        List<String> missing = new ArrayList<>();
        for (String privilege : required) {
            boolean granted = schemas.isEmpty()
                    ? grants.hasGlobally(privilege)
                    : schemas.stream().allMatch(schema -> grants.has(privilege, schema));
            if (!granted) {
                missing.add(privilege);
            }
        }
        return List.copyOf(missing);
    }

    @Override
    protected void loadTableFacts(
            JdbcConnection conn, Collection<Table> tables, Map<TableRef, TableFacts> out) {
        if (tables.isEmpty()) {
            return;
        }
        List<String> schemas = distinctSchemas(tables);
        String placeholders = String.join(",", java.util.Collections.nCopies(schemas.size(), "?"));
        Object[] params = schemas.toArray();

        Map<String, String> engines = new HashMap<>();
        conn.queryList(
                        "SELECT TABLE_SCHEMA, TABLE_NAME, ENGINE FROM information_schema.TABLES"
                                + " WHERE TABLE_SCHEMA IN (" + placeholders + ")",
                        rs -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
                        params)
                .forEach(row -> engines.put(key(row[0], row[1]), row[2]));

        Set<String> withPrimaryKey = new HashSet<>();
        conn.queryList(
                        "SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLE_CONSTRAINTS"
                                + " WHERE CONSTRAINT_TYPE = 'PRIMARY KEY' AND TABLE_SCHEMA IN ("
                                + placeholders + ")",
                        rs -> key(rs.getString(1), rs.getString(2)),
                        params)
                .forEach(withPrimaryKey::add);

        for (Table table : tables) {
            TableRef ref = table.ref();
            String tableKey = key(ref.schema(), ref.table());
            if (!engines.containsKey(tableKey)) {
                continue;
            }
            out.put(ref, new TableFacts(ref, true, engines.get(tableKey), withPrimaryKey.contains(tableKey)));
        }
    }

    private static List<String> distinctSchemas(Collection<Table> tables) {
        Set<String> schemas = new LinkedHashSet<>();
        for (Table table : tables) {
            if (table.ref().schema() != null && !table.ref().schema().isBlank()) {
                schemas.add(table.ref().schema());
            }
        }
        return List.copyOf(schemas);
    }
}
