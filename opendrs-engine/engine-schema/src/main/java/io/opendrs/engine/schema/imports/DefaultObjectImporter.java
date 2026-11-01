package io.opendrs.engine.schema.imports;

import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.convert.ConversionResults;
import io.opendrs.engine.schema.convert.ConversionStatus;
import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.schema.meta.ObjectId;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes a converted batch on the target, one transaction per source object, in the batch's
 * dependency order. Objects already {@code FAILED} or {@code DROPPED} at conversion are carried
 * over without being executed; objects with no statements to run are recorded as {@code DROPPED}.
 */
public class DefaultObjectImporter implements ObjectImporter {

    private final Connection connection;

    public DefaultObjectImporter(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public ImportResults importObjects(ConversionResults conversionResults) throws SQLException {
        ImportResults results = new ImportResults();
        for (Map.Entry<DatabaseObject, ConversionResult> entry : conversionResults.entries()) {
            ObjectId id = entry.getKey().id();
            ConversionResult conversion = entry.getValue();
            if (conversion.getStatus() == ConversionStatus.FAILED
                    || conversion.getStatus() == ConversionStatus.DROPPED) {
                results.put(id, conversion);
                continue;
            }
            if (conversion.getStatements().isEmpty()) {
                results.put(id, outcome(ConversionStatus.DROPPED, conversion.getStatements()));
                continue;
            }
            executeInTransaction(conversion.getStatements());
            results.put(id, outcome(ConversionStatus.SUCCEEDED, conversion.getStatements()));
        }
        return results;
    }

    private void executeInTransaction(List<String> statements) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static ConversionResult outcome(ConversionStatus status, List<String> statements) {
        return ConversionResult.builder()
                .status(status)
                .statements(statements)
                .infos(List.of())
                .warnings(List.of())
                .error("")
                .build();
    }
}
