package io.opendrs.migration.service;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.api.response.MigrationPrecheckResponse;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.domain.TaskState;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.precheck.CheckResult;
import io.opendrs.precheck.DbPreCheck;
import io.opendrs.precheck.DbPreChecks;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MigrationPrecheckService {

    private static final int ERROR_MESSAGE_MAX = 1024;

    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final MappingValidator mappingValidator;
    private final TableSelectionExpander tableExpander;
    private final DbPreChecks dbPreChecks;
    private final TransactionTemplate transactionTemplate;

    public MigrationPrecheckService(
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MappingValidator mappingValidator,
            TableSelectionExpander tableExpander,
            DbPreChecks dbPreChecks,
            TransactionTemplate transactionTemplate) {
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.mappingValidator = mappingValidator;
        this.tableExpander = tableExpander;
        this.dbPreChecks = dbPreChecks;
        this.transactionTemplate = transactionTemplate;
    }

    public MigrationPrecheckResponse precheck(Long id) {
        transactionTemplate.executeWithoutResult(status -> beginPrecheck(id));
        List<CheckResult> results;
        try {
            results = runChecks(requireTask(id));
        } catch (RuntimeException ex) {
            taskMapper.markFailed(id, truncate(ex.getMessage()));
            throw ex;
        }
        boolean ok = results.stream().allMatch(CheckResult::ok);
        if (ok) {
            int updated = taskMapper.compareAndSetState(id, TaskState.PRECHECKING, TaskState.PRECHECKED);
            if (updated == 0) {
                MigrationTask current = requireTask(id);
                throw AppException.of(
                        ErrorCode.TASK_CONFLICT,
                        "Task " + id + " cannot complete precheck from state " + current.getState());
            }
            return new MigrationPrecheckResponse(true, TaskState.PRECHECKED, results);
        }
        taskMapper.markFailed(id, truncate(summarizeFailures(results)));
        return new MigrationPrecheckResponse(false, TaskState.FAILED, results);
    }

    private void beginPrecheck(Long id) {
        MigrationTask task = requireTask(id);
        if (!task.getState().canPrecheck()) {
            throw AppException.of(
                    ErrorCode.TASK_CONFLICT,
                    "Task " + id + " cannot be prechecked from state " + task.getState());
        }
        ConnectionInfo source = requireConnection(task.getSourceConnectionId());
        ConnectionInfo target = requireConnection(task.getTargetConnectionId());
        requirePreCheck(source.getType(), "source");
        requirePreCheck(target.getType(), "target");
        int updated = taskMapper.compareAndSetState(id, task.getState(), TaskState.PRECHECKING);
        if (updated == 0) {
            throw AppException.of(
                    ErrorCode.TASK_CONFLICT,
                    "Task " + id + " cannot be prechecked from state " + task.getState());
        }
    }

    private List<CheckResult> runChecks(MigrationTask task) {
        ConnectionInfo source = requireConnection(task.getSourceConnectionId());
        ConnectionInfo target = requireConnection(task.getTargetConnectionId());
        DbPreCheck sourceCheck = requirePreCheck(source.getType(), "source");
        DbPreCheck targetCheck = requirePreCheck(target.getType(), "target");
        TableSelection selection = task.getTablesJson();

        List<CheckResult> results = new ArrayList<>();
        List<Table> sourceTables;
        try {
            sourceTables = tableExpander.expand(source, selection);
        } catch (AppException ex) {
            results.add(CheckResult.fail("connect", ex.getMessage()));
            sourceTables = tableExpander.expandExplicit(selection);
        }

        if (results.stream().noneMatch(result -> "connect".equals(result.name()) && !result.ok())) {
            results.addAll(sourceCheck.precheckSource(source, sourceTables));
        }

        List<Table> targetTables = mappingValidator.mapTargets(selection, sourceTables);
        results.addAll(targetCheck.precheckTarget(target, targetTables));
        return results;
    }

    private DbPreCheck requirePreCheck(DbType type, String role) {
        return dbPreChecks.of(type).orElseThrow(() -> AppException.of(
                ErrorCode.PARAM_INVALID,
                "No precheck registered for " + role + " type: " + type));
    }

    private static String summarizeFailures(List<CheckResult> results) {
        String joined = results.stream()
                .filter(result -> !result.ok())
                .map(result -> result.name() + ": " + result.message())
                .collect(Collectors.joining("; "));
        return joined.isEmpty() ? "Precheck failed" : "Precheck failed: " + joined;
    }

    private static String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Precheck failed";
        }
        if (message.length() <= ERROR_MESSAGE_MAX) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX);
    }

    private MigrationTask requireTask(Long id) {
        MigrationTask task = taskMapper.findById(id);
        if (task == null) {
            throw AppException.of(ErrorCode.TASK_NOT_FOUND, "Task not found: " + id);
        }
        return task;
    }

    private ConnectionInfo requireConnection(Long id) {
        ConnectionInfo connection = connectionMapper.findById(id);
        if (connection == null) {
            throw AppException.of(ErrorCode.INTERNAL_ERROR, "Connection not found: " + id);
        }
        return connection;
    }
}
