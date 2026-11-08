package io.opendrs.engine.datacheck.type.serialize;

/**
 * One row's output buffers. Create it once per row loop (or per row) and reuse it: the two sides
 * of every column are appended here and hashed or compared when the row is done.
 *
 * <p>Row scoped on purpose — unlike {@link SerializeConfiguration}, which is table scoped and can
 * be shared, this object is mutable and must not be shared between threads.
 */
public final class SerializeContext {

    private final StringBuilder source = new StringBuilder();
    private final StringBuilder target = new StringBuilder();

    public void clear() {
        source.setLength(0);
        target.setLength(0);
    }

    public StringBuilder source() {
        return source;
    }

    public StringBuilder target() {
        return target;
    }

    public String sourceText() {
        return source.toString();
    }

    public String targetText() {
        return target.toString();
    }
}
