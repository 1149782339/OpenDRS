package io.opendrs.sink.applier;

import io.opendrs.sink.dialect.DatabaseType;
import io.opendrs.sink.event.ChangeEvent;
import io.opendrs.sink.event.DataChangeEvent;
import io.opendrs.sink.event.Operation;
import io.opendrs.sink.event.SchemaChangeEvent;
import io.opendrs.sink.event.TransactionEvent;
import io.opendrs.sink.naming.ColumnNamingStrategy;
import io.opendrs.sink.naming.TableNamingStrategy;
import io.opendrs.sink.relation.FieldsMetaData;
import io.opendrs.sink.relation.TableId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts Debezium embedded {@link SourceRecord}s into sink {@link ChangeEvent}s.
 * Connect {@code Schema}/{@code Struct} are used as a library; there is no Connector/Task SPI.
 */
public final class SourceRecordConverter {

    static final String SCHEMA_CHANGE_EVENT = "SchemaChangeValue";
    static final String TRANSACTION_EVENT = "TransactionMetadataValue";
    static final String HEARTBEAT_EVENT = "Heartbeat";

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceRecordConverter.class);

    private final TableNamingStrategy tableNamingStrategy;
    private final ColumnNamingStrategy columnNamingStrategy;
    private final boolean applyDdlEnabled;

    public SourceRecordConverter(
            TableNamingStrategy tableNamingStrategy,
            ColumnNamingStrategy columnNamingStrategy,
            boolean applyDdlEnabled) {
        this.tableNamingStrategy = tableNamingStrategy;
        this.columnNamingStrategy = columnNamingStrategy;
        this.applyDdlEnabled = applyDdlEnabled;
    }

    public List<ChangeEvent> convert(Iterable<SourceRecord> records) {
        List<ChangeEvent> events = new ArrayList<>();
        for (SourceRecord record : records) {
            convertOne(record).ifPresent(events::add);
        }
        return events;
    }

    public Optional<ChangeEvent> convertOne(SourceRecord record) {
        if (record == null || !(record.value() instanceof Struct value)) {
            return Optional.empty();
        }
        Schema schema = record.valueSchema();
        if (schema == null || schema.name() == null) {
            return Optional.empty();
        }
        String schemaName = schema.name();
        String topic = record.topic();
        Integer partition = record.kafkaPartition();
        long offset = extractOffset(record);

        if (schemaName.endsWith(SCHEMA_CHANGE_EVENT)) {
            if (!applyDdlEnabled) {
                LOGGER.info("schema change event is omitted");
                return Optional.empty();
            }
            String ddl = value.schema().field("ddl") != null ? value.getString("ddl") : null;
            if (ddl == null || ddl.isBlank()) {
                LOGGER.debug("schema change event has no ddl, skip");
                return Optional.empty();
            }
            return Optional.of(SchemaChangeEvent.builder()
                    .ddl(ddl)
                    .tableId(getTableIdentifier(value))
                    .offset(offset)
                    .partition(partition)
                    .topic(topic)
                    .databaseType(sourceDatabaseType(value))
                    .build());
        }
        if (schemaName.endsWith(TRANSACTION_EVENT)) {
            String txId = value.getString("id");
            String status = value.getString("status");
            return Optional.of(TransactionEvent.build()
                    .transactionId(txId)
                    .status(TransactionEvent.Status.valueOf(status))
                    .offset(offset)
                    .partition(partition)
                    .topic(topic)
                    .build());
        }
        if (schemaName.endsWith(HEARTBEAT_EVENT)) {
            LOGGER.debug("ignore heartbeat event");
            return Optional.empty();
        }
        if (schema.field("op") == null) {
            LOGGER.debug("ignore non-envelope record topic={} schema={}", topic, schemaName);
            return Optional.empty();
        }
        Operation operation = Operation.fromString(value.getString("op"));
        return Optional.of(DataChangeEvent.builder()
                .offset(offset)
                .partition(partition == null ? 0 : partition)
                .topic(topic)
                .tableId(getTableIdentifier(value))
                .operation(operation)
                .afterValues(getValues(value.getStruct("after")))
                .beforeValues(getValues(value.getStruct("before")))
                .transactionId(getTransactionId(value))
                .fieldsMetaData(FieldsMetaData.extractFieldsMetaData(record.keySchema(), value, columnNamingStrategy))
                .databaseType(sourceDatabaseType(value))
                .build());
    }

    TableId getTableIdentifier(Struct value) {
        Struct source = value.schema().field("source") != null ? value.getStruct("source") : null;
        if (source == null) {
            return tableNamingStrategy.resolveTableId(new TableId(null, null, null));
        }
        String catalog = source.schema().field("db") != null ? source.getString("db") : null;
        String schema = source.schema().field("schema") != null ? source.getString("schema") : null;
        String table = source.schema().field("table") != null ? source.getString("table") : null;
        return tableNamingStrategy.resolveTableId(new TableId(catalog, schema, table));
    }

    private static DatabaseType sourceDatabaseType(Struct value) {
        if (value.schema().field("source") == null) {
            return DatabaseType.MYSQL;
        }
        Struct source = value.getStruct("source");
        if (source == null || source.schema().field("connector") == null) {
            return DatabaseType.MYSQL;
        }
        String connector = source.getString("connector");
        if (connector == null || connector.isBlank()) {
            return DatabaseType.MYSQL;
        }
        return DatabaseType.valueOf(connector.toUpperCase(Locale.ROOT));
    }

    private static String getTransactionId(Struct value) {
        Field field = value.schema().field("transaction");
        if (field == null) {
            return null;
        }
        Struct transactionMetaData = value.getStruct("transaction");
        if (transactionMetaData == null) {
            return null;
        }
        return transactionMetaData.getString("id");
    }

    private Map<String, Object> getValues(Struct value) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (value == null) {
            return map;
        }
        for (Field field : value.schema().fields()) {
            String columnName = columnNamingStrategy.resolveColumnName(field.name());
            map.put(columnName, value.get(field));
        }
        return map;
    }

    private static long extractOffset(SourceRecord record) {
        Map<String, ?> sourceOffset = record.sourceOffset();
        if (sourceOffset == null) {
            return 0L;
        }
        Object pos = sourceOffset.get("pos");
        if (pos instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
