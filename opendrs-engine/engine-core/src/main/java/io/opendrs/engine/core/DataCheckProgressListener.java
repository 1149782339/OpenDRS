package io.opendrs.engine.core;

import io.opendrs.engine.common.metadata.TableId;

/** Reports how many source rows a full data check has read so far. */
@FunctionalInterface
public interface DataCheckProgressListener {

    DataCheckProgressListener NONE = (table, rowsChecked) -> { };

    void onRowsChecked(TableId table, long rowsChecked);
}
