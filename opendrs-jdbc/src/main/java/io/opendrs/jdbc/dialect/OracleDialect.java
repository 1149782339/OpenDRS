package io.opendrs.jdbc.dialect;

import io.opendrs.jdbc.JdbcException;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcUrlBuilder;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.common.metadata.DatabaseType;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Minimal Oracle dialect; privilege/CDC checks are not implemented yet. */
public class OracleDialect extends AbstractDbDialect {

    private static final Pattern ORACLE_IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*");

    public OracleDialect() {
        super(DatabaseType.ORACLE);
    }

    @Override
    public String testSql() {
        return "SELECT 1 FROM DUAL";
    }

    @Override
    public void applyConnectProperties(Properties props) {
        props.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
    }

    @Override
    public void afterConnect(JdbcConnection conn, JdbcConnectionConfig config) {
        String pdb = JdbcUrlBuilder.extraString(config, "pdb");
        if (pdb == null || pdb.isBlank()) {
            return;
        }
        conn.execute("ALTER SESSION SET CONTAINER = " + quoteOracleIdent(pdb.trim()));
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
