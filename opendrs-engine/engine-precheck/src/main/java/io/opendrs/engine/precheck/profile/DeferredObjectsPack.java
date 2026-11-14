package io.opendrs.engine.precheck.profile;

import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.RuleBinding;
import io.opendrs.engine.precheck.RulePack;
import io.opendrs.engine.precheck.Severity;
import java.util.List;

/**
 * Checks of the deferred-target-objects flow (one-way full + incremental: secondary indexes,
 * unique keys and foreign keys dropped before the full load and rebuilt afterwards, FK
 * enforcement off on the target sessions). Bound only when the task runs that flow; all are
 * warnings, since each describes a consequence the operator may accept.
 */
final class DeferredObjectsPack {

    static final RulePack PACK = context -> context.deferredObjects() && !context.bidirectional()
            ? List.of(
                    new RuleBinding(CheckIds.TARGET_TABLE_NOT_EMPTY, Severity.WARNING),
                    new RuleBinding(CheckIds.TARGET_EXTERNAL_FOREIGN_KEY, Severity.WARNING),
                    new RuleBinding(CheckIds.TARGET_FOREIGN_KEY_BYPASS, Severity.WARNING),
                    new RuleBinding(CheckIds.SOURCE_CASCADE_FOREIGN_KEY, Severity.WARNING))
            : List.of();

    private DeferredObjectsPack() {}
}
