package io.opendrs.engine.core;

/**
 * Handle for one data-check run. The inconsistent rows are written to the metadata database while
 * the run progresses, so the handle only exposes the shared task lifecycle.
 */
public interface DataCheckTask extends MigrationTask {
}
