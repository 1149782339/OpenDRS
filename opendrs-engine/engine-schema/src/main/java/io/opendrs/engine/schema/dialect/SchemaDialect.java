package io.opendrs.engine.schema.dialect;

import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.engine.schema.meta.ObjectType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * The three source-side reads schema migration needs: enumerate objects in a schema, resolve an
 * object's dependencies, and read its DDL.
 *
 * <p>Deliberately narrow, and read-only. The full replay dialect knows about INSERT/UPSERT building,
 * Kafka Connect schemas and statement binding — none of which schema migration touches, and it also
 * owns the target-side writes, which the import stage does itself. Keeping this interface here lets
 * {@code engine-schema} stay free of a dependency on {@code engine-replay}.
 */
public interface SchemaDialect {

    List<ObjectId> getObjects(Connection connection, ObjectType type, String schema) throws SQLException;

    List<ObjectId> getObjectDependencies(Connection connection, ObjectId id, boolean ignoreForeignKey)
            throws SQLException;

    String getObjectDdl(Connection connection, ObjectId id) throws SQLException;
}
