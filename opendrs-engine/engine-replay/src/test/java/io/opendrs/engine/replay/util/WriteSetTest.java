package io.opendrs.engine.replay.util;

import static io.opendrs.engine.replay.TestEvents.row;
import static io.opendrs.engine.replay.TestEvents.txMark;
import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.replay.event.ApplierEvent;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.event.TransactionEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The write set's contract: adjust a transaction's {@code lastCommitted} backwards to its last
 * conflicting predecessor so the dispatcher may run non-conflicting transactions in parallel,
 * while keeping full order for DDL / no-primary-key transactions.
 */
class WriteSetTest {

    private WriteSet writeSet = new WriteSet();

    @Test
    void nonConflictingTransactionKeepsOrderUnconstrained() {
        ApplierEvent first = tx(1, 0, true, row(Operation.CREATE, "s", "a", 1, "t1"));
        ApplierEvent second = tx(2, 1, true, row(Operation.CREATE, "s", "b", 2, "t2"));
        writeSet.add(first);
        writeSet.add(second);
        // No shared key: nothing to wait for, lastCommitted collapses to the history floor (0).
        assertThat(second.getLastCommitted()).isEqualTo(0);
    }

    @Test
    void conflictingTransactionWaitsForPredecessor() {
        ApplierEvent first = tx(1, 0, true, row(Operation.CREATE, "s", "a", 1, "t1"));
        ApplierEvent conflict = tx(2, 1, true, row(Operation.UPDATE, "s", "a", 1, "t2"));
        writeSet.add(first);
        writeSet.add(conflict);
        // Same (schema, table, pk): must dispatch only after tx#1 has committed.
        assertThat(conflict.getLastCommitted()).isEqualTo(1);
    }

    @Test
    void transactionWithoutWriteSetClearsHistoryAndStaysOrdered() {
        ApplierEvent first = tx(1, 0, true, row(Operation.CREATE, "s", "a", 1, "t1"));
        ApplierEvent ddl = tx(2, 1, false, txMark(null, TransactionEvent.Status.END));
        ApplierEvent afterDdl = tx(3, 2, true, row(Operation.UPDATE, "s", "a", 1, "t3"));
        writeSet.add(first);
        writeSet.add(ddl);
        writeSet.add(afterDdl);
        // DDL is a full barrier: the following transaction keeps its original lastCommitted
        // even though it touches the same key tx#1 wrote.
        assertThat(afterDdl.getLastCommitted()).isEqualTo(2);
    }

    @Test
    void deleteConflictsOnBeforeValues() {
        ApplierEvent insert = tx(1, 0, true, row(Operation.CREATE, "s", "a", 1, "t1"));
        ApplierEvent delete = tx(2, 1, true, row(Operation.DELETE, "s", "a", 1, "t2"));
        writeSet.add(insert);
        writeSet.add(delete);
        assertThat(delete.getLastCommitted()).isEqualTo(1);
    }

    private static ApplierEvent tx(long sequenceNumber, long lastCommitted, boolean canUseWriteSet,
            ChangeEvent... events) {
        boolean hasData = List.of(events).stream().anyMatch(e -> !(e instanceof TransactionEvent));
        return ApplierEvent.builder()
                .sequenceNumber(sequenceNumber)
                .lastCommitted(lastCommitted)
                .canUseWriteSet(canUseWriteSet)
                .hasDataChangeEvent(hasData)
                .records(List.of(events))
                .build();
    }
}
