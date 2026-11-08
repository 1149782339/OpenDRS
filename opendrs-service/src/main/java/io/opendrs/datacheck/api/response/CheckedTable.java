package io.opendrs.datacheck.api.response;

import io.opendrs.datacheck.api.request.SelectedTable;

/** One checked table pair; the target is resolved when the task is created. */
public record CheckedTable(SelectedTable source, SelectedTable target) {
}
