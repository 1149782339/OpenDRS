package io.opendrs.engine.schema.imports;

import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.meta.ObjectId;

/**
 * Called once per object as the batch progresses, in execution order, after that object's outcome
 * is known. Lets a caller stream per-table progress without the importer having to buffer the whole
 * batch first.
 */
@FunctionalInterface
public interface ImportListener {

    ImportListener NONE = (id, result) -> { };

    /**
     * @param sourceId the source object identity
     * @param result its import outcome ({@code SUCCEEDED}, {@code FAILED} or {@code DROPPED})
     */
    void onObject(ObjectId sourceId, ConversionResult result);
}
