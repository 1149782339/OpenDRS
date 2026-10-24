package io.opendrs.migration.domain;

import io.opendrs.common.log.MigrationLogLevel;
import java.time.LocalDateTime;

/**
 * One line of a task's log.
 *
 * <p>{@code loggedAt} is a {@link LocalDateTime} on purpose: the row is written with an explicit
 * value (a batch of 200 rows would otherwise share one {@code CURRENT_TIMESTAMP}), and MySQL maps
 * {@code DATETIME} to {@code LocalDateTime} without a type handler on either side. The API converts
 * it to an {@code Instant} so the wire format matches {@code createdAt}.
 */
public class MigrationLog {

    private Long id;
    private Long taskId;
    private LocalDateTime loggedAt;
    private MigrationLogLevel level;
    private String message;

    public MigrationLog() {
    }

    public MigrationLog(Long taskId, LocalDateTime loggedAt, MigrationLogLevel level, String message) {
        this.taskId = taskId;
        this.loggedAt = loggedAt;
        this.level = level;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public LocalDateTime getLoggedAt() {
        return loggedAt;
    }

    public void setLoggedAt(LocalDateTime loggedAt) {
        this.loggedAt = loggedAt;
    }

    public MigrationLogLevel getLevel() {
        return level;
    }

    public void setLevel(MigrationLogLevel level) {
        this.level = level;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
