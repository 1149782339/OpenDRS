package io.opendrs.engine.jdbc.deferred;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MySqlObjectCatalogTest {

    private static final String ORDERS = """
            CREATE TABLE `orders` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `user_id` bigint NOT NULL,
              `code` varchar(64) NOT NULL,
              `note` text,
              `g` point NOT NULL /*!80003 SRID 0 */,
              PRIMARY KEY (`id`),
              UNIQUE KEY `uk_code` (`code`),
              KEY `idx_user_code` (`user_id`,`code`(10) DESC),
              KEY `idx_fn` ((lower(`code`))),
              FULLTEXT KEY `ft_note` (`note`) /*!50100 WITH PARSER `ngram` */ ,
              SPATIAL KEY `sp_g` (`g`),
              KEY `idx``odd` (`code`) COMMENT 'x' /*!80000 INVISIBLE */,
              CONSTRAINT `fk_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
              CONSTRAINT `fk_other_db` FOREIGN KEY (`user_id`) REFERENCES `crm`.`accounts` (`id`),
              CONSTRAINT `chk_id` CHECK ((`id` > 0))
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""";

    @Test
    void keepsEveryKeyDefinitionVerbatim() {
        List<DeferredObject> objects = MySqlObjectCatalog.parse(
                "shop", "orders", ORDERS, List.of(), Set.of("shop.orders", "shop.users"));

        assertThat(objects).extracting(DeferredObject::name).containsExactly(
                "uk_code", "idx_user_code", "idx_fn", "ft_note", "sp_g", "idx`odd", "fk_user", "fk_other_db");
        assertThat(byName(objects, "uk_code").type()).isEqualTo(DeferredObjectType.UNIQUE);
        assertThat(byName(objects, "idx_user_code").createDdl())
                .isEqualTo("ALTER TABLE `shop`.`orders` ADD KEY `idx_user_code` (`user_id`,`code`(10) DESC)");
        assertThat(byName(objects, "idx_fn").createDdl()).endsWith("ADD KEY `idx_fn` ((lower(`code`)))");
        assertThat(byName(objects, "ft_note").createDdl())
                .endsWith("ADD FULLTEXT KEY `ft_note` (`note`) /*!50100 WITH PARSER `ngram` */");
        assertThat(byName(objects, "sp_g").createDdl()).endsWith("ADD SPATIAL KEY `sp_g` (`g`)");
        assertThat(byName(objects, "idx`odd").dropDdl()).isEqualTo("ALTER TABLE `shop`.`orders` DROP INDEX `idx``odd`");
        assertThat(byName(objects, "fk_user").createDdl()).isEqualTo(
                "ALTER TABLE `shop`.`orders` ADD CONSTRAINT `fk_user` FOREIGN KEY (`user_id`)"
                        + " REFERENCES `shop`.`users` (`id`) ON DELETE CASCADE");
        assertThat(byName(objects, "fk_other_db").createDdl()).contains("REFERENCES `crm`.`accounts` (`id`)");
        assertThat(byName(objects, "fk_user").dropDdl())
                .isEqualTo("ALTER TABLE `shop`.`orders` DROP FOREIGN KEY `fk_user`");
    }

    @Test
    void tablesWithoutPrimaryKeyKeepTheirUniqueKeys() {
        String ddl = """
                CREATE TABLE `logs` (
                  `k` varchar(10) NOT NULL,
                  `v` int,
                  UNIQUE KEY `uk_k` (`k`),
                  KEY `idx_v` (`v`)
                ) ENGINE=InnoDB""";
        List<DeferredObject> objects = MySqlObjectCatalog.parse("shop", "logs", ddl, List.of(), Set.of("shop.logs"));
        assertThat(objects).extracting(DeferredObject::name).containsExactly("idx_v");
    }

    @Test
    void indexesAnOutsideForeignKeyNeedsStayInPlace() {
        String ddl = """
                CREATE TABLE `users` (
                  `id` bigint NOT NULL,
                  `email` varchar(64) NOT NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_email` (`email`),
                  KEY `idx_email_id` (`email`,`id`)
                ) ENGINE=InnoDB""";
        List<MySqlObjectCatalog.ReferencingRow> referencing = List.of(
                new MySqlObjectCatalog.ReferencingRow("crm", "contacts", "fk_contact_email", "email"),
                new MySqlObjectCatalog.ReferencingRow("shop", "orders", "fk_user", "id"));
        List<DeferredObject> objects = MySqlObjectCatalog.parse(
                "shop", "users", ddl, referencing, Set.of("shop.users", "shop.orders"));
        assertThat(objects).isEmpty();
    }

    @Test
    void dropOrderPutsForeignKeysFirstAndBuildOrderLast() {
        List<DeferredObject> objects = new ArrayList<>(MySqlObjectCatalog.parse(
                "shop", "orders", ORDERS, List.of(), Set.of("shop.orders")));
        objects.sort(DeferredObject.DROP_ORDER);
        assertThat(objects.getFirst().type()).isEqualTo(DeferredObjectType.FOREIGN_KEY);
        assertThat(objects.getLast().type()).isEqualTo(DeferredObjectType.INDEX);

        objects.sort(DeferredObject.BUILD_ORDER);
        assertThat(objects).extracting(DeferredObject::type).containsExactly(
                DeferredObjectType.INDEX, DeferredObjectType.INDEX, DeferredObjectType.INDEX,
                DeferredObjectType.INDEX, DeferredObjectType.INDEX,
                DeferredObjectType.UNIQUE,
                DeferredObjectType.FOREIGN_KEY, DeferredObjectType.FOREIGN_KEY);
    }

    @Test
    void mergesPlainIndexesOfOneTableButNotFulltext() {
        MySqlObjectCatalog catalog = new MySqlObjectCatalog();
        List<DeferredObject> objects = MySqlObjectCatalog.parse(
                "shop", "orders", ORDERS, List.of(), Set.of("shop.orders"));
        List<DeferredObject> plain = List.of(byName(objects, "idx_user_code"), byName(objects, "idx_fn"));
        assertThat(catalog.mergedCreate(plain)).contains(
                "ALTER TABLE `shop`.`orders` ADD KEY `idx_user_code` (`user_id`,`code`(10) DESC),"
                        + " ADD KEY `idx_fn` ((lower(`code`)))");
        assertThat(catalog.mergedCreate(List.of(byName(objects, "idx_fn"), byName(objects, "ft_note"))))
                .isEqualTo(Optional.empty());
        assertThat(catalog.mergedCreate(List.of(byName(objects, "idx_fn")))).isEmpty();
    }

    private static DeferredObject byName(List<DeferredObject> objects, String name) {
        return objects.stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow();
    }
}
