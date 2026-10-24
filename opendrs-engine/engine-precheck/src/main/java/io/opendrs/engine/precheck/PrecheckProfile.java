package io.opendrs.engine.precheck;

import io.opendrs.common.metadata.DatabaseType;
import java.util.List;

/**
 * One migration direction's checklist: which rules apply, and how much each one matters. The profile
 * is data, not code - adding a check means adding a rule class and one binding line here.
 */
public interface PrecheckProfile {

    String id();

    String label();

    DatabaseType sourceType();

    DatabaseType targetType();

    /** Packs in execution order: shared checks first, then each side, then the cross-side ones. */
    List<RulePack> packs();
}
