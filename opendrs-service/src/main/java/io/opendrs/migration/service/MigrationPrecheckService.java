package io.opendrs.migration.service;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.core.PrecheckParam;
import io.opendrs.engine.core.PrecheckTask;
import io.opendrs.migration.api.response.MigrationPrecheckResponse;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.core.TaskState;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.engine.precheck.PrecheckItem;
import io.opendrs.engine.precheck.PrecheckResults;
import io.opendrs.engine.precheck.Severity;
import io.opendrs.engine.precheck.Verdict;
import io.opendrs.engine.replay.exception.MappingException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Async precheck orchestration: state machine (CAS phases), persistence and progress reporting
 * stay in the service; the actual checks run inside the engine behind {@link MigrationEngine}.
 */
@Service
public class MigrationPrecheckService {

    private static final Logger log = LoggerFactory.getLogger(MigrationPrecheckService.class);
    private static final int ERROR_MESSAGE_MAX = 1024;

    /** A failing checklist would otherwise write one line per row; the rest stay in the JSON. */
    private static final int MAX_LOGGED_FAILURES = 10;

    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final MigrationEngine engine;
    private final MigrationLogService logService;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolTaskExecutor coordinatorExecutor;

    public MigrationPrecheckService(
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MigrationEngine engine,
            MigrationLogService logService,
            TransactionTemplate transactionTemplate,
            ThreadPoolTaskExecutor coordinatorExecutor) {
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.engine = engine;
        this.logService = logService;
        this.transactionTemplate = transactionTemplate;
        this.coordinatorExecutor = coordinatorExecutor;
    }

    public MigrationPrecheckResponse startPrecheck(Long id) {
        boolean submitted = Boolean.TRUE.equals(transactionTemplate.execute(status -> beginPrecheck(id)));
        if (submitted) {
            try {
                coordinatorExecutor.execute(() -> runPrecheckJob(id));
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
                    "Task " + id + " cannot be prechecked from phase " + task.getPhase()
                            + " state " + task.getState());
        }
        ConnectionInfo source = requireConnection(task.getSourceConnectionId());
        ConnectionInfo target = requireConnection(task.getTargetConnectionId());
        requirePreCheckSupport(source.toDatabaseType(), target.toDatabaseType());
        int updated = taskMapper.beginPrecheck(id, task.getPhase(), task.getState());
        if (updated == 0) {
            MigrationTask latest = requireTask(id);
            if (isAlreadyPrechecking(latest)) {
                return false;
            }
            throw AppException.of(
                    ErrorCode.TASK_CONFLICT,
                    "Task " + id + " cannot be prechecked from phase " + latest.getPhase()
                            + " state " + latest.getState());
        }
        taskMapper.updatePrecheckResults(id, PrecheckResults.empty());
        logService.log(id, MigrationLogLevel.INFO, "precheck started");
        return true;
    }

    static boolean canPrecheck(MigrationTask task) {
        if (task.getState() == TaskState.STARTING
                || task.getState() == TaskState.RUNNING
                || task.getState() == TaskState.STOPPING) {
            return false;
        }
        MigrationPhase phase = task.getPhase();
        if (phase == MigrationPhase.CREATED) {
            return task.getState() == null;
        }
        if (phase == MigrationPhase.PRECHECKING) {
            return task.getState() == TaskState.FAILED;
        }
        if (phase == MigrationPhase.PRECHECKED) {
            return task.getState() == null
                    || task.getState() == TaskState.STOPPED
                    || task.getState() == TaskState.FAILED;
        }
        return false;
    }

    static boolean isAlreadyPrechecking(MigrationTask task) {
        return task.getPhase() == MigrationPhase.PRECHECKING && task.getState() == null;
    }

    private void runPrecheckJob(Long id) {
        try {
            runChecks(id);
        } catch (RuntimeException ex) {
            log.warn("Precheck run failed for task {}", id, ex);
            String message = truncate(ex.getMessage());
            taskMapper.markPrecheckFailed(id, message);
            logService.log(id, MigrationLogLevel.ERROR, "precheck failed: " + message);
        }
    }

    private void runChecks(Long id) {
        MigrationTask task = requireTask(id);
        ConnectionInfo source = requireConnection(task.getSourceConnectionId());
        ConnectionInfo target = requireConnection(task.getTargetConnectionId());

        PrecheckTask handle = engine.precheck(PrecheckParam.builder()
                .source(source.toJdbcConfig())
                .target(target.toJdbcConfig())
                .selection(MappingRequests.toNeutral(
                        task.getOptionsJson().selection().toTableSelection()))
                .bidirectional(task.getOptionsJson() != null
                        && task.getOptionsJson().topology() == MigrationOptions.Topology.BIDIRECTIONAL)
                .progressListener(results -> taskMapper.updatePrecheckResults(id, results))
                .build());
        try {
            handle.await();
        } catch (InterruptedException ex) {
            handle.requestStop();
            Thread.currentThread().interrupt();
            throw AppException.of(ErrorCode.INTERNAL_ERROR, "Precheck interrupted");
        }
        Throwable failure = handle.failure();
        if (failure != null) {
            throw translate(failure);
        }
        PrecheckResults accumulated = handle.results();

        if (accumulated == null) {
            throw AppException.of(ErrorCode.INTERNAL_ERROR, "Precheck produced no results");
        }
        // Name the failing checks first, then the verdict: a summary that says "2 failed" without
        // saying which two is not worth a log line.
        List<PrecheckItem> failed = failedItems(accumulated);
        failed.stream().limit(MAX_LOGGED_FAILURES).forEach(item -> logService.log(
                id,
                item.severity() == Severity.BLOCKER ? MigrationLogLevel.ERROR : MigrationLogLevel.WARNING,
                "precheck " + item.id() + ": " + describe(item)));
        if (failed.size() > MAX_LOGGED_FAILURES) {
            logService.log(id, MigrationLogLevel.INFO, (failed.size() - MAX_LOGGED_FAILURES)
                    + " more failing check(s) are in the checklist but not in this log");
        }

        if (accumulated.admitted()) {
            int updated = taskMapper.completePrecheckSuccess(id);
            if (updated == 0) {
                MigrationTask current = requireTask(id);
                throw AppException.of(
                        ErrorCode.TASK_CONFLICT,
                        "Task " + id + " cannot complete precheck from phase " + current.getPhase()
                                + " state " + current.getState());
            }
            logService.log(id, MigrationLogLevel.INFO, "precheck passed: "
                    + accumulated.items().size() + " item(s), " + failed.size()
                    + " failed but not blocking");
            return;
        }
        String summary = summarizeFailures(accumulated);
        taskMapper.markPrecheckFailed(id, truncate(summary));
        logService.log(id, MigrationLogLevel.WARNING, "precheck not admitted: "
                + accumulated.items().stream().filter(PrecheckItem::blocking).count() + " blocking item(s)");
    }

    /** Checks that came back with a negative verdict; warnings included, they still need reading. */
    private static List<PrecheckItem> failedItems(PrecheckResults results) {
        return results.items().stream()
                .filter(item -> item.verdict() == Verdict.FAIL)
                .toList();
    }

    private static String describe(PrecheckItem item) {
        if (item.message() == null || item.message().isBlank()) {
            return item.verdict().name();
        }
        return item.message();
    }

    /** A mapping problem is the user's input, not an internal error; keep the old translation. */
    private static RuntimeException translate(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MappingException mapping) {
                return AppException.of(ErrorCode.PARAM_INVALID, mapping.getMessage());
            }
            if (current.getCause() == current) {
                break;
            }
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(failure.getMessage(), failure);
    }

    private MigrationPrecheckResponse toResponse(MigrationTask task) {
        PrecheckResults stored = task.getPrecheckResultsJson() == null
                ? PrecheckResults.empty()
                : task.getPrecheckResultsJson();
        boolean ok = task.getPhase() == MigrationPhase.PRECHECKED && stored.admitted();
        return MigrationPrecheckResponse.of(ok, task.getPhase(), task.getState(), stored);
    }

    private void requirePreCheckSupport(DatabaseType source, DatabaseType target) {
        if (!engine.supportsPrecheck(source, target)) {
            throw AppException.of(
                    ErrorCode.PARAM_INVALID,
                    "No precheck registered for " + source + " → " + target);
        }
    }

    /** Names the blocking checks for the task's error column. */
    private static String summarizeFailures(PrecheckResults results) {
        String summary = results.summary();
        return summary.isEmpty() ? "Precheck failed" : "Precheck failed: " + summary;
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
