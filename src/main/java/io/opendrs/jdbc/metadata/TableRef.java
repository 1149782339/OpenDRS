package io.opendrs.jdbc.metadata;

/** Coordinate of a table (schema + name). */
public record TableRef(String schema, String table) {
}
