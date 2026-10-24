package io.opendrs.engine.core;

import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.dialect.DbDialects;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link TableSelection} into concrete {@link Table} rows against live source metadata, so
 * that everything downstream — precheck rules, DDL conversion, the applier — works on real tables
 * and never on selection syntax.
 *
 * <p>Three rules, in this order:
 * <ol>
 *   <li>No include lists ⇒ every schema the connection can read ({@link DbDialect#listSchemas}),
 *       which is what "migrate the whole instance" means and what keeps newly created schemas in
 *       scope;</li>
 *   <li>whole schemas ⇒ every table of each of them, likewise listed live;</li>
 *   <li>{@code excludeSchemas} then {@code excludeTables} are subtracted last, so an exclusion
 *       always wins over an inclusion.</li>
 * </ol>
 *
 * <p>Only the first two need a connection. A selection that already names its tables is used
 * verbatim, which is why precheck can still inspect an explicit selection after the source
 * connection failed.
 */
public class TableSelectionExpander {

    private final JdbcConnectionFactory factory;

    public TableSelectionExpander(JdbcConnectionFactory factory) {
        this.factory = factory;
    }

    /** {@code null} selection means "no filter": the whole source. */
    public List<Table> expand(JdbcConnectionConfig source, TableSelection selection) {
        return expand(source, selection, DbDialects.of(source.getType()));
    }

    List<Table> expand(JdbcConnectionConfig source, TableSelection selection, DbDialect dialect) {
        TableSelection scope = selection == null ? TableSelection.all() : selection;
        if (!needsListing(scope)) {
            return expandExplicit(scope);
        }
        try (JdbcConnection conn = factory.open(source)) {
            return expandWithConnection(conn, dialect, scope);
        }
    }

    /**
     * The selected tables as written, no metadata round trip. Used when listing cannot run because
     * the source connection failed. An "everything" selection expands to nothing here: without a
     * connection there is nothing to enumerate, and the caller marks the table list as unknown
     * instead of pretending the source is empty.
     */
    public List<Table> expandExplicit(TableSelection selection) {
        TableSelection scope = selection == null ? TableSelection.all() : selection;
        Map<TableRef, Table> out = new LinkedHashMap<>();
        for (TableId id : scope.tables()) {
            add(out, scope, id.getSchema(), id.getTable());
        }
        return List.copyOf(out.values());
    }

    private List<Table> expandWithConnection(JdbcConnection conn, DbDialect dialect, TableSelection scope) {
        Map<TableRef, Table> out = new LinkedHashMap<>();
        for (String schema : schemasToRead(conn, dialect, scope)) {
            for (Table table : expandSchema(conn, dialect, scope, schema)) {
                out.putIfAbsent(table.ref(), table);
            }
        }
        return List.copyOf(out.values());
    }

    /**
     * Which schemas need a table listing. "Everything" is resolved through the dialect rather than
     * remembered, so a database created after the task started still lands in scope. Only the two
     * listing shapes get here; a table-level selection is already explicit.
     */
    private List<String> schemasToRead(JdbcConnection conn, DbDialect dialect, TableSelection scope) {
        Set<String> schemas = new LinkedHashSet<>();
        if (scope.isAll()) {
            schemas.addAll(dialect.listSchemas(conn));
        } else {
            schemas.addAll(scope.schemas());
        }
        schemas.removeIf(scope::isSchemaExcluded);
        return List.copyOf(schemas);
    }

    private List<Table> expandSchema(
            JdbcConnection conn, DbDialect dialect, TableSelection scope, String schema) {
        Map<TableRef, Table> out = new LinkedHashMap<>();
        for (Table table : dialect.listTables(conn, schema, List.of())) {
            add(out, scope, schema, table.ref().table());
        }
        return List.copyOf(out.values());
    }

    /**
     * Keeps the selection's spelling of the schema (JDBC metadata reports a MySQL database as the
     * catalog) and drops excluded tables, so mappings and excludes resolve against one spelling.
     */
    private static void add(Map<TableRef, Table> out, TableSelection scope, String schema, String table) {
        if (scope.isSchemaExcluded(schema) || scope.isTableExcluded(schema, table)) {
            return;
        }
        TableRef ref = new TableRef(schema, table);
        out.putIfAbsent(ref, new Table(ref));
    }

    private static boolean needsListing(TableSelection scope) {
        return scope.isAll() || !scope.schemas().isEmpty();
    }
}
