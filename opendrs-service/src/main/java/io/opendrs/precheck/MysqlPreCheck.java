package io.opendrs.precheck;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.dialect.MysqlDialect;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MysqlPreCheck implements DbPreCheck {

    private final JdbcConnectionFactory factory;
    private final DbDialect dialect;

    @Autowired
    public MysqlPreCheck(JdbcConnectionFactory factory, MysqlDialect dialect) {
        this.factory = factory;
        this.dialect = dialect;
    }

    MysqlPreCheck(JdbcConnectionFactory factory, DbDialect dialect) {
        this.factory = factory;
        this.dialect = dialect;
    }

    @Override
    public DbType type() {
        return DbType.MYSQL;
    }

    @Override
    public void validate(ConnectionInfo info, List<Table> tables) {
        try (JdbcConnection conn = factory.open(info)) {
            for (Table table : tables == null ? List.<Table>of() : tables) {
                TableRef ref = table.ref();
                if (!dialect.schemaExists(conn, ref.schema())) {
                    throw AppException.of(ErrorCode.PARAM_INVALID, "Schema does not exist: " + ref.schema());
                }
                if (!dialect.tableExists(conn, ref.schema(), ref.table())) {
                    throw AppException.of(
                            ErrorCode.PARAM_INVALID,
                            "Table does not exist: " + ref.schema() + "." + ref.table());
                }
            }
        }
    }

    @Override
    public List<CheckResult> precheckSource(ConnectionInfo info, List<Table> tables) {
        List<CheckResult> results = new ArrayList<>();
        try (JdbcConnection conn = factory.open(info)) {
            for (Table table : tables == null ? List.<Table>of() : tables) {
                TableRef ref = table.ref();
                boolean schemaOk = dialect.schemaExists(conn, ref.schema());
                if (!schemaOk) {
                    results.add(CheckResult.fail("schema_exists", "Schema does not exist: " + ref.schema(), ref));
                    continue;
                }
                results.add(CheckResult.ok("schema_exists", "Schema exists: " + ref.schema(), ref));
                if (!dialect.tableExists(conn, ref.schema(), ref.table())) {
                    results.add(CheckResult.fail(
                            "table_exists",
                            "Table does not exist: " + ref.schema() + "." + ref.table(),
                            ref));
                    continue;
                }
                results.add(CheckResult.ok("table_exists", "Table exists: " + ref.schema() + "." + ref.table(), ref));
                results.add(checkReadPrivilege(conn, ref));
            }
            results.addAll(checkBinlog(conn));
        } catch (AppException ex) {
            results.add(CheckResult.fail("connect", ex.getMessage()));
        }
        return results;
    }

    @Override
    public List<CheckResult> precheckTarget(ConnectionInfo info, List<Table> tables) {
        List<CheckResult> results = new ArrayList<>();
        try (JdbcConnection conn = factory.open(info)) {
            List<Table> safeTables = tables == null ? List.of() : tables;
            for (String schema : targetSchemas(info, safeTables)) {
                if (!dialect.schemaExists(conn, schema)) {
                    results.add(CheckResult.fail("schema_exists", "Target schema does not exist: " + schema));
                    continue;
                }
                results.add(CheckResult.ok("schema_exists", "Target schema exists: " + schema));
                if (dialect.hasSchemaPrivilege(conn, schema)) {
                    results.add(CheckResult.ok("schema_privilege", "CREATE privilege on schema: " + schema));
                } else {
                    results.add(CheckResult.fail("schema_privilege", "No CREATE privilege on schema: " + schema));
                }
            }
            for (Table table : safeTables) {
                TableRef ref = table.ref();
                if (dialect.tableExists(conn, ref.schema(), ref.table())) {
                    results.add(CheckResult.fail(
                            "table_absent",
                            "Target table already exists: " + ref.schema() + "." + ref.table(),
                            ref));
                } else {
                    results.add(CheckResult.ok(
                            "table_absent",
                            "Target table does not exist: " + ref.schema() + "." + ref.table(),
                            ref));
                }
            }
        } catch (AppException ex) {
            results.add(CheckResult.fail("connect", ex.getMessage()));
        }
        return results;
    }

    private CheckResult checkReadPrivilege(JdbcConnection conn, TableRef ref) {
        try {
            conn.queryOne("SELECT 1 FROM " + qualified(ref) + " WHERE 1=0", rs -> rs.getInt(1));
            return CheckResult.ok("read_privilege", "SELECT is allowed", ref);
        } catch (AppException ex) {
            return CheckResult.fail("read_privilege", "SELECT is not allowed: " + ex.getMessage(), ref);
        }
    }

    private List<CheckResult> checkBinlog(JdbcConnection conn) {
        List<CheckResult> results = new ArrayList<>();
        try {
            Object logBin = conn.queryOne("SELECT @@log_bin", rs -> rs.getObject(1));
            if (isOn(logBin)) {
                results.add(CheckResult.ok("log_bin", "log_bin is ON"));
            } else {
                results.add(CheckResult.fail("log_bin", "log_bin is not ON"));
            }
        } catch (AppException ex) {
            results.add(CheckResult.fail("log_bin", "Unable to read log_bin: " + ex.getMessage()));
        }
        try {
            String format = conn.queryOne("SELECT @@binlog_format", rs -> rs.getString(1));
            if (format != null && "ROW".equalsIgnoreCase(format.trim())) {
                results.add(CheckResult.ok("binlog_format", "binlog_format is ROW"));
            } else {
                results.add(CheckResult.fail("binlog_format", "binlog_format is not ROW: " + format));
            }
        } catch (AppException ex) {
            results.add(CheckResult.fail("binlog_format", "Unable to read binlog_format: " + ex.getMessage()));
        }
        try {
            String gtid = conn.queryOne("SELECT @@gtid_mode", rs -> rs.getString(1));
            if (gtid != null && "ON".equalsIgnoreCase(gtid.trim())) {
                results.add(CheckResult.ok("gtid_mode", "gtid_mode is ON"));
            } else {
                results.add(CheckResult.ok("gtid_mode", "gtid_mode is not ON (warning only): " + gtid));
            }
        } catch (AppException ex) {
            results.add(CheckResult.ok("gtid_mode", "Unable to read gtid_mode (warning only)"));
        }
        return results;
    }

    private static Set<String> targetSchemas(ConnectionInfo info, List<Table> tables) {
        Set<String> schemas = new LinkedHashSet<>();
        if (tables != null) {
            for (Table table : tables) {
                if (table.ref().schema() != null && !table.ref().schema().isBlank()) {
                    schemas.add(table.ref().schema());
                }
            }
        }
        if (schemas.isEmpty() && info.getDbName() != null && !info.getDbName().isBlank()) {
            schemas.add(info.getDbName());
        }
        return schemas;
    }

    private static String qualified(TableRef ref) {
        return quoteIdent(ref.schema()) + "." + quoteIdent(ref.table());
    }

    private static String quoteIdent(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }

    static boolean isOn(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "on".equals(text) || "true".equals(text) || "1".equals(text);
    }
}
