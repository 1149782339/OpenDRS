package io.opendrs.engine.precheck.fact;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.DependencyUnavailableException;
import io.opendrs.engine.precheck.Side;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.JdbcException;
import io.opendrs.common.metadata.TableId;
import java.sql.SQLException;
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
    private Map<TableId, TableFacts> tableFacts;

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
                raw = connection().connection().getMetaData().getDatabaseProductVersion();
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
            try {
                parameters = Map.copyOf(loadParameters(connection()));
            } catch (SQLException ex) {
                throw readFailed("读取服务端参数", ex);
            }
        }
        return parameters;
    }

    public final CharsetInfo charset() {
        try {
            return loadCharset(connection());
        } catch (SQLException ex) {
            throw readFailed("读取字符集", ex);
        }
    }

    public final TimezoneInfo timezone() {
        try {
            return loadTimezone(connection());
        } catch (SQLException ex) {
            throw readFailed("读取时区", ex);
        }
    }

    public final InstanceState instanceState() {
        try {
            return loadInstanceState(connection());
        } catch (SQLException ex) {
            throw readFailed("读取实例状态", ex);
        }
    }

    /** Required privileges that are not granted globally or on every one of {@code schemas}. */
    public final List<String> missingPermissions(Collection<String> schemas, List<String> required) {
        try {
            return loadMissingPermissions(connection(), schemas, required);
        } catch (SQLException ex) {
            throw readFailed("读取权限", ex);
        }
    }

    /** Bulk table metadata for the given tables; loaded once, then served from the cache. */
    public final Map<TableId, TableFacts> tableFacts(Collection<TableId> tables) {
        if (tableFacts == null) {
            Map<TableId, TableFacts> loaded = new LinkedHashMap<>();
            try {
                loadTableFacts(connection(), tables, loaded);
            } catch (SQLException ex) {
                throw readFailed("读取表元数据", ex);
            }
            for (TableId id : tables) {
                loaded.putIfAbsent(id, TableFacts.missing(id));
            }
            tableFacts = Map.copyOf(loaded);
        }
        return tableFacts;
    }

    private static JdbcException readFailed(String what, SQLException ex) {
        return new JdbcException(what + "失败: " + ex.getMessage(), ex);
    }

    protected abstract Map<String, String> loadParameters(JdbcConnection conn) throws SQLException;

    protected abstract CharsetInfo loadCharset(JdbcConnection conn) throws SQLException;

    protected abstract TimezoneInfo loadTimezone(JdbcConnection conn) throws SQLException;

    protected abstract InstanceState loadInstanceState(JdbcConnection conn) throws SQLException;

    protected abstract List<String> loadMissingPermissions(
            JdbcConnection conn, Collection<String> schemas, List<String> required)
            throws SQLException;

    /** Fills {@code out} for the tables that actually exist; the base class marks the rest missing. */
    protected abstract void loadTableFacts(
            JdbcConnection conn, Collection<TableId> tables, Map<TableId, TableFacts> out)
            throws SQLException;

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
