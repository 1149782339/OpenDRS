package io.opendrs.migration.service;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.api.response.MigrationPrecheckResponse;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.precheck.CheckResult;
import io.opendrs.precheck.DbPreCheck;
import io.opendrs.precheck.DbPreChecks;
import io.opendrs.precheck.PrecheckResults;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MigrationPrecheckService {

    private static final Logger log = LoggerFactory.getLogger(MigrationPrecheckService.class);
    private static final int ERROR_MESSAGE_MAX = 1024;

    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final MappingValidator mappingValidator;
    private final TableSelectionExpander tableExpander;
    private final DbPreChecks dbPreChecks;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolTaskExecutor taskJobExecutor;

    public MigrationPrecheckService(
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MappingValidator mappingValidator,
            TableSelectionExpander tableExpander,
            DbPreChecks dbPreChecks,
            TransactionTemplate transactionTemplate,
            ThreadPoolTaskExecutor taskJobExecutor) {
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.mappingValidator = mappingValidator;
        this.tableExpander = tableExpander;
        this.dbPreChecks = dbPreChecks;
        this.transactionTemplate = transactionTemplate;
        this.taskJobExecutor = taskJobExecutor;
    }

    public MigrationPrecheckResponse startPrecheck(Long id) {
        boolean submitted = Boolean.TRUE.equals(transactionTemplate.execute(status -> beginPrecheck(id)));
        if (submitted) {
            try {
                taskJobExecutor.execute(() -> runPrecheckJob(id));
            } catch (RuntimeException ex) {
                taskMapper.markPrecheckFailed(id, truncate(ex.getMessage()));
                throw ex;
            }
        }
        return getPrecheck(id);
    }

    public MigrationPrecheckResponse getPrecheck(Long id) {
        return toResponse(requireTask(id));
    }

    /**
     * @return true when a new background run was CAS-started
     */
    private boolean beginPrecheck(Long id) {
        MigrationTask task = requireTask(id);
        if (isAlreadyPrechecking(task)) {
            return false;
        }
        if (!canPrecheck(task)) {
            throw AppException.of(
                    ErrorCode.TASK_CONFLICT,
                    "Task " + id + " cannot be prechecked from phase " + task.getJobPhase()
                            + " jobState " + task.getJobState());
        }
        ConnectionInfo source = requireConnection(task.getSourceConnectionId());
        ConnectionInfo target = requireConnection(task.getTargetConnectionId());
        requirePreCheck(source.getType(), "source");
        requirePreCheck(target.getType(), "target");
        int updated = taskMapper.beginPrecheck(id, task.getJobPhase(), task.getJobState());
        if (updated == 0) {
            MigrationTask latest = requireTask(id);
            if (isAlreadyPrechecking(latest)) {
                return false;
            }
            throw AppException.of(
                    ErrorCode.TASK_CONFLICT,
                    "Task " + id + " cannot be prechecked from phase " + latest.getJobPhase()
                            + " jobState " + latest.getJobState());
        }
        taskMapper.updatePrecheckResults(id, PrecheckResults.empty());
        return true;
    }

    static boolean canPrecheck(MigrationTask task) {
        if (task.getJobState() == JobState.STARTING
                || task.getJobState() == JobState.RUNNING
                || task.getJobState() == JobState.STOPPING) {
            return false;
        }
        JobPhase phase = task.getJobPhase();
        if (phase == JobPhase.CREATED) {
            return task.getJobState() == null;
        }
        if (phase == JobPhase.PRECHECKING) {
            return task.getJobState() == JobState.FAILED;
        }
        if (phase == JobPhase.PRECHECKED) {
            return task.getJobState() == null
                    || task.getJobState() == JobState.STOPPED
                    || task.getJobState() == JobState.FAILED;
        }
        return false;
    }

    static boolean isAlreadyPrechecking(MigrationTask task) {
        return task.getJobPhase() == JobPhase.PRECHECKING && task.getJobState() == null;
    }

    private void runPrecheckJob(Long id) {
        try {
            runChecks(id);
        } catch (RuntimeException ex) {
            log.warn("Precheck job failed for task {}", id, ex);
            taskMapper.markPrecheckFailed(id, truncate(ex.getMessage()));
        }
    }

    private void runChecks(Long id) {
        MigrationTask task = requireTask(id);
        ConnectionInfo source = requireConnection(task.getSourceConnectionId());
        ConnectionInfo target = requireConnection(task.getTargetConnectionId());
        DbPreCheck sourceCheck = requirePreCheck(source.getType(), "source");
        DbPreCheck targetCheck = requirePreCheck(target.getType(), "target");
        TableSelection selection = task.getTablesJson();

        PrecheckResults accumulated = PrecheckResults.empty();
        persistResults(id, accumulated);

        List<CheckResult> sourceResults = new ArrayList<>();
        List<Table> sourceTables;
        try {
            sourceTables = tableExpander.expand(source, selection);
        } catch (AppException ex) {
            sourceResults.add(CheckResult.fail("connect", ex.getMessage()));
            sourceTables = tableExpander.expandExplicit(selection);
        }

        if (sourceResults.stream().noneMatch(result -> "connect".equals(result.name()) && !result.ok())) {
            sourceResults.addAll(sourceCheck.precheckSource(source, sourceTables));
        }
        accumulated = accumulated.withSource(sourceResults);
        persistResults(id, accumulated);

        List<Table> targetTables = mappingValidator.mapTargets(selection, sourceTables);
        List<CheckResult> targetResults = new ArrayList<>(targetCheck.precheckTarget(target, targetTables));
        accumulated = accumulated.withTarget(targetResults);
        persistResults(id, accumulated);

        boolean ok = accumulated.all().stream().allMatch(CheckResult::ok);
        if (ok) {
            int updated = taskMapper.completePrecheckSuccess(id);
            if (updated == 0) {
                MigrationTask current = requireTask(id);
                throw AppException.of(
                        ErrorCode.TASK_CONFLICT,
                        "Task " + id + " cannot complete precheck from phase " + current.getJobPhase()
                                + " jobState " + current.getJobState());
            }
            return;
        }
        taskMapper.markPrecheckFailed(id, truncate(summarizeFailures(accumulated.all())));
    }

    private void persistResults(Long id, PrecheckResults results) {
        taskMapper.updatePrecheckResults(id, results);
    }

    private MigrationPrecheckResponse toResponse(MigrationTask task) {
        PrecheckResults stored = task.getPrecheckResultsJson() == null
                ? PrecheckResults.empty()
                : task.getPrecheckResultsJson();
        boolean ok = task.getJobPhase() == JobPhase.PRECHECKED
                && stored.all().stream().allMatch(CheckResult::ok);
        return MigrationPrecheckResponse.of(ok, task.getJobPhase(), task.getJobState(), stored);
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
