/*
 *  Copyright DbSink Authors.
 *  This source code is licensed under the Apache License Version 2.0, available
 *  at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.opendrs.sink.applier;

import io.opendrs.sink.SinkConfig;
import io.opendrs.sink.context.TaskContext;
import io.opendrs.sink.event.ChangeEvent;
import io.opendrs.sink.exception.ApplierException;
import java.util.Collection;
import java.util.List;
import org.apache.kafka.connect.source.SourceRecord;

/**
 * Parses Debezium embedded {@link SourceRecord}s and applies them through {@link JdbcApplier}.
 */
public class ChangeEventApplier implements Applier<Collection<SourceRecord>> {

    private final Applier<Collection<ChangeEvent>> applier;
    private final SourceRecordConverter converter;

    public ChangeEventApplier(Applier<Collection<ChangeEvent>> internalApplier, SinkConfig config) {
        this.applier = internalApplier;
        this.converter = new SourceRecordConverter(
                config.getTableNamingStrategy(),
                config.getColumnNamingStrategy(),
                config.getApplierDDLEnabled());
    }

    @Override
    public void prepare(TaskContext taskContext) {
        this.applier.prepare(taskContext);
    }

    @Override
    public void apply(Collection<SourceRecord> records) throws ApplierException {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<ChangeEvent> events = converter.convert(records);
        if (events.isEmpty()) {
            return;
        }
        applier.apply(events);
    }

    @Override
    public void release() {
        applier.release();
    }
}
