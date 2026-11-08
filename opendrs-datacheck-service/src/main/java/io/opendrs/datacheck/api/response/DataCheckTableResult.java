package io.opendrs.datacheck.api.response;

/**
 * Per-table summary of one check task, for the table list the frontend renders before drilling
 * into a table's rows.
 *
 * @param tableId source table identifier ({@code schema.table})
 * @param differentCount rows present on both sides but different
 * @param sourceOnlyCount rows only on the source
 * @param targetOnlyCount rows only on the target
 * @param totalCount sum of the three
 */
public record DataCheckTableResult(
        String tableId,
        long differentCount,
        long sourceOnlyCount,
        long targetOnlyCount,
        long totalCount) {
}
