package io.opendrs.migration.service;

import io.opendrs.common.error.ErrorCode;
import io.opendrs.common.error.ExceptionFactory;
import io.opendrs.common.i18n.ErrorMessage;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.domain.ConnectionInfo;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Picks the Debezium {@code database.server.id} when a MySQL-sourced task starts, the way
 * cloud DRS products do instead of asking the user: collect the server_ids already registered
 * on the source (its replicas — which includes every connected binlog client, so our own
 * running tasks show up too), then draw random candidates from the huge 32-bit server_id
 * space until one is free.
 *
 * <p>The list is best-effort — {@code SHOW REPLICAS} only sees currently connected clients —
 * so the random draw over ~4.2 billion values is what actually keeps collisions negligible,
 * with MySQL itself refusing a duplicate id as the final safety net. Nothing is persisted:
 * every engine start gets a fresh draw that reflects the source's current topology.
 */
@Component
public class ServerIdAllocator {

    private static final Logger log = LoggerFactory.getLogger(ServerIdAllocator.class);

    /** Below this the small ids replicas typically auto-assign live; stay out of that band. */
    private static final long MIN_SERVER_ID = 5400;
    /** MySQL server_id max (2^32 - 1). */
    private static final long MAX_SERVER_ID = 4_294_967_295L;
    private static final int MAX_ATTEMPTS = 32;

    private final JdbcConnectionFactory connectionFactory;

    public ServerIdAllocator(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /** Fresh, currently-unused server id for this source. */
    public long allocate(ConnectionInfo source) {
        Set<Long> used = replicaServerIds(source);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long candidate = random.nextLong(MIN_SERVER_ID, MAX_SERVER_ID + 1);
            if (used.add(candidate)) {
                log.debug("allocated server id {} for source connection {}", candidate, source.getId());
                return candidate;
            }
        }
        throw new IllegalStateException(
                "unable to allocate a MySQL server id for connection " + source.getId());
    }

    /** server_ids of replicas currently registered on the source. Empty when none, null-set never returned. */
    private Set<Long> replicaServerIds(ConnectionInfo source) {
        try (JdbcConnection connection = connectionFactory.open(source.toJdbcConfig())) {
            return new HashSet<>(queryReplicas(connection));
        } catch (RuntimeException ex) {
            throw ExceptionFactory.create(
                    ErrorCode.CONNECTION_TEST_FAILED,
                    ErrorMessage.CONNECTION_LIST_REPLICAS_FAILED, ex.getMessage());
        }
    }

    /**
     * {@code @@server_uuid} of the instance behind this connection — the UUID namespace local
     * transactions are tagged with. The bidirectional loop filter needs it to recognize the
     * peer's transactions in the captured binlog.
     */
    public String serverUuid(ConnectionInfo connectionInfo) {
        try (JdbcConnection connection = connectionFactory.open(connectionInfo.toJdbcConfig())) {
            return connection.queryList("SELECT @@GLOBAL.server_uuid AS u", rs -> rs.getString("u"))
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("server_uuid query returned nothing"));
        } catch (RuntimeException ex) {
            throw ExceptionFactory.create(
                    ErrorCode.CONNECTION_TEST_FAILED,
                    ErrorMessage.CONNECTION_SERVER_UUID_FAILED, connectionInfo.getId(), ex.getMessage());
        }
    }

    /** SHOW REPLICAS (8.0.22+); older releases only know SHOW SLAVE HOSTS. Both expose a Server_id column. */
    private Set<Long> queryReplicas(JdbcConnection connection) {
        try {
            return Set.copyOf(connection.queryList(
                    "SHOW REPLICAS", rs -> rs.getLong("Server_id")));
        } catch (RuntimeException syntaxOrOldVersion) {
            log.debug("SHOW REPLICAS failed ({}), falling back to SHOW SLAVE HOSTS",
                    syntaxOrOldVersion.getMessage());
            return Set.copyOf(connection.queryList(
                    "SHOW SLAVE HOSTS", rs -> rs.getLong("Server_id")));
        }
    }
}
