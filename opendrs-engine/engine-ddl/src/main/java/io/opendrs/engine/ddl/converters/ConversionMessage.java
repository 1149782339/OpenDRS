package io.opendrs.engine.ddl.converters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DDL conversion message
 *
 */
public class ConversionMessage {
    private final List<String> warnings;
    private final List<String> infos;

    public ConversionMessage() {
        this.warnings = new ArrayList<>();
        this.infos = new ArrayList<>();
    }

    /**
     * Get conversion info messages
     *
     * @return conversion info messages
     */
    public List<String> getInfoMessages() {
        return Collections.unmodifiableList(infos);
    }

    /**
     * Get warning info messages
     *
     * @return conversion warning messages
     */
    public List<String> getWarningMessages() {
        return Collections.unmodifiableList(warnings);
    }


    /**
     * Put a message with the specified level
     *
     * @param  message conversion message
     * @param  level   message level
     */
    public void report(String message, Level level) {
        switch (level) {
            case INFO: {
                infos.add(message);
            }
            break;
            case WARNING: {
                warnings.add(message);
            }
            break;
            default:
                throw new IllegalArgumentException("message level is not valid");
        }
    }

    /**
     * Message level
     *
     */
    public enum Level {
        /**
         * Info level
         */
        INFO,
        /**
         * Warning level
         */
        WARNING
    }
}
