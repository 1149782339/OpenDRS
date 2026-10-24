package io.opendrs.engine.replay.metrics;

/**
 * Which physical direction of a task a pipeline carries. A one-way task owns a single
 * {@link #FORWARD} pipeline; a bidirectional task adds a {@link #REVERSE} one with the
 * endpoints swapped. Metrics are tagged with {@link #tag()} so the monitor can show the
 * two directions separately.
 */
public enum ReplayDirection {

    /** The task's declared direction: writes land on the target endpoint. */
    FORWARD("forward"),

    /** The swapped direction of a bidirectional task: writes land on the source endpoint. */
    REVERSE("reverse");

    private final String tag;

    ReplayDirection(String tag) {
        this.tag = tag;
    }

    /** The value metrics carry as the {@code direction} tag. */
    public String tag() {
        return tag;
    }
}
