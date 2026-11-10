package io.opendrs.engine.schema.imports;

import io.opendrs.engine.common.naming.SchemaNamingStrategy;
import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.convert.ConversionResults;
import io.opendrs.engine.schema.convert.ConversionStatus;
import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.engine.schema.meta.ObjectType;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes a converted batch on the target, one transaction per source object, in the batch's
 * dependency order.
 *
 * <p>Objects already {@code FAILED} or {@code DROPPED} at conversion are carried over without being
 * executed; objects with no statements to run are recorded as {@code DROPPED}. Everything else runs
 * and is recorded as {@code SUCCEEDED}, or as {@code FAILED} with the driver's message when its
 * statements throw — the batch then continues with the next object.
 *
 * <p><b>Transaction assumption.</b> Per-object rollback only means something on a target with
 * transactional DDL, i.e. PostgreSQL. A MySQL target commits DDL implicitly, so a failure there
 * leaves the object's earlier statements applied; the object is still recorded as {@code FAILED}.
 */
public class DefaultObjectImporter implements ObjectImporter {

    /** One failure must not flood the report with a full driver trace. */
    private static final int MAX_ERROR_LENGTH = 500;

    private final Connection connection;

    /**
     * Target schema the connection was last pointed at, or {@code null} while it is still wherever
     * the caller left it. Reset per {@link #importObjects} call so a reused importer never assumes
     * the connection survived untouched between batches.
     */
    private String currentSchema;

    public DefaultObjectImporter(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public ImportResults importObjects(
            ConversionResults conversionResults, ImportConfiguration configuration) throws SQLException {
        Objects.requireNonNull(conversionResults, "conversionResults");
        ImportConfiguration options =
                configuration == null ? ImportConfiguration.defaults() : configuration;
        currentSchema = null;
        ImportListener listener = options.getListener();
        ImportResults results = new ImportResults();
        for (Map.Entry<DatabaseObject, ConversionResult> entry : conversionResults.entries()) {
            ObjectId id = entry.getKey().id();
            ConversionResult conversion = entry.getValue();
            if (conversion.getStatus() == ConversionStatus.FAILED
                    || conversion.getStatus() == ConversionStatus.DROPPED) {
                record(results, listener, id, conversion);
                continue;
            }
            if (conversion.getStatements().isEmpty()) {
                record(results, listener, id, outcome(ConversionStatus.DROPPED, conversion, ""));
                continue;
            }
            try {
                useSchemaOf(id, options.getSchemaNamingStrategy());
                executeInTransaction(
                        conversion.getStatements(), options.getStatementTimeoutSeconds());
                record(results, listener, id, outcome(ConversionStatus.SUCCEEDED, conversion, ""));
            } catch (SQLException ex) {
                if (isConnectionFailure(ex)) {
                    throw ex;
                }
                record(results, listener, id, outcome(ConversionStatus.FAILED, conversion, describe(ex)));
            }
        }
        return results;
    }

    private static void record(
            ImportResults results, ImportListener listener, ObjectId id, ConversionResult result) {
        results.put(id, result);
        listener.onObject(id, result);
    }

    /**
     * Points the connection at the schema this object should be created in, if a strategy is
     * configured and the answer differs from where the connection already is.
     */
    private void useSchemaOf(ObjectId id, SchemaNamingStrategy naming) throws SQLException {
        // CREATE SCHEMA names its target. search_path is how unqualified table DDL finds a schema,
        // and pointing it at a schema that this statement is about to create does nothing useful.
        if (naming == null || id.type() == ObjectType.SCHEMA) {
            return;
        }
        String target = naming.resolveSchemaName(id.schema());
        if (target == null || target.isBlank() || target.equals(currentSchema)) {
            return;
        }
        connection.setSchema(target);
        currentSchema = target;
    }

    /**
     * Runs one object's statements as a single transaction and restores the connection's
     * auto-commit mode afterwards.
     *
     * <p>Written out by hand instead of try-with-resources so a failing {@code close} cannot mask
     * the statement failure that caused it: every cleanup step is folded into the failure returned
     * to the caller, and a failure with no earlier cause becomes the failure itself.
     */
    private void executeInTransaction(List<String> statements, int timeoutSeconds)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        SQLException failure = null;
        Statement statement = null;
        try {
            statement = connection.createStatement();
            if (timeoutSeconds > 0) {
                statement.setQueryTimeout(timeoutSeconds);
            }
            for (String sql : statements) {
                statement.execute(sql);
            }
            connection.commit();
        } catch (SQLException ex) {
            failure = rollback(ex);
        } finally {
            failure = closeStatement(statement, failure);
            failure = restoreAutoCommit(autoCommit, failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Rolls the failed object back. A rollback that itself throws leaves the connection in an
     * unknown state, so it is reported as a connection-level failure ({@code 08000}) with the
     * original statement error kept as its cause and suppressed exception — the caller must abort
     * the batch rather than keep issuing statements on a connection nobody can reason about.
     */
    private SQLException rollback(SQLException failure) {
        try {
            connection.rollback();
            return failure;
        } catch (SQLException rollbackFailure) {
            SQLException fatal = new SQLException(
                    "rollback failed: " + describe(rollbackFailure), "08000", rollbackFailure);
            fatal.addSuppressed(failure);
            return fatal;
        }
    }

    private static SQLException closeStatement(Statement statement, SQLException failure) {
        if (statement == null) {
            return failure;
        }
        try {
            statement.close();
            return failure;
        } catch (SQLException ex) {
            if (failure == null) {
                return ex;
            }
            failure.addSuppressed(ex);
            return failure;
        }
    }

    private SQLException restoreAutoCommit(boolean autoCommit, SQLException failure) {
        try {
            connection.setAutoCommit(autoCommit);
            return failure;
        } catch (SQLException ex) {
            if (failure == null) {
                return ex;
            }
            failure.addSuppressed(ex);
            return failure;
        }
    }

    /** SQLState class {@code 08}: the connection is gone, so no later object can succeed either. */
    private static boolean isConnectionFailure(SQLException ex) {
        for (SQLException current = ex; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("08")) {
                return true;
            }
        }
        return false;
    }

    /** Driver message for the report, prefixed with the SQLState; capped so one failure cannot flood it. */
    private static String describe(SQLException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        String state = ex.getSQLState();
        String described = state == null || state.isBlank() ? message : state + ": " + message;
        return described.length() <= MAX_ERROR_LENGTH
                ? described
                : described.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    /**
     * Import outcome for one object. The conversion's statements, infos and warnings are carried
     * over — a warning raised while converting is still worth seeing next to the object that ran —
     * and {@code error} is this importer's own field.
     */
    private static ConversionResult outcome(
            ConversionStatus status, ConversionResult conversion, String error) {
        return ConversionResult.builder()
                .status(status)
                .statements(conversion.getStatements())
                .infos(conversion.getInfos())
                .warnings(conversion.getWarnings())
                .error(error)
                .build();
    }
}
