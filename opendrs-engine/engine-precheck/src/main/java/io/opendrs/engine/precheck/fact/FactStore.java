package io.opendrs.engine.precheck.fact;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.precheck.PrecheckContext;
import io.opendrs.engine.precheck.Side;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import java.util.EnumMap;
import java.util.Map;

/**
 * The two endpoints of one run. Rules ask for a side and get the same cached instance back, so
 * several rules share one {@code SHOW VARIABLES} and one table-metadata sweep.
 */
public final class FactStore implements AutoCloseable {

    private final Map<Side, EndpointFacts> bySide = new EnumMap<>(Side.class);

    public FactStore(PrecheckContext context, JdbcConnectionFactory factory) {
        bySide.put(Side.SOURCE, create(Side.SOURCE, context.source(), factory));
        bySide.put(Side.TARGET, create(Side.TARGET, context.target(), factory));
    }

    public EndpointFacts source() {
        return bySide.get(Side.SOURCE);
    }

    public EndpointFacts target() {
        return bySide.get(Side.TARGET);
    }

    public EndpointFacts of(Side side) {
        EndpointFacts facts = bySide.get(side);
        if (facts == null) {
            throw new IllegalArgumentException("No endpoint facts for side " + side);
        }
        return facts;
    }

    private static EndpointFacts create(Side side, JdbcConnectionConfig config, JdbcConnectionFactory factory) {
        DatabaseType type = config.getType();
        return switch (type) {
            case MYSQL -> new MysqlFacts(side, config, factory);
            case POSTGRESQL -> new PostgresFacts(side, config, factory);
            default -> throw new IllegalStateException("No fact reader for " + type);
        };
    }

    @Override
    public void close() {
        bySide.values().forEach(EndpointFacts::close);
    }
}
