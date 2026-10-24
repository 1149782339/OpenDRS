package io.opendrs.common.util;

import java.util.Optional;

/**
 * String / SQL identifier utilities. Pure functions only — no framework dependencies.
 *
 */
public class StringUtil {

    private StringUtil() {
    }

    /**
     * Quote sql identifier with the specified quote
     *
     * @param identifier sql identifier
     * @param quote      quote
     */
    public static String quote(String identifier, String quote) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    /**
     * Quote sql identifier with the specified quote
     *
     * @param identified sql identifier
     * @param quote      quote
     */
    public static String quote(String identified, char quote) {
        if (identified.charAt(0) == quote && identified.charAt(identified.length() - 1) == quote) {
            return identified;
        }
        if (isQuoted(identified)) {
            identified = unquote(identified);
        }
        return quote + identified + quote;
    }

    /**
     * Get the quote character of the SQL identifier if possible
     *
     * @param identifier SQL identifier
     * @return the quote character if possible
     */
    public static Optional<Character> getQuote(String identifier) {
        if (isQuoted(identifier)) {
            return Optional.of(identifier.charAt(0));
        }
        return Optional.empty();
    }

    /**
     * Remove the quote from a SQL identifier with the specified quote
     *
     * @param identifier SQL identifier
     * @param quote      quote
     * @return SQL identifier without the quote
     */
    public static String unquote(String identifier, char quote) {
        if (identifier.charAt(0) == quote && identifier.charAt(identifier.length() - 1) == quote) {
            return identifier.substring(1, identifier.length() - 1);
        }
        return identifier;
    }

    /**
     * Remove the quote from a SQL identifier
     *
     * @param identifier SQL identifier
     * @return SQL identifier without the quote
     */
    public static String unquote(String identifier) {
        Optional<Character> optional = getQuote(identifier);
        if (!optional.isPresent()) {
            return identifier;
        }
        char quota = optional.get();
        identifier.replace(quota + "" + quota, quota + "");
        return identifier.substring(1, identifier.length() - 1);
    }

    /**
     * Judge if a SQL identifier is quoted
     *
     * @param identifier SQL identifier
     * @return if the SQL identifier is quoted
     */
    public static boolean isQuoted(String identifier) {
        if (identifier.length() <= 2) {
            return false;
        }
        if (identifier.charAt(0) == '"' && identifier.charAt(identifier.length() - 1) == '"') {
            return true;
        }
        if (identifier.charAt(0) == '`' && identifier.charAt(identifier.length() - 1) == '`') {
            return true;
        }
        if (identifier.charAt(0) == '\'' && identifier.charAt(identifier.length() - 1) == '\'') {
            return true;
        }
        return false;
    }

    /**
     * Judge if a string object is empty
     *
     * @param str string object
     * @return if the string object is empty
     */
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }
}
