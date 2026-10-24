package io.opendrs.engine.precheck;

import io.opendrs.common.metadata.DatabaseType;
import java.util.List;
import java.util.Optional;

/** The available migration directions. Lookup is by the pair of endpoint types. */
public final class PrecheckProfiles {

    private final List<PrecheckProfile> profiles;

    public PrecheckProfiles(List<PrecheckProfile> profiles) {
        this.profiles = List.copyOf(profiles);
    }

    public Optional<PrecheckProfile> of(DatabaseType source, DatabaseType target) {
        return profiles.stream()
                .filter(profile -> profile.sourceType() == source && profile.targetType() == target)
                .findFirst();
    }

    public boolean supports(DatabaseType source, DatabaseType target) {
        return of(source, target).isPresent();
    }
}
