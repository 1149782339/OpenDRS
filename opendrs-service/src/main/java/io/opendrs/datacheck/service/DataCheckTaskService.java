package io.opendrs.datacheck.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.common.error.ExceptionFactory;
import io.opendrs.common.i18n.ErrorMessage;
import io.opendrs.datacheck.api.request.CreateDataCheckTaskRequest;
import io.opendrs.datacheck.api.request.DataCheckOptions;
import io.opendrs.datacheck.api.request.SelectedTable;
import io.opendrs.datacheck.api.response.CandidateSchema;
import io.opendrs.datacheck.api.response.CheckedTable;
import io.opendrs.datacheck.api.response.DataCheckResultItem;
import io.opendrs.datacheck.api.response.DataCheckResultPage;
import io.opendrs.datacheck.api.response.DataCheckTableResult;
import io.opendrs.datacheck.api.response.DataCheckTaskResponse;
import io.opendrs.datacheck.job.DataCheckCoordinator;
import io.opendrs.datacheck.job.DataCheckCoordinatorFactory;
import io.opendrs.datacheck.job.DataCheckCoordinatorRegistry;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTableRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTaskRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.EndpointRow;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.common.selection.SchemaMapping;
import io.opendrs.engine.common.selection.TableMapping;
import io.opendrs.engine.common.selection.TableMappings;
import io.opendrs.engine.common.selection.TableSelection;
import io.opendrs.engine.core.TableSelectionExpander;
import io.opendrs.engine.core.TaskState;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import io.opendrs.datacheck.domain.DataCheckPhase;
import io.opendrs.datacheck.domain.DataCheckTableStatus;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.datacheck.PrimaryKeySupport;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.replay.mapping.MappingValidator;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creation resolves target tables once; runtime only reads the persisted pairs. */
@Service
public class DataCheckTaskService {

    private static final int MAX_PAGE_SIZE = 500;

    private final DataCheckTaskRepository repository;
    private final MappingValidator mappingValidator;
    private final ObjectMapper objectMapper;
    private final DataCheckCoordinatorFactory coordinatorFactory;
    private final DataCheckCoordinatorRegistry registry;
    private final ExecutorService coordinatorExecutor;
    private final TableSelectionExpander expander;
    private final JdbcConnectionFactory connectionFactory;

    public DataCheckTaskService(
            DataCheckTaskRepository repository,
            MappingValidator mappingValidator,
            ObjectMapper objectMapper,
            DataCheckCoordinatorFactory coordinatorFactory,
            DataCheckCoordinatorRegistry registry,
            ExecutorService dataCheckCoordinatorExecutor,
            JdbcConnectionFactory connectionFactory) {
        this.repository = repository;
        this.mappingValidator = mappingValidator;
        this.objectMapper = objectMapper;
        this.coordinatorFactory = coordinatorFactory;
        this.registry = registry;
        this.coordinatorExecutor = dataCheckCoordinatorExecutor;
        this.expander = new TableSelectionExpander(connectionFactory);
        this.connectionFactory = connectionFactory;
    }

    @Transactional
    public DataCheckTaskResponse create(CreateDataCheckTaskRequest request) {
        DataCheckTaskRepository.MigrationTaskRow migrationTask =
                repository.findMigrationTask(request.migrationTaskId());
        if (migrationTask == null) {
            throw ExceptionFactory.create(ErrorCode.TASK_NOT_FOUND, ErrorMessage.TASK_NOT_FOUND);
        }

        MigrationOptionsView view = optionsView(migrationTask.optionsJson());
        validateMigrationReady(migrationTask);
        validateInScope(view, request.tables());
        validatePrimaryKeys(migrationTask, request.tables());
        TableSelection selection = selectionOf(view);
        List<CheckedTable> pairs = new ArrayList<>(request.tables().size());
        for (SelectedTable source : request.tables()) {
            TableId target = mappingValidator.resolveTableId(
                    selection, source.schema(), source.table());
            pairs.add(new CheckedTable(
                    source, new SelectedTable(target.getSchema(), target.getTable())));
        }

        long id;
        try {
            id = repository.insertTask(
                    UUID.randomUUID().toString(),
                    request.migrationTaskId(),
                    request.pipelineId(),
                    request.name(),
                    request.mode(),
                    writeJson(request.options()));
        } catch (DuplicateKeyException ex) {
            throw ExceptionFactory.create(ErrorCode.TASK_CONFLICT, ErrorMessage.TASK_NAME_EXISTS);
        }
        for (CheckedTable pair : pairs) {
            repository.insertTableTask(id, identifier(pair.source()), identifier(pair.target()));
        }
        return get(id);
    }

    public List<DataCheckTaskResponse> list(Long migrationTaskId) {
        return repository.listTasks(migrationTaskId).stream().map(this::toResponse).toList();
    }

    /**
     * Source tables a check may cover: the migration task's selection expanded with the same
     * {@link TableSelectionExpander} the engine uses at run time.
     */
    public List<CandidateSchema> candidates(long migrationTaskId) {
        DataCheckTaskRepository.MigrationTaskRow task = repository.findMigrationTask(migrationTaskId);
        if (task == null) {
            throw ExceptionFactory.create(ErrorCode.TASK_NOT_FOUND, ErrorMessage.TASK_NOT_FOUND);
        }
        TableSelection selection = selectionOf(optionsView(task.optionsJson()));
        EndpointRow source = repository.findEndpoints(List.of(task.sourceEndpointId())).stream()
                .filter(endpoint -> endpoint.id() == task.sourceEndpointId())
                .findFirst()
                .orElse(null);
        if (source == null) {
            throw ExceptionFactory.create(
                    ErrorCode.INTERNAL_ERROR, ErrorMessage.TASK_SOURCE_NOT_FOUND,
                    task.sourceEndpointId());
        }
        try {
            List<TableId> tables = expander.expand(
                    EndpointConfigs.toConfig(source, objectMapper), selection);
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (TableId table : tables) {
                String schema = table.getSchema() == null ? "" : table.getSchema();
                grouped.computeIfAbsent(schema, key -> new ArrayList<>()).add(table.getTable());
            }
            return grouped.entrySet().stream()
                    .map(entry -> new CandidateSchema(entry.getKey(), List.copyOf(entry.getValue())))
                    .toList();
        } catch (SQLException | RuntimeException ex) {
            throw ExceptionFactory.create(
                    ErrorCode.METADATA_READ_FAILED,
                    ErrorMessage.DATACHECK_CANDIDATES_FAILED, ex.getMessage());
        }
    }

    public DataCheckTaskResponse get(long id) {
        return toResponse(require(id));
    }

    public void delete(long id) {
        DataCheckTaskRow task = require(id);
        TaskState state = task.state();
        if (state == TaskState.STARTING
                || state == TaskState.RUNNING
                || state == TaskState.STOPPING
                || state == TaskState.TERMINATING) {
            throw ExceptionFactory.create(
                    ErrorCode.TASK_CONFLICT, ErrorMessage.TASK_MUST_TERMINATE_BEFORE_DELETE, state);
        }
        repository.deleteTask(id);
    }

    public void start(long id) {
        DataCheckTaskRow task = require(id);
        TaskState state = task.state();
        // First start (state is null) or resume after a clean stop / a failed incremental run.
        if (state != null && state != TaskState.STOPPED && state != TaskState.FAILED) {
            throw ExceptionFactory.create(
                    ErrorCode.TASK_CONFLICT, ErrorMessage.TASK_CANNOT_START_FROM, id, state);
        }
        if (repository.casState(id, state, TaskState.STARTING) == 0) {
            throw ExceptionFactory.create(
                    ErrorCode.TASK_CONFLICT, ErrorMessage.TASK_CANNOT_START_FROM, id, "STARTING");
        }
        DataCheckCoordinator coordinator = coordinatorFactory.create(id);
        if (!registry.tryRegister(coordinator)) {
            repository.markFailed(id, "another check is already running for this task");
            throw ExceptionFactory.create(
                    ErrorCode.TASK_CONFLICT, ErrorMessage.TASK_CANNOT_START_FROM, id, "RUNNING");
        }
        coordinatorExecutor.execute(coordinator);
    }

    public void stop(long id) {
        DataCheckTaskRow task = require(id);
        if (task.state() != TaskState.RUNNING && task.state() != TaskState.STARTING) {
            throw ExceptionFactory.create(
                    ErrorCode.TASK_CONFLICT, ErrorMessage.TASK_CANNOT_PAUSE_FROM, task.state());
        }
        if (isFullCheckRunning(id)) {
            throw ExceptionFactory.create(
                    ErrorCode.TASK_CONFLICT, ErrorMessage.DATACHECK_CANNOT_STOP_FULL);
        }
        registry.requestStop(id);
        if (repository.casState(id, TaskState.RUNNING, TaskState.STOPPING) == 0) {
            repository.casState(id, TaskState.STARTING, TaskState.STOPPING);
        }
    }

    public void terminate(long id) {
        DataCheckTaskRow task = require(id);
        if (task.state() != TaskState.RUNNING && task.state() != TaskState.STARTING) {
            throw ExceptionFactory.create(
                    ErrorCode.TASK_CONFLICT, ErrorMessage.TASK_CANNOT_TERMINATE_FROM, task.state());
        }
        registry.requestStop(id);
        if (repository.casState(id, TaskState.RUNNING, TaskState.TERMINATING) == 0) {
            repository.casState(id, TaskState.STARTING, TaskState.TERMINATING);
        }
    }

    public List<DataCheckTableResult> tables(long id) {
        require(id);
        Map<String, long[]> counts = repository.countsByTable(id);
        return repository.findTableRows(id).stream().map(row -> {
            long[] perFlag = counts.getOrDefault(row.sourceTableId(), new long[3]);
            return new DataCheckTableResult(
                    row.sourceTableId(),
                    row.targetTableId(),
                    row.phase(),
                    row.status(),
                    row.lagMs() == null ? 0 : row.lagMs(),
                    row.errorMessage(),
                    perFlag[0], perFlag[1], perFlag[2],
                    perFlag[0] + perFlag[1] + perFlag[2],
                    row.rowsChecked(),
                    row.lastCheckAt(),
                    row.waitingForReplay());
        }).toList();
    }

    public DataCheckResultPage results(long id, String tableId, Integer flag, int page, int size) {
        require(id);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        long total = repository.countResults(id, tableId, flag);
        List<DataCheckResultItem> items =
                repository.pageResults(id, tableId, flag, safePage, safeSize);
        return new DataCheckResultPage(items, total, safePage, safeSize);
    }

    private DataCheckTaskResponse toResponse(DataCheckTaskRow row) {
        List<CheckedTable> pairs = repository.findTableRows(row.id()).stream()
                .map(table -> new CheckedTable(
                        selected(table.sourceTableId()), selected(table.targetTableId())))
                .toList();
        return new DataCheckTaskResponse(
                row.id(),
                row.publicId(),
                row.migrationTaskId(),
                row.pipelineId(),
                row.name(),
                row.mode(),
                row.state(),
                pairs,
                options(row.optionsJson()),
                row.lagMs(),
                row.errorMessage(),
                row.createdAt(),
                row.updatedAt(),
                isStoppable(row));
    }

    private DataCheckTaskRow require(long id) {
        DataCheckTaskRow row = repository.findTask(id);
        if (row == null) {
            throw ExceptionFactory.create(ErrorCode.TASK_NOT_FOUND, ErrorMessage.TASK_NOT_FOUND);
        }
        return row;
    }


    static void validateMigrationReady(DataCheckTaskRepository.MigrationTaskRow migrationTask) {
        MigrationMode mode;
        try {
            mode = migrationTask.mode() == null ? null : MigrationMode.valueOf(migrationTask.mode());
        } catch (IllegalArgumentException ex) {
            mode = null;
        }
        String phase = migrationTask.phase() == null ? "UNKNOWN" : migrationTask.phase();
        String state = migrationTask.state() == null ? "UNKNOWN" : migrationTask.state();
        boolean ready;
        if (mode == MigrationMode.FULL_ONLY) {
            ready = "COMPLETED".equals(state);
        } else {
            ready = "INCREMENTAL".equals(phase) || "COMPLETED".equals(state);
        }
        if (!ready) {
            throw ExceptionFactory.create(
                    ErrorCode.PARAM_INVALID,
                    ErrorMessage.DATACHECK_MIGRATION_NOT_READY,
                    phase, state);
        }
    }

    private void validatePrimaryKeys(
            DataCheckTaskRepository.MigrationTaskRow migrationTask, List<SelectedTable> tables) {
        EndpointRow source = repository.findEndpoints(List.of(migrationTask.sourceEndpointId()))
                .stream()
                .filter(endpoint -> endpoint.id() == migrationTask.sourceEndpointId())
                .findFirst()
                .orElse(null);
        if (source == null) {
            throw ExceptionFactory.create(
                    ErrorCode.INTERNAL_ERROR, ErrorMessage.TASK_SOURCE_NOT_FOUND,
                    String.valueOf(migrationTask.sourceEndpointId()));
        }
        JdbcConnectionConfig config = EndpointConfigs.toConfig(source, objectMapper);
        try (JdbcConnection connection = connectionFactory.open(config)) {
            for (SelectedTable table : tables) {
                TableId tableId = TableId.of(table.schema(), table.table());
                TableDefinition definition = connection.readTable(tableId);
                try {
                    PrimaryKeySupport.validate(tableId, definition);
                } catch (PrimaryKeySupport.UnsupportedPrimaryKeyException ex) {
                    throw ExceptionFactory.create(
                            ErrorCode.PARAM_INVALID,
                            ErrorMessage.DATACHECK_UNSUPPORTED_PK,
                            String.valueOf(ex.table()),
                            ex.column() == null ? "" : ex.column(),
                            ex.typeName() == null ? "" : ex.typeName());
                }
            }
        } catch (SQLException ex) {
            throw ExceptionFactory.create(
                    ErrorCode.INTERNAL_ERROR, ErrorMessage.DATACHECK_CANDIDATES_FAILED, ex.getMessage());
        }
    }

    static boolean isFullCheckRunning(List<DataCheckTableRow> tables) {
        if (tables == null) {
            return false;
        }
        for (DataCheckTableRow table : tables) {
            if (table.phase() == DataCheckPhase.FULL
                    && table.status() == DataCheckTableStatus.RUNNING) {
                return true;
            }
        }
        return false;
    }

    private boolean isFullCheckRunning(long taskId) {
        return isFullCheckRunning(repository.findTableRows(taskId));
    }

    static boolean isStoppable(TaskState state, List<DataCheckTableRow> tables) {
        if (state != TaskState.RUNNING && state != TaskState.STARTING) {
            return false;
        }
        return !isFullCheckRunning(tables);
    }

    private boolean isStoppable(DataCheckTaskRow row) {
        return isStoppable(row.state(), repository.findTableRows(row.id()));
    }

    /** Resolves the migration task's persisted selection/mappings into the engine form. */
    private MigrationOptionsView optionsView(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(optionsJson, MigrationOptionsView.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot read migration task options", ex);
        }
    }

    private TableSelection selectionOf(MigrationOptionsView view) {
        SelectionView selection = view == null ? null : view.selection();
        if (selection == null) {
            return TableSelection.all();
        }
        return new TableSelection(
                selection.schemas(),
                tableIds(selection.tables()),
                selection.excludeSchemas(),
                tableIds(selection.excludeTables()),
                tableMappings(selection.mappings()));
    }

    /** Rejects tables that the parent migration task does not cover. */
    private static void validateInScope(MigrationOptionsView view, List<SelectedTable> requested) {
        SelectionView selection = view == null ? null : view.selection();
        for (SelectedTable table : requested) {
            if (!inScope(selection, table.schema(), table.table())) {
                throw ExceptionFactory.create(
                        ErrorCode.PARAM_INVALID,
                        ErrorMessage.DATACHECK_TABLE_OUT_OF_SCOPE,
                        table.schema() + "." + table.table());
            }
        }
    }

    private static boolean inScope(SelectionView selection, String schema, String table) {
        if (selection == null) {
            return true;
        }
        if (contains(selection.excludeSchemas(), schema)) {
            return false;
        }
        if (containsTable(selection.excludeTables(), schema, table)) {
            return false;
        }
        if (selection.mode() == null) {
            return true;
        }
        return switch (selection.mode()) {
            case "TABLE" -> containsTable(selection.tables(), schema, table);
            case "SCHEMA" -> contains(selection.schemas(), schema);
            case "ALL" -> true;
            default -> false;
        };
    }

    private static boolean contains(List<String> values, String wanted) {
        return values != null && values.contains(wanted);
    }

    private static boolean containsTable(
            List<SelectedTableView> tables, String schema, String table) {
        return tables != null && tables.stream().anyMatch(candidate ->
                Objects.equals(candidate.schema(), schema) && Objects.equals(candidate.table(), table));
    }

    private DataCheckOptions options(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(optionsJson, DataCheckOptions.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String writeJson(DataCheckOptions options) {
        if (options == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot write check options", ex);
        }
    }

    private static List<TableId> tableIds(List<SelectedTableView> tables) {
        if (tables == null) {
            return null;
        }
        return tables.stream()
                .filter(java.util.Objects::nonNull)
                .map(table -> TableId.of(table.schema(), table.table()))
                .toList();
    }

    private static TableMappings tableMappings(TableMappingsView view) {
        if (view == null) {
            return null;
        }
        List<SchemaMapping> schemas = view.schema() == null
                ? null
                : view.schema().stream()
                        .map(mapping -> new SchemaMapping(mapping.source(), mapping.target()))
                        .toList();
        List<TableMapping> tables = view.tables() == null
                ? null
                : view.tables().stream()
                        .map(mapping -> new TableMapping(
                                mapping.sourceSchema(),
                                mapping.sourceTable(),
                                mapping.targetSchema(),
                                mapping.targetTable()))
                        .toList();
        return new TableMappings(schemas, tables);
    }

    private static String identifier(SelectedTable table) {
        return TableId.of(table.schema(), table.table()).identifier();
    }

    private static SelectedTable selected(String identifier) {
        int dot = identifier.indexOf('.');
        if (dot < 0) {
            return new SelectedTable(null, identifier);
        }
        return new SelectedTable(identifier.substring(0, dot), identifier.substring(dot + 1));
    }

    // ------------------------------------------------------------------
    // Local view of the migration task options JSON (only selection matters here)
    // ------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MigrationOptionsView(SelectionView selection) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SelectionView(
            String mode,
            List<String> schemas,
            List<SelectedTableView> tables,
            List<String> excludeSchemas,
            List<SelectedTableView> excludeTables,
            TableMappingsView mappings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SelectedTableView(String schema, String table) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TableMappingsView(List<SchemaMappingView> schema, List<TableMappingView> tables) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SchemaMappingView(String source, String target) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TableMappingView(
            String sourceSchema, String sourceTable, String targetSchema, String targetTable) {
    }
}
