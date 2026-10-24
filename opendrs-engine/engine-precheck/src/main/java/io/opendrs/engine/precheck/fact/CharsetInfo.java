package io.opendrs.engine.precheck.fact;

/** The endpoint's default character set: {@code character_set_server} / {@code server_encoding}. */
public record CharsetInfo(String charset, String collation) {

    public String display() {
        if (charset == null || charset.isBlank()) {
            return "未知";
        }
        return collation == null || collation.isBlank() ? charset : charset + " / " + collation;
    }
}
