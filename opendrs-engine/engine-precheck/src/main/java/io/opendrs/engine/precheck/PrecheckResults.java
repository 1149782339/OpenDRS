package io.opendrs.engine.precheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Stored JSON for one precheck run: the plan with the state each check reached, plus which check is
 * executing right now. The list is complete from the first publish, so the wizard can paint the
 * whole checklist immediately and flip rows one by one.
 */
public record PrecheckResults(String profileId, List<PrecheckItem> items, String runningId) {

    public PrecheckResults {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static PrecheckResults empty() {
        return new PrecheckResults(null, List.of(), null);
    }

    public static PrecheckResults planned(String profileId, List<PrecheckItem> items) {
        return new PrecheckResults(profileId, items, null);
    }

    public PrecheckResults withRunning(String checkId) {
        return new PrecheckResults(profileId, items, checkId);
    }

    public PrecheckResults replace(PrecheckItem item) {
        List<PrecheckItem> updated = new ArrayList<>(items.size());
        for (PrecheckItem current : items) {
            updated.add(current.id().equals(item.id()) ? item : current);
        }
        return new PrecheckResults(profileId, updated, runningId);
    }

    public Optional<PrecheckItem> item(String checkId) {
        return items.stream().filter(item -> item.id().equals(checkId)).findFirst();
    }

    /**
     * Conservative admission: every planned check must have finished with a real verdict, and no
     * blocker may have failed. A failed warning does <em>not</em> block - the wizard shows it as
     * 「请确认」 and moving to the next step is the user's explicit acceptance. Anything without a
     * verdict (pending, error, blocked, unknown) does block: "we could not tell" is never a pass.
     */
    public boolean admitted() {
        return !items.isEmpty() && items.stream().noneMatch(PrecheckItem::blocking);
    }

    /** One line for the task's error column; empty when the run is admitted. */
    public String summary() {
        return items.stream()
                .filter(PrecheckItem::blocking)
                .map(item -> item.id() + ": " + (item.message() == null ? item.state() : item.message()))
                .collect(Collectors.joining("; "));
    }
}
