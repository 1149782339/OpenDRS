package io.opendrs.engine.replay.sql;

/**
 * Identifier Rule
 *
 */
public class IdentifierRule {
    /**
     * delimiter
     *
     */
    private final String delimiter;
    /**
     * quote string
     *
     */
    private final String quoteString;

    /**
     * IdentifierRule
     *
     * @param delimiter   delimiter
     * @param quoteString quote string
     */
    public IdentifierRule(String delimiter, String quoteString) {
        this.delimiter = delimiter;
        this.quoteString = quoteString;
    }

    /**
     * Get delimiter string
     *
     * @return delimiter
     */
    public String getDelimiter() {
        return delimiter;
    }

    /**
     * Get quote string
     *
     * @return quote string
     */
    public String getQuoteString() {
        return quoteString;
    }
}
