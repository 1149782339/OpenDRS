package io.opendrs.engine.datacheck.type.serialize;

import io.opendrs.engine.datacheck.type.LogicalValue;

/**
 * Serializes both sides of one comparison into canonical text, so that the data check can compare
 * them with plain string equality and report a difference as text.
 *
 * <p>The two sides are serialized together rather than in isolation: what a source value means
 * depends on the target column it is compared with (an {@code INTEGER} against a {@code BIGINT}, a
 * MySQL {@code tinyint(1)} against a PostgreSQL {@code boolean}, ...), so the implementation is
 * allowed to look at both values and normalize them against each other.
 */
public interface Serializer {

    /**
     * Serializes one source/target pair.
     *
     * @param source the value read from the source side
     * @param target the value read from the target side
     * @param columnIndex 0-based index into the source/target column definitions
     * @param context the row buffers, owned and reused by the caller
     */
    void serialize(LogicalValue source, LogicalValue target, int columnIndex, SerializeContext context);
}
