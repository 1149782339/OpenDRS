package io.opendrs.engine.schema.dialect;

import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.engine.schema.meta.ObjectType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * The three dialect operations structure migration needs: target identifier resolution, table
 * existence check, and DDL execution.
 *
 * <p>Deliberately narrow. The full replay dialect knows about INSERT/UPSERT building, Kafka Connect
 * schemas and statement binding — none of which structure migration touches. Keeping this interface
 * here lets {@code engine-schema} stay free of a dependency on {@code engine-replay}; the engine
 * facade adapts the target database's dialect to it when assembling a run.
 */
public interface SchemaDialect {

    List<ObjectId> getObjects(Connection connection, ObjectType type, String schema) throws SQLException;

    List<ObjectId> getObjectDependencies(Connection connection, ObjectId id, boolean ignoreForeignKey)
            throws SQLException;

    String getObjectDdl(Connection connection, ObjectId id) throws SQLException;
}
