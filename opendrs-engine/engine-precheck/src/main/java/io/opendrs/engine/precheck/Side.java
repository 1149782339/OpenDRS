package io.opendrs.engine.precheck;

/** Which endpoint a check looks at. {@link #BOTH} needs facts from both sides (e.g. charset). */
public enum Side {
    SOURCE,
    TARGET,
    BOTH
}
