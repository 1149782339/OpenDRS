package io.opendrs.migration.api.response;

import java.util.List;

/**
 * Deferred target objects of a task.
 *
 * @param gate {@code NONE} (the task does not defer objects), {@code CAPTURED} (some are still
 *     dropped or failed) or {@code RESTORED} (all back)
 * @param snapshotEndPosition source position replay must pass before unique and foreign keys come
 *     back; null until the snapshot is loaded
 */
public record DeferredObjectsResponse(
        String gate,
        String snapshotEndPosition,
        int total,
        int rebuilt,
        int failed,
        List<DeferredObjectResponse> objects) {
}
