package io.opendrs.engine.ddl.converters;

/**
 * Base class of  each SQL converter
 *
 */
public abstract class CommonSQLConverter implements SQLConverter {
    protected final ConversionConfiguration configuration;

    public CommonSQLConverter(ConversionConfiguration configuration) {
        this.configuration = configuration;
    }
}
