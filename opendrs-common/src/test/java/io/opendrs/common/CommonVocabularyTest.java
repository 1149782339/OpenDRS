package io.opendrs.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;
import io.opendrs.common.util.StringUtil;
import org.junit.jupiter.api.Test;

class CommonVocabularyTest {

    @Test
    void tableIdTwoPartCoordinate() {
        TableId id = TableId.of("myschema", "mytable");
        assertThat(id.getSchema()).isEqualTo("myschema");
        assertThat(id.getTable()).isEqualTo("mytable");
        assertThat(id.getCatalog()).isNull();
        assertThat(id.identifier()).isEqualTo("myschema.mytable");
        assertThat(id.toString()).isEqualTo("myschema.mytable");
    }

    @Test
    void tableIdEqualityIgnoresToStringOnlyDifferences() {
        assertThat(TableId.of("s", "t")).isEqualTo(TableId.of("s", "t"));
        assertThat(TableId.of("s", "t")).isNotEqualTo(TableId.of(null, "s.t"));
        assertThat(TableId.of("s", "t").hashCode()).isEqualTo(TableId.of("s", "t").hashCode());
    }

    @Test
    void tableIdToQuotedUsesSameQuoteChar() {
        TableId quoted = TableId.of("my schema", "my table").toQuoted('"');
        assertThat(quoted.identifier()).isEqualTo("\"my schema\".\"my table\"");
    }

    @Test
    void stringUtilQuoteUnquoteRoundTrip() {
        assertThat(StringUtil.quote("abc", '"')).isEqualTo("\"abc\"");
        assertThat(StringUtil.unquote("\"abc\"", '"')).isEqualTo("abc");
        assertThat(StringUtil.unquote("`abc`", '`')).isEqualTo("abc");
        assertThat(StringUtil.isQuoted("`abc`")).isTrue();
        assertThat(StringUtil.isQuoted("abc")).isFalse();
        assertThat(StringUtil.isEmpty(null)).isTrue();
    }

    @Test
    void databaseTypeNamesAreThePersistedContract() {
        assertThat(DatabaseType.MYSQL.name()).isEqualTo("MYSQL");
        assertThat(DatabaseType.ORACLE.name()).isEqualTo("ORACLE");
        assertThat(DatabaseType.POSTGRESQL.name()).isEqualTo("POSTGRESQL");
    }
}
