package io.opendrs.migration.service;

import io.opendrs.common.error.ErrorCode;
import io.opendrs.common.error.ExceptionFactory;
import io.opendrs.common.i18n.ErrorMessage;
import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.migration.api.response.MigrationLogItem;
import io.opendrs.migration.api.response.MigrationLogPage;
import io.opendrs.migration.domain.MigrationLog;
import io.opendrs.migration.mapper.MigrationLogMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the task log: a bounded buffer in front of {@code migration_log}.
 *
 * <p>Two producers write here. The service records what it knows itself (lifecycle, phase changes,
 * precheck verdicts) and the engine reports what only it can see through {@link #listenerFor}, which
 * is called on Debezium threads. Neither may block on the metadata database: a slow insert inside
 * the change consumer would stall binlog consumption, and back-pressure there can fill the source's
 * binlog disk. So {@link #log} only offers to a queue and returns; one daemon thread drains it in
 * batches.
 *
 * <p>The buffer is bounded and drops on overflow rather than blocking. Dropped lines are counted per
 * task and reported once, as a single warning row, the next time that task writes anything - losing
 * a log line is always preferable to slowing down the migration it describes.
 */
@Service
public class MigrationLogService {

    private static final Logger log = LoggerFactory.getLogger(MigrationLogService.class);

    /** Matches the column width; anything longer is cut here, the only place that decides. */
    private static final int MESSAGE_MAX = 2000;

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int BATCH_MAX = 200;
    private static final long POLL_MILLIS = 500;
    private static final int PAGE_SIZE_DEFAULT = 20;
    private static final int PAGE_SIZE_MAX = 200;

    private final MigrationLogMapper mapper;
    private final MigrationTaskMapper taskMapper;
    private final BlockingQueue<MigrationLog> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ConcurrentHashMap<Long, AtomicLong> dropped = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread writer;

    public MigrationLogService(MigrationLogMapper mapper, MigrationTaskMapper taskMapper) {
        this.mapper = mapper;
        this.taskMapper = taskMapper;
    }

    @PostConstruct
    void start() {
        writer = new Thread(this::drainLoop, "opendrs-log-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /**
     * Queues one line. Never throws and never blocks: this is called from engine threads.
     */
    public void log(Long taskId, MigrationLogLevel level, String message) {
        if (taskId == null || message == null || message.isBlank()) {
            return;
        }
        MigrationLog entry = new MigrationLog(taskId, LocalDateTime.now(), level, truncate(message));
        if (!queue.offer(entry)) {
            dropped.computeIfAbsent(taskId, key -> new AtomicLong()).incrementAndGet();
        }
    }

    /** The engine-facing view of {@link #log}, bound to one task. */
    public MigrationLogListener listenerFor(Long taskId) {
        return (level, message) -> log(taskId, level, message);
    }

    /**
     * @param level {@code null} for every level. Filtering happens in SQL, not on the page: a client
     *     side filter would show an empty page whenever the selected level is rare.
     */
    public MigrationLogPage page(Long taskId, MigrationLogLevel level, int page, int size) {
        if (taskMapper.findById(taskId) == null) {
            throw ExceptionFactory.create(ErrorCode.TASK_NOT_FOUND, ErrorMessage.TASK_NOT_FOUND, taskId);
        }
        int pageSize = Math.min(Math.max(size <= 0 ? PAGE_SIZE_DEFAULT : size, 1), PAGE_SIZE_MAX);
        int pageNumber = Math.max(page, 1);
        long total = mapper.count(taskId, level);
        long offset = (long) (pageNumber - 1) * pageSize;
        if (offset >= total) {
            return new MigrationLogPage(List.of(), total, pageNumber, pageSize);
        }
        List<MigrationLogItem> items = mapper
                .findPage(taskId, level, (int) offset, pageSize)
                .stream()
                .map(MigrationLogService::toItem)
                .toList();
        return new MigrationLogPage(items, total, pageNumber, pageSize);
    }

    /** Called before the task row is deleted, in the same transaction. */
    public void deleteByTaskId(Long taskId) {
        mapper.deleteByTaskId(taskId);
        dropped.remove(taskId);
    }

    /**
     * Drains the buffer on the calling thread. Shutdown and tests use it; the read path does not,
     * so a page request never waits behind a write.
     */
    public void flush() {
        List<MigrationLog> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            write(batch);
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
        Thread current = writer;
        if (current != null) {
            try {
                current.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    private void drainLoop() {
        List<MigrationLog> batch = new ArrayList<>(BATCH_MAX);
        while (running.get() || !queue.isEmpty()) {
            try {
                MigrationLog first = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_MAX - 1);
                write(batch);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                batch.clear();
            }
        }
    }

    private void write(List<MigrationLog> batch) {
        appendDropWarnings(batch);
        try {
            mapper.insertBatch(batch);
        } catch (RuntimeException ex) {
            // The usual cause is a task deleted while its lines were still buffered: the foreign
            // key rejects them. Retrying would park the writer behind rows that can never land.
            log.warn("Dropped {} migration log row(s): {}", batch.size(), ex.getMessage());
        }
    }

    /** One warning per task that overflowed, appended to the batch that finally gets through. */
    private void appendDropWarnings(List<MigrationLog> batch) {
        if (dropped.isEmpty()) {
            return;
        }
        Set<Long> tasks = new LinkedHashSet<>();
        for (MigrationLog entry : batch) {
            tasks.add(entry.getTaskId());
        }
        for (Long taskId : tasks) {
            AtomicLong counter = dropped.get(taskId);
            long lost = counter == null ? 0 : counter.getAndSet(0);
            if (lost > 0) {
                batch.add(new MigrationLog(
                        taskId,
                        LocalDateTime.now(),
                        MigrationLogLevel.WARNING,
                        lost + " log line(s) were dropped because the log buffer was full"));
            }
        }
    }

    private static MigrationLogItem toItem(MigrationLog row) {
        return new MigrationLogItem(
                row.getId(),
                row.getLoggedAt() == null
                        ? null
                        : row.getLoggedAt().atZone(ZoneId.systemDefault()).toInstant(),
                row.getLevel(),
                row.getMessage());
    }

    private static String truncate(String message) {
        return message.length() <= MESSAGE_MAX ? message : message.substring(0, MESSAGE_MAX);
    }
}
