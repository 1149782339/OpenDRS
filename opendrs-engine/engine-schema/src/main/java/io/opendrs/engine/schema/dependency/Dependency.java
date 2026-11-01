package io.opendrs.engine.schema.dependency;

import io.opendrs.engine.schema.meta.DatabaseObject;

import java.util.List;

public class Dependency {
    DatabaseObject object;
    List<Dependency> dependencies;
}
