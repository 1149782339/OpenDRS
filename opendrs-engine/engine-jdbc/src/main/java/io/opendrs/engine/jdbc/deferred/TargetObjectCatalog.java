package io.opendrs.engine.jdbc.deferred;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Reads, drops and recreates the secondary objects of target tables (one implementation per
 * target dialect). Everything is read from the target's own catalog, so whatever the schema
 * migration or the user created is restored as it was.
 *
 * <p>What is never captured: the primary key and the index behind it, indexes inherited by
 * partitions from their parent, the unique keys of tables without a primary key (replay may rely
 * on them to address rows), and keys that a foreign key from outside the task depends on.
 */
public interface TargetObjectCatalog {

    DatabaseType type();

    /** Secondary objects of {@code tables} that the task may drop and rebuild. */
    List<DeferredObject> capture(JdbcConnection target, Collection<TableId> tables) throws SQLException;

    /** Foreign keys of tables outside {@code tables} that reference a table inside it. */
    List<ExternalReference> externalReferences(JdbcConnection target, Collection<TableId> tables)
            throws SQLException;

    /** Whether the object is currently present on the target (drop/create idempotency). */
    boolean exists(JdbcConnection target, DeferredObject object) throws SQLException;

    /** Session statements that switch foreign-key enforcement off for this connection. */
    List<String> disableForeignKeySql();

    /**
     * Session statement capping how long a rebuild DDL waits for a table lock. While replay
     * writes, a DDL queued behind a row lock also blocks every later writer on that table, so
     * the rebuild gives up quickly and retries instead of stalling replay.
     */
    String lockTimeoutSql(int seconds);

    /** Whether {@code ex} is a lock conflict (deadlock or lock wait timeout) worth retrying at once. */
    boolean isLockConflict(SQLException ex);

    String truncateSql(TableId table);

    String analyzeSql(TableId table);

    /** Returns a row when the table holds data. */
    String probeRowSql(TableId table);

    /**
     * One statement that creates several objects of the same table, when the dialect can do that
     * cheaper than one by one (MySQL builds every index of one {@code ALTER TABLE} in a single
     * pass). Empty when the objects must be created separately.
     */
    default Optional<String> mergedCreate(List<DeferredObject> sameTable) {
        return Optional.empty();
    }

    static TargetObjectCatalog forType(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL -> new PostgresObjectCatalog();
            case MYSQL -> new MySqlObjectCatalog();
            default -> throw new IllegalArgumentException("No target object catalog for " + type);
        };
    }

    static boolean supports(DatabaseType type) {
        return type == DatabaseType.POSTGRESQL || type == DatabaseType.MYSQL;
    }
}
