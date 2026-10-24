package io.opendrs.engine.replay.util;

import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.replay.annotation.ThreadSafe;
import io.opendrs.engine.replay.event.ApplierEvent;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.relation.FieldsMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Write set is used by parallel transactional applier.
 * We use it to adjust the last committed sequence number
 * for those transactions which can be applied parallel.
 *
 */
@ThreadSafe
public class WriteSet {
    private static long MAX_HISTORY_WRITE_SET_SIZE = 1000;

    private static final String HASH_DELIMITER = "\0%\0";

    private Map<Long, Long> historyWriteSet = new HashMap<>();

    private final HashUtil hashUtil;

    private long historyWriteSetStart;

    public WriteSet() {
        hashUtil = new HashUtil();
    }

    /**
     * Add one applierEvent into writeSet and adjust its last committed sequence number if possible
     *
     * @param event {@link ApplierEvent}
     */
    public synchronized void add(ApplierEvent event) {
        boolean exceedCapacity = false;
        long sequenceNumber = event.getSequenceNumber();
        long lastCommitted = event.getLastCommitted();
        long lastParent = historyWriteSetStart;
        boolean canUseWriteSet = event.canUseWriteSet();
        if (canUseWriteSet) {
            List<Long> writeSet = getWriteSet(event);
            if (writeSet.size() + historyWriteSet.size() > MAX_HISTORY_WRITE_SET_SIZE) {
                exceedCapacity = true;
            }
            for (Long key : writeSet) {
                Long lastVersion = historyWriteSet.get(key);
                if (lastVersion == null) {
                    if (!exceedCapacity) {
                        historyWriteSet.put(key, sequenceNumber);
                    }
                } else {
                    if (lastVersion > lastParent && lastVersion < sequenceNumber) {
                        lastParent = lastVersion;
                    }
                    historyWriteSet.put(key, sequenceNumber);
                }
            }
            lastCommitted = Math.min(lastParent, lastCommitted);
            event.setLastCommitted(lastCommitted);
        }
        if (exceedCapacity || !canUseWriteSet) {
            historyWriteSetStart = sequenceNumber;
            historyWriteSet.clear();
        }
    }

    private List<Long> getWriteSet(ApplierEvent applierEvent) {
        List<Long> writeSet = new ArrayList<>(applierEvent.getEvents().size());
        for (ChangeEvent event : applierEvent.getEvents()) {
            if (!(event instanceof DataChangeEvent)) {
                continue;
            }
            final DataChangeEvent dataChangeEvent = (DataChangeEvent) event;
            final TableId tableId = dataChangeEvent.getTableId();
            final FieldsMetaData fieldsMetaData = dataChangeEvent.getFieldsMetaData();
            final List<String> primaryKeyFields = fieldsMetaData.getPrimaryKeyFieldNames();
            final List<String> row = new ArrayList<>(fieldsMetaData.getFieldSchemas().size() + 5);
            if (tableId.getCatalog() != null) {
                row.add(tableId.getCatalog());
            }
            if (tableId.getSchema() != null) {
                row.add(tableId.getSchema());
            }
            if (tableId.getTable() != null) {
                row.add(tableId.getTable());
            }
            Map<String, Object> values = dataChangeEvent.getOperation() == Operation.DELETE ?
                dataChangeEvent.getBeforeValues() : dataChangeEvent.getAfterValues();
            for (String fieldName : primaryKeyFields) {
                Object object = values.get(fieldName);
                String str = StringUtil.toString(object, fieldsMetaData.getFieldSchema(fieldName));
                if (str == null) {
                    continue;
                }
                row.add(str);
            }
            writeSet.add(hashUtil.getHash(String.join(HASH_DELIMITER, row)));
        }
        return writeSet;
    }
}
