package io.opendrs.engine.core;

import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.common.selection.TableSelection;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a {@link TableSelection} into concrete {@link TableId} rows against live source metadata, so
 * that everything downstream — precheck rules, DDL conversion, the applier — works on real tables
 * and never on selection syntax.
 *
 * <p>Three rules, in this order:
 * <ol>
 *   <li>No include lists ⇒ every schema the connection can read ({@link JdbcConnection#listSchemas}),
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
    public List<TableId> expand(JdbcConnectionConfig source, TableSelection selection)
            throws SQLException {
        TableSelection scope = selection == null ? TableSelection.all() : selection;
        if (!needsListing(scope)) {
            return expandExplicit(scope);
        }
        try (JdbcConnection conn = factory.open(source)) {
            return expandWithConnection(conn, scope);
        }
    }

    /**
     * The selected tables as written, no metadata round trip. Used when listing cannot run because
     * the source connection failed. An "everything" selection expands to nothing here: without a
     * connection there is nothing to enumerate, and the caller marks the table list as unknown
     * instead of pretending the source is empty.
     */
    public List<TableId> expandExplicit(TableSelection selection) {
        TableSelection scope = selection == null ? TableSelection.all() : selection;
        Set<TableId> out = new LinkedHashSet<>();
        for (TableId id : scope.tables()) {
            add(out, scope, id.getSchema(), id.getTable());
        }
        return List.copyOf(out);
    }

    private List<TableId> expandWithConnection(JdbcConnection conn, TableSelection scope)
            throws SQLException {
        Set<TableId> out = new LinkedHashSet<>();
        for (String schema : schemasToRead(conn, scope)) {
            out.addAll(expandSchema(conn, scope, schema));
        }
        return List.copyOf(out);
    }

    /**
     * Which schemas need a table listing. "Everything" is resolved through the connection rather
     * than remembered, so a database created after the task started still lands in scope. Only the
     * two listing shapes get here; a table-level selection is already explicit.
     */
    private List<String> schemasToRead(JdbcConnection conn, TableSelection scope)
            throws SQLException {
        Set<String> schemas = new LinkedHashSet<>();
        if (scope.isAll()) {
            schemas.addAll(conn.listSchemas());
        } else {
            schemas.addAll(scope.schemas());
        }
        schemas.removeIf(scope::isSchemaExcluded);
        return List.copyOf(schemas);
    }

    private List<TableId> expandSchema(JdbcConnection conn, TableSelection scope, String schema)
            throws SQLException {
        Set<TableId> out = new LinkedHashSet<>();
        for (TableId id : conn.listTables(schema, List.of())) {
            add(out, scope, schema, id.getTable());
        }
        return List.copyOf(out);
    }

    /**
     * Keeps the selection's spelling of the schema (JDBC metadata reports a MySQL database as the
     * catalog) and drops excluded tables, so mappings and excludes resolve against one spelling.
     */
    private static void add(Set<TableId> out, TableSelection scope, String schema, String table) {
        if (scope.isSchemaExcluded(schema) || scope.isTableExcluded(schema, table)) {
            return;
        }
        out.add(TableId.of(schema, table));
    }

    private static boolean needsListing(TableSelection scope) {
        return scope.isAll() || !scope.schemas().isEmpty();
    }
}
