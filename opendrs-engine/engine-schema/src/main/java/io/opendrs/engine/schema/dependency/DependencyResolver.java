package io.opendrs.engine.schema.dependency;

import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.schema.meta.ObjectId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DependencyResolver {

    /**
     * Orders objects so that every object appears after the objects it depends on (source
     * dependency order, Kahn's algorithm with original order as the tie-break).
     *
     * <p>Dependencies that are not part of the given set do not affect ordering (export already
     * reported them as missing). On a dependency cycle the remaining objects are appended in
     * original order rather than failing.
     */
    public static List<DatabaseObject> topoSort(List<DatabaseObject> objects) {
        Map<ObjectId, Integer> indexById = new HashMap<>();
        for (int i = 0; i < objects.size(); i++) {
            indexById.put(objects.get(i).id(), i);
        }
        List<DatabaseObject> ordered = new ArrayList<>(objects.size());
        boolean[] emitted = new boolean[objects.size()];
        int remaining = objects.size();
        while (remaining > 0) {
            boolean progressed = false;
            for (int i = 0; i < objects.size() && remaining > 0; i++) {
                if (emitted[i]) {
                    continue;
                }
                boolean ready = true;
                for (ObjectId dependency : objects.get(i).dependencies()) {
                    Integer depIndex = indexById.get(dependency);
                    if (depIndex != null && !emitted[depIndex]) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    emitted[i] = true;
                    ordered.add(objects.get(i));
                    remaining--;
                    progressed = true;
                }
            }
            if (!progressed) {
                for (int i = 0; i < objects.size(); i++) {
                    if (!emitted[i]) {
                        ordered.add(objects.get(i));
                    }
                }
                remaining = 0;
            }
        }
        return ordered;
    }
}
