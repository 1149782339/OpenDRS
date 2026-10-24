package io.opendrs.migration.service;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.domain.ConnectionInfo;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Live "how far has the source written" position for the task detail page: opens the source
 * connection and runs {@code SHOW MASTER STATUS}, returning {@code file:pos}. Results
 * are cached briefly so the status polling loop never hammers the source.
 *
 * <p>{@code SHOW MASTER STATUS} works on every MySQL 5.x/8.0 release; {@code SHOW BINARY LOG
 * STATUS} only exists from 8.4. Keep the old spelling until 8.0 sources fall out of support.
 */
@Component
public class SourcePositionQuery {

    private static final Logger log = LoggerFactory.getLogger(SourcePositionQuery.class);
    private static final long CACHE_TTL_MS = 3_000;

    private final JdbcConnectionFactory connectionFactory;
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public SourcePositionQuery(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /** Current source binlog position ("mysql-bin.000007:197"), or null when unreachable. */
    public String query(ConnectionInfo source) {
        if (source == null || source.getId() == null) {
            return null;
        }
        CacheEntry entry = cache.get(source.getId());
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.atMs() < CACHE_TTL_MS) {
            return entry.position();
        }
        String position = fetch(source);
        cache.put(source.getId(), new CacheEntry(position, now));
        return position;
    }

    private String fetch(ConnectionInfo source) {
        try (JdbcConnection connection = connectionFactory.open(source.toJdbcConfig())) {
            Row row = connection.queryOne(
                    "SHOW MASTER STATUS",
                    rs -> new Row(rs.getString("File"), rs.getLong("Position")));
            if (row == null || row.file() == null) {
                // Binlog disabled or emptied: nothing meaningful to show.
                return null;
            }
            return row.file() + ":" + row.pos();
        } catch (RuntimeException ex) {
            // Source unreachable (stopped task, deleted connection...): show "—", not an error.
            log.debug("source position query failed for connection {}: {}",
                    source.getId(), ex.getMessage());
            return null;
        }
    }

    private record Row(String file, long pos) {
    }

    private record CacheEntry(String position, long atMs) {
    }
}
