package io.opendrs.engine.replay.mapping;

/** Maps a single source table onto a target table. */
public record TableMapping(String sourceSchema, String sourceTable, String targetSchema, String targetTable) {
}
