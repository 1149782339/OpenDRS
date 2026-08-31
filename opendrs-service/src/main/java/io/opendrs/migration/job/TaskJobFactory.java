package io.opendrs.migration.job;

import io.opendrs.debezium.CdcEngineFactory;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.service.TableSelectionExpander;
import org.springframework.stereotype.Component;

@Component
public class TaskJobFactory {

    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final TaskJobRegistry registry;
    private final TableSelectionExpander tableExpander;
    private final CdcEngineFactory engineFactory;

    public TaskJobFactory(
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            TaskJobRegistry registry,
            TableSelectionExpander tableExpander,
            CdcEngineFactory engineFactory) {
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
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
                registry,
                tableExpander,
                engineFactory);
    }
}
