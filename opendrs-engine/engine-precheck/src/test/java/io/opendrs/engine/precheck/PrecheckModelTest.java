package io.opendrs.engine.precheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.jdbc.JdbcConnectionConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The three rules the service relies on: what blocks, what is admitted, and how a plan is checked. */
class PrecheckModelTest {

    private static final PrecheckContext CONTEXT = new PrecheckContext(
            config(DatabaseType.MYSQL),
            config(DatabaseType.POSTGRESQL),
            List.of(),
            List.of(),
            true,
            false);

    @Test
    void failedBlockerBlocksButFailedWarningDoesNot() {
        PrecheckResults results = PrecheckResults.planned("p", List.of(
                completed("a", Severity.BLOCKER, Verdict.PASS),
                completed("b", Severity.WARNING, Verdict.FAIL)));

        assertTrue(results.admitted());
        assertTrue(results.summary().isEmpty());
    }

    @Test
    void failedBlockerIsNotAdmitted() {
        PrecheckResults results = PrecheckResults.planned("p", List.of(
                completed("a", Severity.BLOCKER, Verdict.FAIL)));

        assertFalse(results.admitted());
        assertEquals("a: boom", results.summary());
    }

    @Test
    void unknownVerdictIsNeverAPass() {
        PrecheckResults results = PrecheckResults.planned("p", List.of(
                completed("a", Severity.WARNING, Verdict.UNKNOWN)));

        assertFalse(results.admitted());
    }

    @Test
    void pendingOrBlockedRowsAreNotAdmitted() {
        PrecheckItem pending = PrecheckItem.pending(
                new CheckMeta("a", "A", CheckGroup.NETWORK, Side.SOURCE), Severity.INFO, 0);
        PrecheckItem blocked = pending.blocked("A", "no route");

        assertFalse(PrecheckResults.planned("p", List.of(pending)).admitted());
        assertFalse(PrecheckResults.planned("p", List.of(blocked)).admitted());
    }

    @Test
    void emptyPlanIsNeverAdmitted() {
        assertFalse(PrecheckResults.empty().admitted());
    }

    @Test
    void duplicateBindingIsRejectedAtAssembly() {
        RuleRegistry registry = new RuleRegistry(List.of(passRule("a")));
        PrecheckProfile duplicated = new Profile(List.of(
                context -> List.of(new RuleBinding("a", Severity.BLOCKER)),
                context -> List.of(new RuleBinding("a", Severity.WARNING))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PlanBuilder(registry).build(duplicated, CONTEXT));
        assertTrue(error.getMessage().contains("bound twice"));
    }

    @Test
    void unknownRuleIsRejectedAtAssembly() {
        RuleRegistry registry = new RuleRegistry(List.of(passRule("a")));
        PrecheckProfile unknown = new Profile(List.of(
                context -> List.of(new RuleBinding("nope", Severity.BLOCKER))));

        assertThrows(IllegalStateException.class, () -> new PlanBuilder(registry).build(unknown, CONTEXT));
    }

    @Test
    void emptySelectionIsRejectedAtAssembly() {
        RuleRegistry registry = new RuleRegistry(List.of(passRule("a")));
        PrecheckProfile empty = new Profile(List.of(context -> List.of()));

        assertThrows(IllegalStateException.class, () -> new PlanBuilder(registry).build(empty, CONTEXT));
    }

    @Test
    void displayOrderFollowsGroupsWhileExecutionOrderIsKept() {
        RuleRegistry registry = new RuleRegistry(List.of(
                rule("net", CheckGroup.NETWORK),
                rule("param", CheckGroup.PARAMETER)));
        PrecheckProfile profile = new Profile(List.of(context -> List.of(
                new RuleBinding("net", Severity.BLOCKER),
                new RuleBinding("param", Severity.BLOCKER))));

        PrecheckPlan plan = new PlanBuilder(registry).build(profile, CONTEXT);

        assertEquals(List.of("net", "param"), plan.checks().stream().map(PlannedCheck::ruleId).toList());
        assertEquals(1, plan.checks().get(0).order());
        assertEquals(0, plan.checks().get(1).order());
    }

    private static PrecheckItem completed(String id, Severity severity, Verdict verdict) {
        PrecheckItem pending = PrecheckItem.pending(
                new CheckMeta(id, id, CheckGroup.NETWORK, Side.SOURCE), severity, 0);
        Evaluation evaluation = verdict == Verdict.FAIL
                ? Evaluation.fail("boom", "actual", "expected", "advice")
                : new Evaluation(verdict, List.of());
        return pending.completed(evaluation);
    }

    private static CheckRule passRule(String id) {
        return rule(id, CheckGroup.NETWORK);
    }

    private static CheckRule rule(String id, CheckGroup group) {
        return new CheckRule() {
            @Override
            public CheckMeta meta() {
                return new CheckMeta(id, id, group, Side.SOURCE);
            }

            @Override
            public Evaluation evaluate(PrecheckContext context, io.opendrs.engine.precheck.fact.FactStore facts) {
                return Evaluation.pass();
            }
        };
    }

    private static JdbcConnectionConfig config(DatabaseType type) {
        return JdbcConnectionConfig.builder()
                .type(type)
                .host("127.0.0.1")
                .port(1)
                .database("db")
                .username("u")
                .password("p")
                .build();
    }

    private record Profile(List<RulePack> packs) implements PrecheckProfile {

        @Override
        public String id() {
            return "test";
        }

        @Override
        public String label() {
            return "test";
        }

        @Override
        public DatabaseType sourceType() {
            return DatabaseType.MYSQL;
        }

        @Override
        public DatabaseType targetType() {
            return DatabaseType.POSTGRESQL;
        }
    }
}
