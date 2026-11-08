package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.metadata.TableId;
import java.util.List;

/**
 * Persists which rows of one data check differ. Only inconsistent rows are stored; a row that is
 * consistent again is removed, so presence means "this row differs".
 *
 * <p>Composite primary keys are encoded by joining the column values with {@code \u0000}.
 */
public interface InconsistentRowStore {

    /** Both sides have the row, but the serialized values differ. */
    int FLAG_DIFFERENT = 0;
    /** The source has the row, the target does not. */
    int FLAG_SOURCE_ONLY = 1;
    /** The target has the row, the source does not. */
    int FLAG_TARGET_ONLY = 2;

    /** Upserts the given primary keys with the given flag. */
    void markInconsistent(TableId table, List<String> pks, int flag);

    /** Removes the given primary keys; they are consistent again. */
    void markConsistent(TableId table, List<String> pks);

    /** Primary keys currently marked as inconsistent for the table. */
    List<String> listInconsistent(TableId table);

    boolean isInconsistent(TableId table, String pk);

    /** Encodes the values of one composite primary key into the single {@code pk} string. */
    static String encode(List<String> keyValues) {
        return String.join("\u0000", keyValues);
    }
}
