package io.opendrs.engine.datacheck.type.serialize;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.datacheck.type.LogicalValue;

/**
 * Source → PostgreSQL. Both sides are written into the row buffers, because a value only means
 * something in the pair it is compared in.
 *
 * <p>The source database is table-scoped configuration; most pairs need nothing special and each
 * {@link LogicalValue} already knows how to append its own canonical text. The MySQL hook stays as
 * the single place for real representation differences (for example {@code BIT}/{@code tinyint(1)}
 * against PostgreSQL {@code bit}/{@code boolean}); plain {@code BINARY(n)} does not need it — the
 * zero padding is part of the stored value on both sides.
 */
public class PostgreSqlSerializer implements Serializer {

    private final SerializeConfiguration configuration;

    public PostgreSqlSerializer(SerializeConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void serialize(
            LogicalValue source, LogicalValue target, int columnIndex, SerializeContext context) {
        if (configuration.sourceDatabase() == DatabaseType.MYSQL
                && serializeForMysql(source, target, columnIndex, context)) {
            return;
        }
        source.serialize(context.source());
        target.serialize(context.target());
    }

    /**
     * MySQL-specific normalization hook.
     *
     * @return {@code true} when the pair was fully written and the default path must be skipped;
     *     {@code false} to fall through to the canonical rendering of both values
     */
    private boolean serializeForMysql(
            LogicalValue source, LogicalValue target, int columnIndex, SerializeContext context) {
        return false;
    }
}
