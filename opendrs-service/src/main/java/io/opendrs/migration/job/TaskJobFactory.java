package io.opendrs.migration.job;

import io.opendrs.debezium.CdcEngineFactory;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.DebeziumOffsetMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.service.TableSelectionExpander;
import org.springframework.stereotype.Component;

@Component
public class TaskJobFactory {

    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final DebeziumOffsetMapper offsetMapper;
    private final TaskJobRegistry registry;
    private final TableSelectionExpander tableExpander;
    private final CdcEngineFactory engineFactory;

    public TaskJobFactory(
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            DebeziumOffsetMapper offsetMapper,
            TaskJobRegistry registry,
            TableSelectionExpander tableExpander,
            CdcEngineFactory engineFactory) {
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.offsetMapper = offsetMapper;
        this.registry = registry;
        this.tableExpander = tableExpander;
        this.engineFactory = engineFactory;
    }

    public TaskJob create(io.opendrs.migration.domain.MigrationTask task) {
        return new TaskJob(
                task.getId(),
                task.getMode(),
                taskMapper,
                connectionMapper,
                offsetMapper,
                registry,
                tableExpander,
                engineFactory);
    }
}
