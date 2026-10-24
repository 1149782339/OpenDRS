package io.opendrs.engine.precheck.fact;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.DependencyUnavailableException;
import io.opendrs.engine.precheck.Side;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.JdbcException;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the rules need to know about one endpoint, read at most once per run.
 *
 * <p>This is the whole reason the checks can be split into "collect" and "judge": the parameters,
 * the grants and the table metadata each cost one round trip and are shared by several rules, so a
 * rule never opens a connection and never writes SQL.
 *
 * <p>A failed connection is remembered and rethrown as {@link DependencyUnavailableException} with
 * the connection check as root cause. That is what turns "the source is down" into one failed row
 * plus a column of blocked rows, instead of fifteen identical connection errors.
 */
public abstract class EndpointFacts implements AutoCloseable {

    private final Side side;
    private final JdbcConnectionConfig config;
    private final JdbcConnectionFactory factory;

    private JdbcConnection connection;
    private DependencyUnavailableException unavailable;
    private ServerVersion version;
    private Map<String, String> parameters;
    private Map<TableRef, TableFacts> tableFacts;

    protected EndpointFacts(Side side, JdbcConnectionConfig config, JdbcConnectionFactory factory) {
        this.side = side;
        this.config = config;
        this.factory = factory;
    }

    public final Side side() {
        return side;
    }

    public final JdbcConnectionConfig config() {
        return config;
    }

    public final DatabaseType type() {
        return config.getType();
    }

    /** The open connection, or a BLOCKED signal naming the connection check. */
    public final JdbcConnection connection() {
        if (connection != null) {
            return connection;
        }
        if (unavailable != null) {
            throw unavailable;
        }
        try {
            connection = factory.open(config);
            return connection;
        } catch (JdbcException ex) {
            unavailable = new DependencyUnavailableException(connectionCheckId(), ex.getMessage());
            throw unavailable;
        }
    }

    private String connectionCheckId() {
        return side == Side.SOURCE ? CheckIds.SOURCE_CONNECTION : CheckIds.TARGET_CONNECTION;
    }

    public final ServerVersion version() {
        if (version == null) {
            String raw;
            try {
                raw = connection().unwrap().getMetaData().getDatabaseProductVersion();
            } catch (java.sql.SQLException ex) {
                throw new JdbcException("读取数据库版本失败: " + ex.getMessage(), ex);
            }
            version = ServerVersion.parse(raw);
        }
        return version;
    }

    /** Server parameters, keys lower-cased so lookups do not depend on the product's casing. */
    public final Map<String, String> parameters() {
        if (parameters == null) {
            parameters = Map.copyOf(loadParameters(connection()));
        }
        return parameters;
    }

    public final CharsetInfo charset() {
        return loadCharset(connection());
    }

    public final TimezoneInfo timezone() {
        return loadTimezone(connection());
    }

    public final InstanceState instanceState() {
        return loadInstanceState(connection());
    }

    /** Required privileges that are not granted globally or on every one of {@code schemas}. */
    public final List<String> missingPermissions(Collection<String> schemas, List<String> required) {
        return loadMissingPermissions(connection(), schemas, required);
    }

    /** Bulk table metadata for the given tables; loaded once, then served from the cache. */
    public final Map<TableRef, TableFacts> tableFacts(Collection<Table> tables) {
        if (tableFacts == null) {
            Map<TableRef, TableFacts> loaded = new LinkedHashMap<>();
            loadTableFacts(connection(), tables, loaded);
            for (Table table : tables) {
                loaded.putIfAbsent(table.ref(), TableFacts.missing(table.ref()));
            }
            tableFacts = Map.copyOf(loaded);
        }
        return tableFacts;
    }

    protected abstract Map<String, String> loadParameters(JdbcConnection conn);

    protected abstract CharsetInfo loadCharset(JdbcConnection conn);

    protected abstract TimezoneInfo loadTimezone(JdbcConnection conn);

    protected abstract InstanceState loadInstanceState(JdbcConnection conn);

    protected abstract List<String> loadMissingPermissions(
            JdbcConnection conn, Collection<String> schemas, List<String> required);

    /** Fills {@code out} for the tables that actually exist; the base class marks the rest missing. */
    protected abstract void loadTableFacts(
            JdbcConnection conn, Collection<Table> tables, Map<TableRef, TableFacts> out);

    /** Shared helper: {@code schema.table} key, lower-cased for products that fold identifiers. */
    protected static String key(String schema, String table) {
        return (schema == null ? "" : schema) + "." + (table == null ? "" : table);
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // closing after a successful run must not turn into a failed precheck
        } finally {
            connection = null;
        }
    }
}
