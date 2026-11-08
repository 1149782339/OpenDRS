package io.opendrs.engine.datacheck.type.serialize;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.util.Pair;
import io.opendrs.engine.datacheck.type.Binary;
import io.opendrs.engine.datacheck.type.LogicalValue;

import java.util.Optional;

/**
 * Source → PostgreSQL. Both sides are handled together, because a value only means something in
 * the pair it is compared in.
 *
 * <p>Most pairs need nothing special: every {@link LogicalValue} already knows its canonical text
 * form through {@link Object#toString()}, so the two sides are simply rendered and compared. The
 * per-source methods below only handle the differences between how the engines store a value on
 * either side; returning an empty {@link Optional} means "fall through to the canonical form".
 */
public class PostgreSqlSerialize implements Serialize {

    @Override
    public Pair<String, String> serialize(
            LogicalValue source, LogicalValue target, DatabaseType databaseType) {
        Optional<Pair<String, String>> special = switch (databaseType) {
            case MYSQL -> serializeForMysqlToPostgres(source, target);
            case ORACLE, POSTGRESQL -> Optional.empty();
        };
        return special.orElseGet(() -> Pair.of(text(source), text(target)));
    }

    /**
     * MySQL pads {@code BINARY(n)} values with {@code 0x00} up to the declared length while
     * PostgreSQL {@code bytea} keeps the bytes as written, so the padding is removed from the MySQL
     * side and the PostgreSQL side is taken as is. Only the pair tells which side is the padded one,
     * which is why the two are rendered here together.
     */
    private static Optional<Pair<String, String>> serializeForMysqlToPostgres(
            LogicalValue source, LogicalValue target) {
        if (source instanceof Binary(byte[] sourceBytes) && target instanceof Binary(byte[] targetBytes)) {
            return Optional.of(Pair.of(
                    new Binary(stripTrailingZero(sourceBytes)).toString(),
                    new Binary(targetBytes).toString()));
        }
        return Optional.empty();
    }

    private static String text(LogicalValue value) {
        return value == null ? "null" : value.toString();
    }

    private static byte[] stripTrailingZero(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return bytes;
        }
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        if (end == bytes.length) {
            return bytes;
        }
        byte[] trimmed = new byte[end];
        System.arraycopy(bytes, 0, trimmed, 0, end);
        return trimmed;
    }
}
