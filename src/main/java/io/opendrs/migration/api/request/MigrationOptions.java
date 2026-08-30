package io.opendrs.migration.api.request;

import jakarta.validation.constraints.Min;

public record MigrationOptions(
        @Min(1) Integer fullDumpParallelism,
        @Min(1) Integer batchSize,
        @Min(1) Long databaseServerId
) {
    public static MigrationOptions defaults() {
        return new MigrationOptions(8, 1000, null);
    }

    public MigrationOptions withDefaults() {
        return new MigrationOptions(
                fullDumpParallelism != null ? fullDumpParallelism : 8,
                batchSize != null ? batchSize : 1000,
                databaseServerId
        );
    }
}
