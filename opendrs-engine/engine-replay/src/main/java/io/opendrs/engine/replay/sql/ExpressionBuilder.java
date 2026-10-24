package io.opendrs.engine.replay.sql;

import io.opendrs.common.metadata.TableId;
import io.opendrs.common.util.StringUtil;
import io.opendrs.engine.replay.annotation.NotThreadSafe;

import java.util.Iterator;
import java.util.List;

/**
 * Expressible Builder
 *
 */
@NotThreadSafe
public class ExpressionBuilder {
    private final StringBuilder sb;
    private final IdentifierRule identifierRule;


    /**
     * Expression Builder
     *
     * @param identifierRule {@link IdentifierRule}
     */
    public ExpressionBuilder(IdentifierRule identifierRule) {
        this.sb = new StringBuilder();
        this.identifierRule = identifierRule;
    }

    /**
     * Append object to the builder
     *
     * @param object
     * @return expression builder {@link ExpressionBuilder}
     */
    public ExpressionBuilder append(Object object) {
        if (object instanceof ExpressibleObject) {
            ((ExpressibleObject) object).append(this);
            return this;
        }
        sb.append(object);
        return this;
    }

    /**
     * Append a table identifier, quoting schema/catalog and table with the identifier rule.
     * Renders {@code schema.table} (or {@code catalog.table}); the table name is always rendered.
     *
     * @param tableId table identifier {@link TableId}
     * @return expression builder {@link ExpressionBuilder}
     */
    public ExpressionBuilder append(TableId tableId) {
        if (tableId.getTable() == null) {
            throw new IllegalArgumentException("table can't be empty");
        }
        final String quoteString = identifierRule.getQuoteString();
        final String delimiter = identifierRule.getDelimiter();
        if (tableId.getSchema() != null) {
            append(StringUtil.quote(tableId.getSchema(), quoteString));
            append(delimiter);
        } else if (tableId.getCatalog() != null) {
            append(StringUtil.quote(tableId.getCatalog(), quoteString));
            append(delimiter);
        }
        append(StringUtil.quote(tableId.getTable(), quoteString));
        return this;
    }

    /**
     * Add the same object multiple times and separate them with a separator
     *
     * @param delimiter delimiter
     * @param value     value
     * @param count     add count
     * @return expression builder {@link ExpressionBuilder}
     */
    public <T> ExpressionBuilder appendMulti(String delimiter, T value, int count) {
        for (int i = 0; i < count; i++) {
            append(value);
            if (i < count - 1) {
                append(delimiter);
            }
        }
        return this;
    }

    /**
     * Add multiple objects and separate them with a separator
     *
     * @param delimiter delimiter
     * @param objects   objects
     * @return expression builder {@link ExpressionBuilder}
     */
    public <T> ExpressionBuilder appendMulti(String delimiter, List<T> objects) {
        for (int i = 0; i < objects.size(); i++) {
            append(objects.get(i));
            if (i < objects.size() - 1) {
                append(delimiter);
            }
        }
        return this;
    }

    /**
     * Get identifier rule
     *
     * @return identifier rule {@link IdentifierRule}
     */
    public IdentifierRule getIdentifierRule() {
        return identifierRule;
    }

    /**
     * Return list expression builder
     *
     * @return list expression builder {@link ListBuilder}
     */
    public ListBuilder listBuilder() {
        return new ListBuilder(this, null, identifierRule.getDelimiter());
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    /**
     * Expression object transformer interface, used to rewrite append method of an expressible object
     *
     */
    @FunctionalInterface
    public interface Transformer<T> {

        /**
         * Apply object to the builder {@link ExpressionBuilder}
         *
         * @param builder expression builder {@link ExpressionBuilder}
         * @param object  object
         */
        void apply(ExpressionBuilder builder, T object);
    }

    /**
     * List Expressible Object Builder
     *
     */
    public static class ListBuilder {
        private final String delimiter;

        private final Transformer transformer;

        private final ExpressionBuilder builder;

        private boolean first = true;

        /**
         * List builder
         *
         * @param builder     expression builder {@link ExpressionBuilder}
         * @param transformer transformer {@link Transformer}
         * @param delimiter   delimiter
         */
        public ListBuilder(ExpressionBuilder builder, Transformer transformer, String delimiter) {
            this.transformer = transformer == null ? ExpressionBuilder::append : transformer;
            this.delimiter = delimiter;
            this.builder = builder;
        }

        /**
         * Return a new builder {@link ListBuilder} with the specified transform {@link Transformer}
         *
         * @param transformer transformer {@link Transformer}
         * @return list Expressible Object Builder {@link ListBuilder}
         */
        public ListBuilder transformedBy(Transformer transformer) {
            return new ListBuilder(builder, transformer, delimiter);
        }

        /**
         * Return a new builder {@link ListBuilder} with the specified delimiter
         *
         * @param delimiter delimiter
         * @return list Expressible Object Builder {@link ListBuilder}
         */
        public ListBuilder delimitedBy(String delimiter) {
            return new ListBuilder(builder, transformer, delimiter);
        }

        /**
         * Traverse the Iterator to append its value to the list builder {@link ListBuilder}
         *
         * @param iterable iterable
         * @return list Expressible Object Builder {@link ListBuilder}
         */
        public ListBuilder of(Iterable<?> iterable) {
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                if (first) {
                    first = false;
                } else {
                    builder.append(delimiter);
                }
                transformer.apply(builder, iterator.next());
            }
            return this;
        }

        /**
         * Append object to the builder
         *
         * @param object
         * @return expression builder {@link ExpressionBuilder}
         */
        public ListBuilder append(Object object) {
            builder.append(object);
            return this;
        }

        /**
         * Traverse the Iterator to append its value to the list builder {@link ListBuilder}
         *
         * @param iterable1 iterable
         * @param iterable2 iterable
         * @return list Expressible Object Builder {@link ListBuilder}
         */
        public ListBuilder of(Iterable<?> iterable1, Iterable<?> iterable2) {
            of(iterable1);
            of(iterable2);
            return this;
        }

        /**
         * Traverse the Iterator to append its value to the list builder {@link ListBuilder}
         *
         * @param iterable1 iterable
         * @param iterable2 iterable
         * @param iterable3 iterable
         * @return list Expressible Object Builder {@link ListBuilder}
         */
        public ListBuilder of(Iterable<?> iterable1, Iterable<?> iterable2, Iterable<?> iterable3) {
            of(iterable1);
            of(iterable2);
            of(iterable3);
            return this;
        }


    }

}
