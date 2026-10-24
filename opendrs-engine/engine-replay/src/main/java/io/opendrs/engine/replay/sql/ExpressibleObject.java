package io.opendrs.engine.replay.sql;


/**
 * Expressible Object
 *
 */
public interface ExpressibleObject {

    /**
     * Append text to the builder {@link ExpressionBuilder}
     *
     * @param builder {@link ExpressionBuilder}
     */
    void append(ExpressionBuilder builder);
}
