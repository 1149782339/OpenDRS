package io.opendrs.engine.core;

/**
 * Handle for one CDC run: snapshot followed by binlog streaming. The run only ends when stopped or
 * when it fails — there is no natural completion.
 */
public interface DataMigrationTask extends MigrationTask {

    /**
     * Which half of the run is active. Always present: a data run is defined as full load followed
     * by streaming, so it reports {@link DataMigrationTaskPhase#FULL} from the moment the handle
     * exists and switches to {@link DataMigrationTaskPhase#INCREMENTAL} once the snapshot is done.
     */
    DataMigrationTaskPhase taskPhase();
}
