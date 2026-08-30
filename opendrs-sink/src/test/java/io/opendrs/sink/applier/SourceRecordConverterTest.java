package io.opendrs.sink.applier;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.sink.event.ChangeEvent;
import io.opendrs.sink.event.DataChangeEvent;
import io.opendrs.sink.event.Operation;
import io.opendrs.sink.event.SchemaChangeEvent;
import io.opendrs.sink.naming.DefaultColumnNamingStrategy;
import io.opendrs.sink.naming.DefaultTableNamingStrategy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class SourceRecordConverterTest {

    private final SourceRecordConverter converter = new SourceRecordConverter(
            new DefaultTableNamingStrategy(), new DefaultColumnNamingStrategy(), true);

    @Test
    void convertsInsertEnvelope() {
        SourceRecord record = dataRecord("c", 7, "alice");
        Optional<ChangeEvent> event = converter.convertOne(record);
        assertThat(event).isPresent().get().isInstanceOf(DataChangeEvent.class);
        DataChangeEvent data = (DataChangeEvent) event.orElseThrow();
        assertThat(data.getOperation()).isEqualTo(Operation.CREATE);
        assertThat(data.getAfterValues().get("id")).isEqualTo(7);
        assertThat(data.getAfterValues().get("name")).isEqualTo("alice");
        assertThat(data.getTableId().getCatalog()).isEqualTo("inventory");
        assertThat(data.getTableId().getTable()).isEqualTo("customers");
        assertThat(data.getFieldsMetaData().getPrimaryKeyFieldNames()).containsExactly("id");
    }

    @Test
    void convertsSchemaChange() {
        Schema sourceSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.mysql.Source")
                .field("connector", Schema.STRING_SCHEMA)
                .field("db", Schema.STRING_SCHEMA)
                .field("table", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.mysql.SchemaChangeValue")
                .field("source", sourceSchema)
                .field("ddl", Schema.STRING_SCHEMA)
                .build();
        Struct source = new Struct(sourceSchema)
                .put("connector", "mysql")
                .put("db", "inventory")
                .put("table", "customers");
        Struct value = new Struct(valueSchema)
                .put("source", source)
                .put("ddl", "CREATE TABLE customers (id INT PRIMARY KEY)");
        SourceRecord record = new SourceRecord(
                Map.of("server", "s"), Map.of("pos", 100L), "opendrs.schema", valueSchema, value);
        ChangeEvent event = converter.convertOne(record).orElseThrow();
        assertThat(event).isInstanceOf(SchemaChangeEvent.class);
        assertThat(((SchemaChangeEvent) event).getDDL()).contains("CREATE TABLE");
    }

    @Test
    void skipsNotificationAndNullValue() {
        Schema notification = SchemaBuilder.struct()
                .name("io.debezium.pipeline.notification.Notification")
                .field("aggregate_type", Schema.STRING_SCHEMA)
                .field("type", Schema.STRING_SCHEMA)
                .build();
        Struct value = new Struct(notification).put("aggregate_type", "Initial Snapshot").put("type", "COMPLETED");
        SourceRecord notificationRecord = new SourceRecord(
                Map.of(), Map.of(), "opendrs.task.1.notification", notification, value);
        assertThat(converter.convertOne(notificationRecord)).isEmpty();
        assertThat(converter.convertOne(null)).isEmpty();
        SourceRecord tombstone = new SourceRecord(Map.of(), Map.of(), "t", Schema.STRING_SCHEMA, null);
        assertThat(converter.convertOne(tombstone)).isEmpty();
    }

    @Test
    void omitsSchemaChangeWhenDisabled() {
        SourceRecordConverter disabled = new SourceRecordConverter(
                new DefaultTableNamingStrategy(), new DefaultColumnNamingStrategy(), false);
        Schema sourceSchema = SchemaBuilder.struct()
                .field("connector", Schema.STRING_SCHEMA)
                .field("db", Schema.STRING_SCHEMA)
                .field("table", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.mysql.SchemaChangeValue")
                .field("source", sourceSchema)
                .field("ddl", Schema.STRING_SCHEMA)
                .build();
        Struct source = new Struct(sourceSchema).put("connector", "mysql").put("db", "d").put("table", "t");
        Struct value = new Struct(valueSchema).put("source", source).put("ddl", "CREATE TABLE t (id INT)");
        SourceRecord record = new SourceRecord(Map.of(), Map.of(), "schema", valueSchema, value);
        assertThat(disabled.convert(List.of(record))).isEmpty();
    }

    private static SourceRecord dataRecord(String op, int id, String name) {
        Schema row = SchemaBuilder.struct()
                .optional()
                .field("id", Schema.INT32_SCHEMA)
                .field("name", Schema.STRING_SCHEMA)
                .build();
        Schema source = SchemaBuilder.struct()
                .field("connector", Schema.STRING_SCHEMA)
                .field("db", Schema.STRING_SCHEMA)
                .field("table", Schema.STRING_SCHEMA)
                .build();
        Schema envelope = SchemaBuilder.struct()
                .name("opendrs.task.1.inventory.customers.Envelope")
                .field("op", Schema.STRING_SCHEMA)
                .field("before", row)
                .field("after", row)
                .field("source", source)
                .build();
        Schema key = SchemaBuilder.struct().field("id", Schema.INT32_SCHEMA).build();
        Struct after = new Struct(row).put("id", id).put("name", name);
        Struct value = new Struct(envelope)
                .put("op", op)
                .put("before", null)
                .put("after", after)
                .put("source", new Struct(source).put("connector", "mysql").put("db", "inventory").put("table", "customers"));
        return new SourceRecord(
                Map.of("server", "s"),
                Map.of("file", "mysql-bin.000001", "pos", 1200L),
                "opendrs.task.1.inventory.customers",
                null,
                key,
                new Struct(key).put("id", id),
                envelope,
                value);
    }
}
