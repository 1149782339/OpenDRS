package io.opendrs.engine.ddl.converters;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.util.Pair;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * SQL converters, loads all SQL converter {@link SQLConverter} and
 * provides an interface to create a converter
 *
 */
public class SQLConverters {

    private static Map<Pair<DatabaseType, DatabaseType>, SQLConverterProvider> SQL_CONVERTER_REGISTRY = new HashMap<>();

    static {
        loadSQLConverters();
    }

    private static void loadSQLConverters() {
        for (SQLConverterProvider converterProvider : ServiceLoader.load(SQLConverterProvider.class)) {
            DatabaseType sourceDatabase = converterProvider.sourceDatabase();
            DatabaseType targetDatabase = converterProvider.targetDatabase();
            SQL_CONVERTER_REGISTRY.put(Pair.of(sourceDatabase, targetDatabase), converterProvider);
        }
    }

    /**
     * Create SQL converter
     *
     * @param sourceDatabase source database {@link DatabaseType}
     * @param targetDatabase target database {@link DatabaseType}
     * @param configuration  conversion configuration {@link ConversionConfiguration}
     * @return SQL converter {@link SQLConverter}
     */
    public static SQLConverter create(
        DatabaseType sourceDatabase,
        DatabaseType targetDatabase,
        ConversionConfiguration configuration
    ) {
        SQLConverterProvider converterProvider = SQL_CONVERTER_REGISTRY.get(Pair.of(sourceDatabase, targetDatabase));
        if (converterProvider == null) {
            throw new ConversionException("sql conversion from \"" + sourceDatabase + "\" to \""
                + targetDatabase + "\" is not supported!");
        }
        return converterProvider.create(configuration);
    }
}
