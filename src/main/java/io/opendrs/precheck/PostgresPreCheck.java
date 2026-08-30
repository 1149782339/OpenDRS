package io.opendrs.precheck;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.dialect.PostgresDialect;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PostgresPreCheck implements DbPreCheck {

    private static final String DEFAULT_SCHEMA = "public";

    private final JdbcConnectionFactory factory;
    private final DbDialect dialect;

    @Autowired
    public PostgresPreCheck(JdbcConnectionFactory factory, PostgresDialect dialect) {
        this.factory = factory;
        this.dialect = dialect;
    }

    PostgresPreCheck(JdbcConnectionFactory factory, DbDialect dialect) {
        this.factory = factory;
        this.dialect = dialect;
    }

    @Override
    public DbType type() {
        return DbType.POSTGRESQL;
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
                if (!dialect.schemaExists(conn, ref.schema())) {
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
            for (String schema : targetSchemas(safeTables)) {
                if (dialect.schemaExists(conn, schema)) {
                    results.add(CheckResult.ok("schema_exists", "Target schema exists: " + schema));
                    if (dialect.hasSchemaPrivilege(conn, schema)) {
                        results.add(CheckResult.ok("schema_privilege", "CREATE privilege on schema: " + schema));
                    } else {
                        results.add(CheckResult.fail("schema_privilege", "No CREATE privilege on schema: " + schema));
                    }
                } else {
                    results.add(CheckResult.ok(
                            "schema_exists",
                            "Target schema does not exist yet: " + schema));
                }
            }
            for (Table table : safeTables) {
                TableRef ref = table.ref();
                String schema = schemaOf(ref);
                if (dialect.tableExists(conn, schema, ref.table())) {
                    results.add(CheckResult.fail(
                            "table_absent",
                            "Target table already exists: " + schema + "." + ref.table(),
                            ref));
                } else {
                    results.add(CheckResult.ok(
                            "table_absent",
                            "Target table does not exist: " + schema + "." + ref.table(),
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
            conn.queryOne("SELECT 1 FROM " + qualified(ref) + " WHERE false", rs -> rs.getInt(1));
            return CheckResult.ok("read_privilege", "SELECT is allowed", ref);
        } catch (AppException ex) {
            return CheckResult.fail("read_privilege", "SELECT is not allowed: " + ex.getMessage(), ref);
        }
    }

    private static Set<String> targetSchemas(List<Table> tables) {
        Set<String> schemas = new LinkedHashSet<>();
        if (tables != null) {
            for (Table table : tables) {
                if (table.ref().schema() != null && !table.ref().schema().isBlank()) {
                    schemas.add(table.ref().schema());
                }
            }
        }
        if (schemas.isEmpty()) {
            schemas.add(DEFAULT_SCHEMA);
        }
        return schemas;
    }

    private static String schemaOf(TableRef ref) {
        if (ref.schema() == null || ref.schema().isBlank()) {
            return DEFAULT_SCHEMA;
        }
        return ref.schema();
    }

    private static String qualified(TableRef ref) {
        return quoteIdent(ref.schema()) + "." + quoteIdent(ref.table());
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
