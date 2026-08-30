package io.opendrs.precheck;

import io.opendrs.migration.domain.DbType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DbPreChecks {

    private final Map<DbType, DbPreCheck> byType;

    @Autowired
    public DbPreChecks(List<DbPreCheck> checks) {
        Map<DbType, DbPreCheck> map = new EnumMap<>(DbType.class);
        for (DbPreCheck check : checks) {
            map.put(check.type(), check);
        }
        this.byType = Map.copyOf(map);
    }

    public Optional<DbPreCheck> of(DbType type) {
        return Optional.ofNullable(byType.get(type));
    }
}
