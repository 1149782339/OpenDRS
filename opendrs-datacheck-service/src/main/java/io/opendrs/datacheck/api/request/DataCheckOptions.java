package io.opendrs.datacheck.api.request;

/** Optional per-task flags; the shape is intentionally open for later knobs. */
public record DataCheckOptions(boolean includeFloat) {

    public static DataCheckOptions defaults() {
        return new DataCheckOptions(false);
    }
}
