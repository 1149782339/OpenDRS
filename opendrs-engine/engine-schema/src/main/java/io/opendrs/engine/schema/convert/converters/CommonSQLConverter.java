package io.opendrs.engine.schema.convert.converters;

import io.opendrs.engine.schema.convert.ConversionConfiguration;

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
