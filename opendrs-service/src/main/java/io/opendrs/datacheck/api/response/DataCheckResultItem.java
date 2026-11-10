package io.opendrs.datacheck.api.response;

import java.time.Instant;

/**
 * One inconsistent row.
 *
 * @param tableId source table identifier ({@code schema.table})
 * @param pk encoded primary key ({@code \u0000} joined for composite keys)
 * @param flag 0 = both sides differ, 1 = source only, 2 = target only
 */
public record DataCheckResultItem(
        String tableId,
        String pk,
        int flag,
        Instant createdAt,
        Instant updatedAt) {
}
