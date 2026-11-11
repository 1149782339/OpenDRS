package io.opendrs.engine.jdbc.deferred;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PostgresObjectCatalogTest {

    @Test
    void capturesIndexesUniqueKeysAndForeignKeys() {
        List<PostgresObjectCatalog.IndexRow> indexes = List.of(
                new PostgresObjectCatalog.IndexRow("idx_user", 11, false,
                        "CREATE INDEX idx_user ON shop.orders USING btree (user_id)", null, null, null),
                new PostgresObjectCatalog.IndexRow("orders_code_key", 12, true,
                        "CREATE UNIQUE INDEX orders_code_key ON shop.orders USING btree (code)",
                        "orders_code_key", "u", "UNIQUE (code)"),
                new PostgresObjectCatalog.IndexRow("uq_lower", 13, true,
                        "CREATE UNIQUE INDEX uq_lower ON shop.orders USING btree (lower(code)) WHERE (code IS NOT NULL)",
                        null, null, null),
                new PostgresObjectCatalog.IndexRow("ex_range", 14, false,
                        "CREATE INDEX ex_range ON shop.orders USING gist (r)", "ex_range", "x", "EXCLUDE ..."));
        List<String[]> foreignKeys = List.<String[]>of(
                new String[]{"orders_user_id_fkey", "FOREIGN KEY (user_id) REFERENCES shop.users(id) ON DELETE CASCADE"},
                new String[]{"fk_legacy", "FOREIGN KEY (x) REFERENCES shop.users(id) NOT VALID"});

        List<DeferredObject> objects = PostgresObjectCatalog.toObjects(
                "shop", "orders", true, indexes, List.of(), foreignKeys, Set.of("shop.orders", "shop.users"));

        assertThat(objects).extracting(DeferredObject::name).containsExactly(
                "idx_user", "orders_code_key", "uq_lower", "orders_user_id_fkey", "fk_legacy");
        DeferredObject constraint = objects.get(1);
        assertThat(constraint.type()).isEqualTo(DeferredObjectType.UNIQUE);
        assertThat(constraint.createDdl())
                .isEqualTo("ALTER TABLE \"shop\".\"orders\" ADD CONSTRAINT \"orders_code_key\" UNIQUE (code)");
        assertThat(constraint.dropDdl())
                .isEqualTo("ALTER TABLE \"shop\".\"orders\" DROP CONSTRAINT \"orders_code_key\"");
        DeferredObject uniqueIndex = objects.get(2);
        assertThat(uniqueIndex.createDdl()).contains("WHERE (code IS NOT NULL)");
        assertThat(uniqueIndex.dropDdl()).isEqualTo("DROP INDEX \"shop\".\"uq_lower\"");
        DeferredObject fk = objects.get(3);
        assertThat(fk.createDdl()).isEqualTo("ALTER TABLE \"shop\".\"orders\" ADD CONSTRAINT \"orders_user_id_fkey\""
                + " FOREIGN KEY (user_id) REFERENCES shop.users(id) ON DELETE CASCADE NOT VALID");
        assertThat(fk.postDdl()).isEqualTo("ALTER TABLE \"shop\".\"orders\" VALIDATE CONSTRAINT \"orders_user_id_fkey\"");
        DeferredObject legacy = objects.get(4);
        assertThat(legacy.createDdl()).endsWith("REFERENCES shop.users(id) NOT VALID").doesNotContain("NOT VALID NOT VALID");
        assertThat(legacy.postDdl()).isNull();
    }

    @Test
    void leavesUniqueKeysOfPkLessTablesAndKeysPinnedByOutsiders() {
        List<PostgresObjectCatalog.IndexRow> indexes = List.of(
                new PostgresObjectCatalog.IndexRow("users_email_key", 21, true,
                        "CREATE UNIQUE INDEX users_email_key ON shop.users USING btree (email)",
                        "users_email_key", "u", "UNIQUE (email)"),
                new PostgresObjectCatalog.IndexRow("users_name_idx", 22, false,
                        "CREATE INDEX users_name_idx ON shop.users USING btree (name)", null, null, null));
        List<PostgresObjectCatalog.ReferencingRow> outsider = List.of(
                new PostgresObjectCatalog.ReferencingRow("crm", "contacts", "fk_email", 21));

        assertThat(PostgresObjectCatalog.toObjects("shop", "users", true, indexes, outsider, List.of(),
                Set.of("shop.users"))).extracting(DeferredObject::name).containsExactly("users_name_idx");
        assertThat(PostgresObjectCatalog.toObjects("shop", "users", false, indexes, List.of(), List.of(),
                Set.of("shop.users"))).extracting(DeferredObject::name).containsExactly("users_name_idx");
        // An in-scope child does not pin the key: it is dropped first and rebuilt after it.
        List<PostgresObjectCatalog.ReferencingRow> insider = List.of(
                new PostgresObjectCatalog.ReferencingRow("shop", "orders", "fk_email", 21));
        assertThat(PostgresObjectCatalog.toObjects("shop", "users", true, indexes, insider, List.of(),
                Set.of("shop.users", "shop.orders"))).hasSize(2);
    }

    @Test
    void quotesIdentifiers() {
        assertThat(PostgresObjectCatalog.quote("we\"ird")).isEqualTo("\"we\"\"ird\"");
        assertThat(new PostgresObjectCatalog().truncateSql(io.opendrs.engine.common.metadata.TableId.of("s", "t")))
                .isEqualTo("TRUNCATE TABLE \"s\".\"t\"");
    }
}
