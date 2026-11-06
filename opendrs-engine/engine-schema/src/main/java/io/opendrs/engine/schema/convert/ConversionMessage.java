package io.opendrs.engine.schema.convert;

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
    private final List<String> errors;

    public ConversionMessage() {
        this.warnings = new ArrayList<>();
        this.infos = new ArrayList<>();
        this.errors = new ArrayList<>();
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
     * Get error messages: constructs that cannot be converted or approximately replaced
     *
     * @return conversion error messages
     */
    public List<String> getFailedMessages() {
        return Collections.unmodifiableList(errors);
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
            case FAILED: {
                errors.add(message);
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
         * Warning level: approximated — most cases covered, semantics not fully equivalent
         */
        WARNING,
        /**
         * Error level: not supported, no approximate replacement exists
         */
        FAILED
    }
}
