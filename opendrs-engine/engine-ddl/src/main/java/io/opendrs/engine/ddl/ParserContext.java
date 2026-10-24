package io.opendrs.engine.ddl;

import java.util.HashMap;
import java.util.Map;

/**
 * Parser Context, used by {@link io.opendrs.engine.ddl.listener.MySqlToPGParserListener}
 *
 */
public class ParserContext {
    private final Map<String, Object> map = new HashMap<>();
    public <T> T get(String value) {
        return (T) map.get(value);
    }

    public void put(String key, Object value) {
        map.put(key, value);
    }
}
