package io.opendrs.migration.service;

import io.opendrs.common.progress.FullLoadCheckpointListener;
import io.opendrs.migration.mapper.FullLoadCheckpointMapper;
import org.springframework.stereotype.Component;

/**
 * Metadata-DB persistence behind {@link FullLoadCheckpointListener}. Called on the Debezium
 * engine thread inside the consumer's batch handling, so it does nothing but the mapper call and
 * returns — the engine owns retry/fail-fast around it.
 *
 * <p>Presence is the done mark: one row per finished {@code (pipeline, table)} in
 * {@code task_snapshot_complete_tables}; a re-export overwrites the row count. There is nothing
 * else to persist — the resume planner derives the remaining tables from the task's current
 * selection, not from a snapshot-time list.
 */
@Component
public class JdbcFullLoadCheckpointListener implements FullLoadCheckpointListener {

    private final FullLoadCheckpointMapper mapper;

    public JdbcFullLoadCheckpointListener(FullLoadCheckpointMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void onTableFinished(long pipelineId, String table, long rowCount) {
        mapper.insertCompleted(pipelineId, table, rowCount);
    }
}
