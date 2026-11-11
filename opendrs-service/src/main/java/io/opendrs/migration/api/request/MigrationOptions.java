package io.opendrs.migration.api.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.opendrs.engine.capture.transport.CdcTransportSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;

/**
 * Unified per-task configuration, persisted as the whole {@code options_json} column: what to
 * migrate ({@link #selection()}), how the full load behaves ({@link #full()}) and how incremental
 * replay behaves ({@link #incremental()}). Adding a knob is a new field here — no DDL.
 *
 * <p>Unsupported-but-defined fields (e.g. {@link Topology#BIDIRECTIONAL}) are stored as stated so
 * the shape is stable when the feature lands; the engine simply does not act on them yet.
 *
 * <p>Every field is nullable on the wire on purpose: create() runs {@link #withDefaults()} before
 * validating and persisting, so a client may send only the piece it cares about.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationOptions(
        Topology topology,
        @Valid Selection selection,
        @Valid FullSettings full,
        @Valid IncrementalSettings incremental,
        Bidirectional bidirectional,
        /**
         * Incremental CDC transport for this task: {@code DIRECT} or {@code KAFKA}.
         * Null means inherit the process-wide {@code opendrs.transport.channel} /
         * {@code OPENDRS_TRANSPORT_CHANNEL} (itself defaulting to DIRECT). Full-load always
         * stays in-process regardless of this value.
         */
        CdcTransportSpec cdcTransportSpec
) {
    public enum Topology {
        ONE_WAY,
        BIDIRECTIONAL
    }

    public enum FullObjectType {
        SCHEMA,
        DATA,
        INDEX
    }

    public enum ImportMode {
        COPY,
        INSERT
    }

    public enum CharsetAdapt {
        AUTO,
        KEEP
    }

    /**
     * Target tables that already hold rows when the first full load starts (one-way full +
     * incremental only).
     */
    public enum TargetTablePolicy {
        /** Keep the rows and load over them (rows with the same primary key are overwritten). */
        APPEND,
        /** Empty the tables first. Never cascades: a table referenced from outside the task fails. */
        TRUNCATE,
        /** Refuse to start. */
        FAIL_IF_NOT_EMPTY
    }

    public enum ConflictPolicy {
        OVERWRITE,
        ERROR,
        IGNORE
    }

    public static MigrationOptions defaults() {
        return new MigrationOptions(
                Topology.ONE_WAY,
                Selection.all(),
                FullSettings.defaults(),
                IncrementalSettings.defaults(),
                null,
                null);
    }

    /** Fills every absent piece with its default so a partially-specified request still stores a complete config. */
    public MigrationOptions withDefaults() {
        Selection sel = selection != null ? selection.withDefaults() : Selection.all();
        FullSettings fullSettings = full != null ? full.withDefaults() : FullSettings.defaults();
        IncrementalSettings incrementalSettings =
                incremental != null ? incremental.withDefaults() : IncrementalSettings.defaults();
        // cdcTransportSpec stays as given (including null = inherit global); do not invent a default.
        return new MigrationOptions(
                topology != null ? topology : Topology.ONE_WAY,
                sel,
                fullSettings,
                incrementalSettings,
                bidirectional,
                cdcTransportSpec);
    }

    /** Bidirectional bookkeeping, generating the GTID namespaces on first use (task creation). */
    public MigrationOptions withBidirectionalDefaults() {
        if (topology != Topology.BIDIRECTIONAL || bidirectional != null) {
            return this;
        }
        return new MigrationOptions(
                topology, selection, full, incremental,
                new Bidirectional(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                cdcTransportSpec);
    }

    /**
     * What this task migrates. {@link Mode#ALL} means the four lists are empty (exclusions are
     * still allowed); {@code SCHEMA} scopes to whole schemas; {@code TABLE} to individual tables.
     * The mode is explicit — the UI's radio — instead of being inferred from which lists are empty.
     */
    public record Selection(
            Mode mode,
            List<String> schemas,
            List<@Valid SelectedTable> tables,
            List<String> excludeSchemas,
            List<@Valid SelectedTable> excludeTables,
            @Valid TableMappings mappings
    ) {
        public enum Mode {
            ALL,
            SCHEMA,
            TABLE
        }

        public static Selection all() {
            return new Selection(Mode.ALL, List.of(), List.of(), List.of(), List.of(), null);
        }

        public static Selection ofSchemas(List<String> schemas) {
            return new Selection(Mode.SCHEMA, schemas, List.of(), List.of(), List.of(), null);
        }

        public static Selection ofTables(List<SelectedTable> tables) {
            return new Selection(Mode.TABLE, List.of(), tables, List.of(), List.of(), null);
        }

        public Selection withDefaults() {
            return new Selection(
                    mode != null ? mode : Mode.ALL,
                    schemas != null ? schemas : List.of(),
                    tables != null ? tables : List.of(),
                    excludeSchemas != null ? excludeSchemas : List.of(),
                    excludeTables != null ? excludeTables : List.of(),
                    mappings);
        }

        /** Legacy flat shape consumed by the engine / mapping validator. */
        public TableSelection toTableSelection() {
            return new TableSelection(schemas, tables, excludeSchemas, excludeTables, mappings);
        }
    }

    /** Full-load behaviour. {@code chunkMultiplier} feeds Debezium's {@code snapshot.max.threads.multiplier}. */
    public record FullSettings(
            List<FullObjectType> objectTypes,
            @Min(1) Integer exportConcurrency,
            @Min(1) Integer importConcurrency,
            ImportMode importMode,
            /** Debezium snapshot threads multiplier: 实际分片数 ≈ 并发线程数 × 分片倍率。 */
            @Min(1) @Max(10) Integer chunkMultiplier,
            CharsetAdapt charsetAdapt,
            /**
             * What the first full load does with target tables that already hold rows. Null (tasks
             * stored before the option existed) means {@link TargetTablePolicy#FAIL_IF_NOT_EMPTY}.
             */
            TargetTablePolicy targetTablePolicy
    ) {
        public FullSettings(
                List<FullObjectType> objectTypes,
                Integer exportConcurrency,
                Integer importConcurrency,
                ImportMode importMode,
                Integer chunkMultiplier,
                CharsetAdapt charsetAdapt) {
            this(objectTypes, exportConcurrency, importConcurrency, importMode, chunkMultiplier, charsetAdapt, null);
        }

        public static FullSettings defaults() {
            return new FullSettings(
                    List.of(FullObjectType.SCHEMA, FullObjectType.DATA, FullObjectType.INDEX),
                    8,
                    8,
                    ImportMode.COPY,
                    4,
                    CharsetAdapt.AUTO,
                    TargetTablePolicy.FAIL_IF_NOT_EMPTY);
        }

        /** The policy in force: the stored one, or the default when the task predates it. */
        public TargetTablePolicy effectiveTargetTablePolicy() {
            return targetTablePolicy != null ? targetTablePolicy : TargetTablePolicy.FAIL_IF_NOT_EMPTY;
        }

        public FullSettings withDefaults() {
            FullSettings d = defaults();
            return new FullSettings(
                    objectTypes != null ? objectTypes : d.objectTypes(),
                    exportConcurrency != null ? exportConcurrency : d.exportConcurrency(),
                    importConcurrency != null ? importConcurrency : d.importConcurrency(),
                    importMode != null ? importMode : d.importMode(),
                    chunkMultiplier != null ? chunkMultiplier : d.chunkMultiplier(),
                    charsetAdapt != null ? charsetAdapt : d.charsetAdapt(),
                    targetTablePolicy != null ? targetTablePolicy : d.targetTablePolicy());
        }
    }

    /** Incremental replay behaviour. */
    public record IncrementalSettings(
            @Min(1) Integer replayConcurrency,
            ConflictPolicy conflictPolicy
    ) {
        public static IncrementalSettings defaults() {
            return new IncrementalSettings(64, ConflictPolicy.OVERWRITE);
        }

        public IncrementalSettings withDefaults() {
            return new IncrementalSettings(
                    replayConcurrency != null ? replayConcurrency : 64,
                    conflictPolicy != null ? conflictPolicy : ConflictPolicy.OVERWRITE);
        }
    }

    /**
     * Bidirectional-sync bookkeeping, generated at task creation when topology is
     * {@link Topology#BIDIRECTIONAL}. Each direction stamps its snapshot (full-load) batches on
     * the target with a synthetic GTID from its own namespace; the opposite direction's capture
     * drops those transactions, which — together with dropping the peer server's own GTID UUID —
     * is what breaks the write-back loop.
     */
    public record Bidirectional(
            String forwardFullGtidNamespace,
            String reverseFullGtidNamespace) {
    }
}
