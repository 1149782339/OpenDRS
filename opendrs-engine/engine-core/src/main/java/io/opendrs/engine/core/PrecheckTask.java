package io.opendrs.engine.core;

import io.opendrs.engine.precheck.PrecheckResults;

/** Handle for one precheck run. {@link #results()} is {@code null} until the job completes. */
public interface PrecheckTask extends MigrationTask {

    PrecheckResults results();
}
