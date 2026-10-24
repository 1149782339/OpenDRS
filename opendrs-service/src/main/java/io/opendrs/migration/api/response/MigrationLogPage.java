package io.opendrs.migration.api.response;

import java.util.List;

/**
 * One page of a task's log.
 *
 * <p>{@code total} counts the rows that match the level filter, not the whole task, so the pager
 * matches what is on screen.
 */
public record MigrationLogPage(List<MigrationLogItem> items, long total, int page, int size) {
}
