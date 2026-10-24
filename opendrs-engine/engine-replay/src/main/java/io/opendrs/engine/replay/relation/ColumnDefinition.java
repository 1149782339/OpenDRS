package io.opendrs.engine.replay.relation;

import io.opendrs.engine.replay.sql.ExpressibleObject;
import io.opendrs.common.util.StringUtil;
import io.opendrs.engine.replay.sql.ExpressionBuilder;
import io.opendrs.engine.replay.sql.IdentifierRule;

import java.util.Optional;

/**
 * Column definition
 *
 */
public class ColumnDefinition implements ExpressibleObject {
    /**
     * Column name
     */
    private String name;
    /**
     * Type name
     */
    private String typeName;
    /**
     * Jdbc type
     */
    private int jdbcType;
    /**
     * Column length
     */
    private int length;
    /**
     * Scale
     */
    private Optional<Integer> scale;
    /**
     * Is generated column
     */
    private boolean isGenerated;
    /**
     * Column position
     */
    private int position;

    /**
     * Get column name
     *
     * @return column name
     */
    public String getName() {
        return name;
    }

    /**
     * Get type name
     *
     * @return type name
     */
    public String getTypeName() {
        return typeName;
    }

    /**
     * Get jdbc type
     *
     * @return jdbc type
     */
    public int getJdbcType() {
        return jdbcType;
    }

    /**
     * Get column length
     *
     * @return column length
     */
    public int getLength() {
        return length;
    }

    /**
     * Get column scale
     *
     * @return column scale
     */
    public Optional<Integer> getScale() {
        return scale;
    }

    /**
     * Get if column is generated
     *
     * @return column scale
     */
    public boolean isGenerated() {
        return isGenerated;
    }

    /**
     * Get column position index
     *
     * @return column position index
     */
    public int getPosition() {
        return position;
    }

    /**
     * Append to expression builder, column should be quoted with the quoteString
     *
     * @param builder expression builder {@link ExpressionBuilder}
     */
    @Override
    public void append(ExpressionBuilder builder) {
        final IdentifierRule rule = builder.getIdentifierRule();
        final String quoteString = rule.getQuoteString();
        builder.append(StringUtil.quote(name, quoteString));
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Get Column Definition builder {@link Builder} to build Column Definition {@link ColumnDefinition}
     *
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Column Definition builder to build Column Definition {@link ColumnDefinition}
     *
     */
    public static class Builder {
        /**
         * Column name
         */
        private String name;
        /**
         * Type name
         */
        private String typeName;
        /**
         * Jdbc type
         */
        private int jdbcType;
        /**
         * Column length
         */
        private int length;
        /**
         * Scale
         */
        private Optional<Integer> scale;
        /**
         * Is generated column
         */
        private boolean isGenerated;
        /**
         * Column position
         */
        private int position;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder typeName(String typeName) {
            this.typeName = typeName;
            return this;
        }

        public Builder jdbcType(int jdbcType) {
            this.jdbcType = jdbcType;
            return this;
        }

        public Builder length(int length) {
            this.length = length;
            return this;
        }

        public Builder scale(Optional<Integer> scale) {
            this.scale = scale;
            return this;
        }


        public Builder isGenerated(boolean isGenerated) {
            this.isGenerated = isGenerated;
            return this;
        }

        public Builder position(int position) {
            this.position = position;
            return this;
        }

        public ColumnDefinition build() {
            ColumnDefinition columnDefinition = new ColumnDefinition();
            columnDefinition.name = name;
            columnDefinition.typeName = typeName;
            columnDefinition.jdbcType = jdbcType;
            columnDefinition.length = length;
            columnDefinition.scale = scale;
            columnDefinition.isGenerated = isGenerated;
            columnDefinition.position = position;
            return columnDefinition;
        }
    }

}
