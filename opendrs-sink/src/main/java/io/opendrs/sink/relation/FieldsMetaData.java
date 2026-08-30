/*
 *  Copyright DbSink Authors.
 *  This source code is licensed under the Apache License Version 2.0, available
 *  at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.opendrs.sink.relation;

import io.opendrs.sink.naming.ColumnNamingStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

/**
 * Fields metadata extracted from a Debezium envelope (key schema + after/before).
 */
public class FieldsMetaData {
    private final List<String> primaryKeyFieldNames;
    private final List<String> nonPrimaryKeyFieldNames;
    private final Map<String, Schema> fieldSchemas;

    public FieldsMetaData(
            List<String> primaryKeyColumnNames,
            List<String> nonPrimaryKeyColumnNames,
            Map<String, Schema> fieldSchemas) {
        this.primaryKeyFieldNames = primaryKeyColumnNames;
        this.nonPrimaryKeyFieldNames = nonPrimaryKeyColumnNames;
        this.fieldSchemas = fieldSchemas;
    }

    public Map<String, Schema> getFieldSchemas() {
        return fieldSchemas;
    }

    public Schema getFieldSchema(String fieldName) {
        return fieldSchemas.get(fieldName);
    }

    public List<String> getPrimaryKeyFieldNames() {
        return primaryKeyFieldNames;
    }

    public List<String> getNonPrimaryKeyFieldNames() {
        return nonPrimaryKeyFieldNames;
    }

    public static FieldsMetaData extractFieldsMetaData(
            SourceRecord record, ColumnNamingStrategy columnNamingStrategy) {
        if (record == null || !(record.value() instanceof Struct value)) {
            throw new IllegalArgumentException("data-change record value must be a Struct");
        }
        return extractFieldsMetaData(record.keySchema(), value, columnNamingStrategy);
    }

    public static FieldsMetaData extractFieldsMetaData(
            Schema keySchema, Struct value, ColumnNamingStrategy columnNamingStrategy) {
        final Struct struct = value.getStruct("after") != null
                ? value.getStruct("after")
                : value.getStruct("before");
        if (struct == null) {
            throw new IllegalArgumentException("data-change envelope has neither after nor before");
        }
        final Map<String, Schema> fieldSchemas = getFieldSchema(struct, columnNamingStrategy);
        final Set<String> primaryKeyFieldNamesSet = getFieldNames(keySchema, columnNamingStrategy);
        final Set<String> fieldNamesSet = getFieldNames(struct.schema(), columnNamingStrategy);

        final List<String> primaryKeyFieldNames = new ArrayList<>();
        final List<String> nonPrimaryKeyFieldNames = new ArrayList<>();
        for (String fieldName : fieldNamesSet) {
            if (primaryKeyFieldNamesSet.contains(fieldName)) {
                primaryKeyFieldNames.add(fieldName);
            } else {
                nonPrimaryKeyFieldNames.add(fieldName);
            }
        }
        return new FieldsMetaData(primaryKeyFieldNames, nonPrimaryKeyFieldNames, fieldSchemas);
    }

    private static Set<String> getFieldNames(Schema schema, ColumnNamingStrategy columnNamingStrategy) {
        final Set<String> names = new LinkedHashSet<>();
        if (schema == null) {
            return names;
        }
        for (Field field : schema.fields()) {
            names.add(columnNamingStrategy.resolveColumnName(field.name()));
        }
        return names;
    }

    private static Map<String, Schema> getFieldSchema(Struct value, ColumnNamingStrategy columnNamingStrategy) {
        Map<String, Schema> map = new LinkedHashMap<>();
        for (Field field : value.schema().fields()) {
            String columnName = columnNamingStrategy.resolveColumnName(field.name());
            map.put(columnName, field.schema());
        }
        return map;
    }
}
