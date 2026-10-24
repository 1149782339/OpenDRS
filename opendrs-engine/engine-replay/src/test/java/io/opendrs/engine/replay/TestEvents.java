package io.opendrs.engine.replay;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.event.TransactionEvent;
import io.opendrs.engine.replay.relation.FieldsMetaData;
import java.util.List;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;

/**
 * Factory helpers for the change events the parallel-applier tests assemble. A single "row"
 * event factory with sensible defaults keeps the test bodies about behaviour, not boilerplate.
 */
public final class TestEvents {

    public static final FieldsMetaData PK_FIELDS = new FieldsMetaData(
            List.of("id"), List.of("name"), Map.of("id", Schema.INT32_SCHEMA, "name", Schema.STRING_SCHEMA));

    private TestEvents() {
    }

    /** A data-change row on {@code <schema>.<table>} with a single int primary key. */
    public static DataChangeEvent row(Operation operation, String schema, String table, int id, String txId) {
        // The write set derives the conflict key from before-values on DELETE, after-values
        // otherwise — mirror that here so both paths are exercisable.
        Map<String, Object> values = Map.of("id", id, "name", "n" + id);
        boolean delete = operation == Operation.DELETE;
        return DataChangeEvent.builder()
                .operation(operation)
                .tableId(new TableId(schema, null, table))
                .afterValues(delete ? Map.of() : values)
                .beforeValues(delete ? values : Map.of())
                .transactionId(txId)
                .fieldsMetaData(PK_FIELDS)
                .databaseType(DatabaseType.MYSQL)
                .topic("server." + schema + "." + table)
                .offset(1L)
                .partition(0)
                .build();
    }

    /** Transaction marker of the given status; transactionId is what the applier keys on. */
    public static TransactionEvent txMark(String txId, TransactionEvent.Status status) {
        return TransactionEvent.build()
                .transactionId(txId)
                .status(status)
                .topic("server.tx")
                .offset(1L)
                .partition(0)
                .build();
    }
}
