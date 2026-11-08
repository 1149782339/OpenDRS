package io.opendrs.engine.datacheck.type;

public interface LogicalValue {
    LogicalType type();
    void serialize(StringBuilder builder);
}
