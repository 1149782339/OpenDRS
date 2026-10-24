package io.opendrs.engine.replay.event;

import java.util.Objects;

/**
 * Operation equivalent to #io.debezium.data.Envelope.Operation
 *
 */
public enum Operation {
    // The Upsert up is dummy, because debezium only has four operations
    // in DataChangeEvent,which are READ,CREATE,UPDATE,DELETE,
    // it is only used when duplicate key errors need to be handled.
    UPSERT("dummy"),
    READ("r"),
    CREATE("c"),
    UPDATE("u"),
    DELETE("d");
    private final String op;

    Operation(String op) {
        this.op = op;
    }

    /**
     * Get operation from string
     *
     * @param op Operation
     * @return operation
     */
    public static Operation fromString(String op) {
        for (Operation operation : Operation.values()) {
            if (Objects.equals(op, operation.op)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("\"" + op + "\" is not a valid operation");
    }
}
