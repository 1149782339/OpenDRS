package io.opendrs.migration.service;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.dialect.DbDialects;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.api.request.SchemaObject;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.domain.ConnectionInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Expands {@code tables.objects} (allTables / tables / excludeTables, including globs like
 * {@code TMP_*}) into concrete {@link Table} rows <em>before</em> {@code DbPreCheck}.
 * {@link DbDialect#listTables} only applies exact {@link TableRef} excludes.
 */
@Component
public class TableSelectionExpander {

    private final JdbcConnectionFactory factory;

    TableSelectionExpander(JdbcConnectionFactory factory) {
        this.factory = factory;
    }

    public List<Table> expand(ConnectionInfo source, TableSelection selection) {
        return expand(source, selection, DbDialects.of(source.getType()));
    }

    List<Table> expand(ConnectionInfo source, TableSelection selection, DbDialect dialect) {
        if (selection == null || selection.objects() == null || selection.objects().isEmpty()) {
            return List.of();
        }
        if (!needsListing(selection)) {
            return expandExplicit(selection);
        }
        try (JdbcConnection conn = factory.open(source)) {
            return expandWithConnection(conn, dialect, selection);
        }
    }

    /**
     * Exact table names only (skips {@code allTables} and glob patterns). Used when listing
     * cannot run because the source connection failed.
     */
    public List<Table> expandExplicit(TableSelection selection) {
        if (selection == null || selection.objects() == null) {
            return List.of();
        }
        Map<TableRef, Table> out = new LinkedHashMap<>();
        for (SchemaObject object : selection.objects()) {
            if (Boolean.TRUE.equals(object.allTables())) {
                continue;
            }
            addExplicitTables(out, object);
        }
        return List.copyOf(out.values());
    }

    private List<Table> expandWithConnection(JdbcConnection conn, DbDialect dialect, TableSelection selection) {
        Map<TableRef, Table> out = new LinkedHashMap<>();
        for (SchemaObject object : selection.objects()) {
            for (Table table : expandObject(conn, dialect, object)) {
                out.putIfAbsent(table.ref(), table);
            }
        }
        return List.copyOf(out.values());
    }

    private List<Table> expandObject(JdbcConnection conn, DbDialect dialect, SchemaObject object) {
        String schema = object.schema();
        List<String> excludes = object.excludeTables() == null ? List.of() : object.excludeTables();
        List<TableRef> exactExcludes = exactExcludeRefs(schema, excludes);
        List<String> globExcludes = globPatterns(excludes);

        if (Boolean.TRUE.equals(object.allTables())) {
            List<Table> listed = dialect.listTables(conn, schema, exactExcludes);
            return listed.stream()
                    .map(table -> withSchema(schema, table))
                    .filter(table -> !excludedByGlob(table.ref().table(), globExcludes))
                    .toList();
        }

        List<String> names = object.tables() == null ? List.of() : object.tables();
        boolean anyGlob = names.stream().anyMatch(NameGlobs::isPattern);
        List<Table> listed = anyGlob
                ? dialect.listTables(conn, schema, exactExcludes).stream()
                        .map(table -> withSchema(schema, table))
                        .toList()
                : List.of();

        Map<TableRef, Table> out = new LinkedHashMap<>();
        for (String name : names) {
            if (NameGlobs.isPattern(name)) {
                for (Table table : listed) {
                    String tableName = table.ref().table();
                    if (NameGlobs.matches(tableName, name) && !excludedByGlob(tableName, globExcludes)) {
                        out.putIfAbsent(table.ref(), table);
                    }
                }
            } else if (!isExcluded(name, excludes)) {
                Table table = new Table(new TableRef(schema, name));
                out.putIfAbsent(table.ref(), table);
            }
        }
        return List.copyOf(out.values());
    }

    private static void addExplicitTables(Map<TableRef, Table> out, SchemaObject object) {
        List<String> names = object.tables() == null ? List.of() : object.tables();
        List<String> excludes = object.excludeTables() == null ? List.of() : object.excludeTables();
        String schema = object.schema();
        for (String name : names) {
            if (NameGlobs.isPattern(name) || isExcluded(name, excludes)) {
                continue;
            }
            Table table = new Table(new TableRef(schema, name));
            out.putIfAbsent(table.ref(), table);
        }
    }

    private static boolean needsListing(TableSelection selection) {
        for (SchemaObject object : selection.objects()) {
            if (Boolean.TRUE.equals(object.allTables())) {
                return true;
            }
            if (object.tables() != null && object.tables().stream().anyMatch(NameGlobs::isPattern)) {
                return true;
            }
        }
        return false;
    }

    private static List<TableRef> exactExcludeRefs(String schema, List<String> excludes) {
        List<TableRef> refs = new ArrayList<>();
        for (String exclude : excludes) {
            if (!NameGlobs.isPattern(exclude)) {
                refs.add(new TableRef(schema, exclude));
            }
        }
        return refs;
    }

    private static List<String> globPatterns(List<String> values) {
        return values.stream().filter(NameGlobs::isPattern).toList();
    }

    private static boolean isExcluded(String name, List<String> excludes) {
        for (String exclude : excludes) {
            if (NameGlobs.isPattern(exclude)) {
                if (NameGlobs.matches(name, exclude)) {
                    return true;
                }
            } else if (exclude.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean excludedByGlob(String name, List<String> globExcludes) {
        for (String glob : globExcludes) {
            if (NameGlobs.matches(name, glob)) {
                return true;
            }
        }
        return false;
    }

    /** Keep the objects.schema spelling so mappings resolve against the selection, not JDBC metadata. */
    private static Table withSchema(String schema, Table table) {
        return new Table(new TableRef(schema, table.ref().table()));
    }
}
