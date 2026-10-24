package io.opendrs.engine.precheck;

/**
 * Stable identity and display data of one check. The id is persisted in the result JSON and is what
 * the profile binds, so renaming the title never breaks history.
 */
public record CheckMeta(String id, String title, CheckGroup group, Side side) {
}
