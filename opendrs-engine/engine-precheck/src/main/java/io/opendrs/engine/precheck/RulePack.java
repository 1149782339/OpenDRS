package io.opendrs.engine.precheck;

import java.util.List;

/**
 * Contributes a set of checks for one aspect of a scenario (common / source / target / both ends).
 * A pack never queries a database and never decides anything - it only names rules.
 */
public interface RulePack {

    List<RuleBinding> select(PrecheckContext context);
}
