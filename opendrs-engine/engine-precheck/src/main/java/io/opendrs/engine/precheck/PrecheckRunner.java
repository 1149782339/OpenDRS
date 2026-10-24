package io.opendrs.engine.precheck;

import io.opendrs.engine.precheck.fact.FactStore;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes the plan on the calling thread, one check at a time, publishing after every state change.
 *
 * <p>Deliberately serial and deliberately simple: precheck runs once per task, so throughput does
 * not matter, while "which row is spinning" does. There is no stop flag between checks and no
 * per-query timeout - the only way a check is slow is an unreachable endpoint, and that is already
 * bounded by the connect timeouts. What must not happen is one broken rule erasing the other rows,
 * hence the catch-all that records a single failed row.
 */
public final class PrecheckRunner {

    private static final Logger log = LoggerFactory.getLogger(PrecheckRunner.class);
    private static final int MESSAGE_MAX = 300;

    private final PrecheckPlan plan;
    private final PrecheckContext context;
    private final FactStore facts;
    private final Consumer<PrecheckResults> listener;

    public PrecheckRunner(
            PrecheckPlan plan,
            PrecheckContext context,
            FactStore facts,
            Consumer<PrecheckResults> listener) {
        this.plan = plan;
        this.context = context;
        this.facts = facts;
        this.listener = listener;
    }

    public PrecheckResults run() {
        PrecheckResults results = PrecheckResults.planned(plan.profileId(), plan.pendingItems());
        // Publishing the whole checklist first is what lets the UI paint every row immediately.
        publish(results);
        for (PlannedCheck check : plan.checks()) {
            results = results.withRunning(check.ruleId());
            publish(results);
            PrecheckItem item = runOne(check);
            results = results.replace(item).withRunning(null);
            publish(results);
        }
        return results;
    }

    private PrecheckItem runOne(PlannedCheck check) {
        PrecheckItem item = pendingItem(check);
        try {
            return item.completed(check.rule().evaluate(context, facts));
        } catch (DependencyUnavailableException ex) {
            return item.blocked(titleOf(ex.rootCause()), ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Precheck rule {} failed", check.ruleId(), ex);
            return item.error(message(ex));
        }
    }

    /** The root cause is named by its title in the plan, so the UI never shows a raw rule id. */
    private String titleOf(String checkId) {
        return plan.checks().stream()
                .filter(check -> check.ruleId().equals(checkId))
                .map(check -> check.rule().meta().title())
                .findFirst()
                .orElse(checkId);
    }

    private PrecheckItem pendingItem(PlannedCheck check) {
        return PrecheckItem.pending(check.rule().meta(), check.severity(), check.order());
    }

    private static String message(RuntimeException ex) {
        String text = ex.getMessage();
        if (text == null || text.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return text.length() <= MESSAGE_MAX ? text : text.substring(0, MESSAGE_MAX);
    }

    private void publish(PrecheckResults results) {
        if (listener != null) {
            listener.accept(results);
        }
    }
}
