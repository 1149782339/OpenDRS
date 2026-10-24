package io.opendrs.engine.precheck;

import io.opendrs.engine.precheck.fact.FactStore;

/**
 * One independently executable check. A rule never opens a connection and never queries the
 * database: it reads already-collected facts, decides, and returns the evidence. That is what lets
 * several parameters be read by a single query and lets the same rule serve both sides.
 *
 * <p>There is deliberately no {@code requires()} method: the run is single-threaded and
 * {@link FactStore} collects each side's facts once on first use, so a declared dependency list
 * would only duplicate what the type-safe fact accessors already express.
 */
public interface CheckRule {

    CheckMeta meta();

    Evaluation evaluate(PrecheckContext context, FactStore facts);
}
