package io.opendrs.engine.replay.transaction;

import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Transaction
 *
 */
public class Transaction {
    private String id;

    private List<ChangeEvent> events;

    private boolean hasDataChangeEvent;

    public Transaction(String id) {
        this.id = id;
        this.events = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public List<ChangeEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public void addEvent(ChangeEvent event) {
        if (event instanceof DataChangeEvent) {
            hasDataChangeEvent = true;
        }
        events.add(event);
    }

    public boolean hasDataChangeEvent() {
        return hasDataChangeEvent;
    }

    /** One self-contained unit that needs no assembly: a single event dispatched on its own. */
    public static Transaction of(ChangeEvent event) {
        Transaction transaction = new Transaction(null);
        transaction.addEvent(event);
        return transaction;
    }
}
