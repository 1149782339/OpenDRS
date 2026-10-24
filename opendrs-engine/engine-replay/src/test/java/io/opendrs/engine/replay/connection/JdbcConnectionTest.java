package io.opendrs.engine.replay.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.dialect.DatabaseDialect;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The provider shared by every parallel applier worker must stay stateless: each
 * {@link JdbcConnection} acquires and closes its own physical connection. This test pins
 * that contract down — it is exactly what the old task-level {@code TargetConnectionProvider}
 * broke by caching one {@code owned} connection for the whole task.
 */
class JdbcConnectionTest {

    @Test
    void twoConnectionsFromSharedProviderUseDistinctPhysicalConnections() throws SQLException {
        AtomicInteger opens = new AtomicInteger();
        ReplayConfig config = ReplayConfig.builder()
                .connectionProvider(() -> {
                    opens.incrementAndGet();
                    return FakeConnection.proxy().unwrap();
                })
                .build();
        DatabaseDialect dialect = dialectDelegatingTo(config.getConnectionProvider());

        JdbcConnection worker1 = new JdbcConnection(dialect, config);
        JdbcConnection worker2 = new JdbcConnection(dialect, config);

        Connection c1 = worker1.connection();
        Connection c2 = worker2.connection();

        assertNotSame(c1, c2, "each worker must get its own physical connection");
        assertEquals(2, opens.get());

        worker1.close();
        assertTrue(FakeConnection.isClosed(c1), "closing worker1 closes only its own connection");
        assertFalse(FakeConnection.isClosed(c2), "worker2's connection must survive worker1's close");
        assertSame(c2, worker2.connection(), "worker2 keeps using its own live connection");
        assertEquals(2, opens.get(), "no reconnect triggered by another worker's close");

        worker2.close();
        assertTrue(FakeConnection.isClosed(c2));
    }

    /** A dialect whose {@code getConnection} is the real one; every other method is unused. */
    private static DatabaseDialect dialectDelegatingTo(ConnectionProvider provider) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "fake-dialect";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if ("getConnection".equals(method.getName())) {
                return provider.getConnection();
            }
            throw new UnsupportedOperationException("unexpected dialect call: " + method.getName());
        };
        return (DatabaseDialect) Proxy.newProxyInstance(
                JdbcConnectionTest.class.getClassLoader(), new Class<?>[] {DatabaseDialect.class}, handler);
    }

    /** A per-instance fake connection recording its closed flag on a shared static map. */
    private static final class FakeConnection {
        private static final java.util.Map<Connection, AtomicBoolean> FLAGS =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        private final AtomicBoolean closed = new AtomicBoolean();
        private final Connection unwrap;

        private FakeConnection() {
            unwrap = (Connection) Proxy.newProxyInstance(
                    JdbcConnectionTest.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isClosed" -> closed.get();
                        case "isValid" -> !closed.get();
                        case "close" -> {
                            closed.set(true);
                            yield null;
                        }
                        case "toString" -> "fake-connection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "unexpected connection call: " + method.getName());
                    });
            FLAGS.put(unwrap, closed);
        }

        static FakeConnection proxy() {
            return new FakeConnection();
        }

        Connection unwrap() {
            return unwrap;
        }

        static boolean isClosed(Connection connection) {
            AtomicBoolean flag = FLAGS.get(connection);
            return flag == null || flag.get();
        }
    }
}
