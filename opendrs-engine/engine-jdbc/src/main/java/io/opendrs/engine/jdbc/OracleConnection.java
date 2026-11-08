package io.opendrs.engine.jdbc;

import io.opendrs.engine.common.metadata.DatabaseType;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Minimal Oracle connection; privilege/CDC checks are not implemented yet. */
public class OracleConnection extends JdbcConnection {

    static final String SERVICE_URL_PATTERN =
            "jdbc:oracle:thin:@//${" + HOSTNAME + "}:${" + PORT + "}/${" + DATABASE + "}";

    static final String SID_URL_PATTERN =
            "jdbc:oracle:thin:@${" + HOSTNAME + "}:${" + PORT + "}:${" + DATABASE + "}";

    private static final Field PORT_FIELD = Field.of(PORT, "1521");

    /**
     * Oracle is the conditional case: the URL itself depends on {@code connectionType}, so the
     * pattern is chosen per configuration instead of being a single constant (the same fallback
     * Debezium's SQL Server connector uses).
     */
    private static final ConnectionFactory FACTORY = config -> open(
            resolveUrl(urlPattern(config), config, PORT_FIELD),
            connectProperties(config),
            null,
            null);

    private static final Pattern ORACLE_IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*");

    public OracleConnection(JdbcConnectionConfig config) {
        super(DatabaseType.ORACLE, config, FACTORY);
    }

    OracleConnection(JdbcConnectionConfig config, ConnectionFactory factory) {
        super(DatabaseType.ORACLE, config, factory);
    }

    static String urlPattern(JdbcConnectionConfig config) {
        String connectionType = extraString(config, "connectionType");
        boolean sid = connectionType != null && "SID".equalsIgnoreCase(connectionType);
        if (connectionType != null
                && !connectionType.isBlank()
                && !sid
                && !"SERVICE".equalsIgnoreCase(connectionType)) {
            throw new IllegalArgumentException("Unsupported Oracle connectionType: " + connectionType);
        }
        return sid ? SID_URL_PATTERN : SERVICE_URL_PATTERN;
    }

    static Properties connectProperties(JdbcConnectionConfig config) {
        Properties props = JdbcConnection.properties(config);
        props.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
        return props;
    }

    @Override
    public void ping() throws SQLException {
        queryOne("SELECT 1 FROM DUAL", rs -> rs.getInt(1));
    }

    @Override
    protected void onConnection(java.sql.Connection connection) throws SQLException {
        String pdb = extraString(config(), "pdb");
        if (pdb == null || pdb.isBlank()) {
            return;
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER SESSION SET CONTAINER = " + quoteOracleIdent(pdb.trim()));
        }
    }

    @Override
    protected String normalize(String ident) {
        if (ident == null) {
            return null;
        }
        return ident.toUpperCase(Locale.ROOT);
    }

    @Override
    protected boolean identEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return expected.equalsIgnoreCase(actual);
    }

    static String quoteOracleIdent(String ident) {
        if (!ORACLE_IDENT.matcher(ident).matches()) {
            throw new JdbcException("Invalid Oracle PDB name");
        }
        return "\"" + ident + "\"";
    }
}
