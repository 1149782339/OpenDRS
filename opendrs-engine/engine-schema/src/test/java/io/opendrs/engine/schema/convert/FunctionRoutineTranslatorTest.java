package io.opendrs.engine.schema.convert;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.schema.convert.converters.MySqlToPGConverter;
import org.junit.jupiter.api.Test;

/**
 * Demo coverage for {@code CREATE FUNCTION} translation (MySQL → PL/pgSQL).
 */
class FunctionRoutineTranslatorTest {

    private static MySqlToPGConverter identityConverter() {
        return new MySqlToPGConverter(
                new ConversionConfiguration(tableId -> tableId, column -> column));
    }

    @Test
    void translatesDefinerAndReturnExpression() {
        ConversionResult result = identityConverter().convert(
                "CREATE DEFINER=`root`@`localhost` FUNCTION add_two(a INT, b INT) "
                        + "RETURNS INT DETERMINISTIC RETURN a + b");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        // header keeps its shape; DEFINER and DETERMINISTIC are dropped, type tokens mapped
        assertThat(stmt).contains("CREATE FUNCTION \"add_two\"(a int, b int) RETURNS int AS $$");
        assertThat(stmt).contains("RETURN a + b;");
        assertThat(stmt).contains("$$ LANGUAGE plpgsql;");
        assertThat(stmt).doesNotContain("DEFINER");
        assertThat(stmt).doesNotContain("DETERMINISTIC");
        assertThat(result.getInfos()).anyMatch(m -> m.contains("DEFINER"));
    }

    @Test
    void translatesDeclareSetAndIfElse() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION calc_tax(amount DECIMAL(10,2)) RETURNS DECIMAL(10,2) "
                        + "BEGIN "
                        + "DECLARE tax DECIMAL(10,2) DEFAULT 0; "
                        + "IF amount>100 THEN SET tax = amount*0.2; "
                        + "ELSE SET tax = amount*0.1; "
                        + "END IF; "
                        + "RETURN tax; "
                        + "END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        // DECLARE moves before BEGIN, SET becomes :=, IF/ELSE re-emitted
        assertThat(stmt).contains("DECLARE\n    tax DECIMAL(10,2) := 0;");
        assertThat(stmt).contains("IF amount>100 THEN");
        assertThat(stmt).contains("tax := amount*0.2;");
        assertThat(stmt).contains("ELSE");
        assertThat(stmt).contains("tax := amount*0.1;");
        assertThat(stmt).contains("END IF;");
        assertThat(stmt).contains("RETURN tax;");
        assertThat(stmt).doesNotContain("SET ");
    }

    @Test
    void translatesWhileLoop() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION sum_to(n INT) RETURNS INT "
                        + "BEGIN "
                        + "DECLARE s INT DEFAULT 0; "
                        + "DECLARE i INT DEFAULT 1; "
                        + "WHILE i<=n DO SET s=s+i; SET i=i+1; END WHILE; "
                        + "RETURN s; "
                        + "END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("WHILE i<=n LOOP");
        assertThat(stmt).contains("s := s+i;");
        assertThat(stmt).contains("i := i+1;");
        assertThat(stmt).contains("END LOOP;");
        assertThat(stmt).contains("RETURN s;");
    }

    @Test
    void reportsErrorAndCommentsOutUnsupportedHandler() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION safe_div(a INT, b INT) RETURNS INT "
                        + "BEGIN "
                        + "DECLARE x INT DEFAULT 0; "
                        + "DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET x = 1; "
                        + "RETURN x; "
                        + "END");

        // no approximate replacement exists for handlers: FAILED level
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.FAILED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(result.getErrors()).contains("HANDLER");
        assertThat(stmt).contains("/* FAILED: DECLARE HANDLER");
        // the rest of the body still translates
        assertThat(stmt).contains("x int := 0;");
        assertThat(stmt).contains("RETURN x;");
    }

    @Test
    void commentsOutAggregateFunctionWithError() {
        ConversionResult result = identityConverter().convert(
                "CREATE AGGREGATE FUNCTION agg_sum(a INT) RETURNS INT RETURN a");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.FAILED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).startsWith("/* FAILED: AGGREGATE FUNCTION not supported");
        assertThat(result.getErrors()).contains("AGGREGATE");
    }

    @Test
    void reportsErrorForUserVariablesInSet() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION touch_var(a INT) RETURNS INT BEGIN SET @x = a; RETURN a; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.FAILED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("user variable @x");
        assertThat(stmt).doesNotContain("SET ");
        assertThat(result.getErrors()).contains("user variable");
    }

    @Test
    void emitsCommentOnFunctionForRoutineCommentOption() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION note() RETURNS VARCHAR(64) COMMENT 'hello' RETURN 'x'");

        assertThat(result.getStatements()).hasSize(2);
        assertThat(result.getStatements().get(0))
                .contains("CREATE FUNCTION \"note\"() RETURNS VARCHAR(64) AS $$");
        assertThat(result.getStatements().get(1))
                .isEqualTo("COMMENT ON FUNCTION \"note\" IS 'hello';");
    }

    @Test
    void rewritesIfNullInExpressions() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION coalesce_demo(a INT) RETURNS INT RETURN IFNULL(a, 0)");

        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("RETURN COALESCE(a, 0);");
    }

    @Test
    void translatesCursorLoopIdiomWithNotFoundHandler() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION sum_salaries() RETURNS INT BEGIN "
                        + "DECLARE total INT DEFAULT 0; "
                        + "DECLARE done INT DEFAULT 0; "
                        + "DECLARE cur CURSOR FOR SELECT salary FROM employees; "
                        + "DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1; "
                        + "OPEN cur; "
                        + "WHILE done = 0 DO "
                        + "FETCH cur INTO total; "
                        + "END WHILE; "
                        + "CLOSE cur; "
                        + "RETURN total; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        // bound cursor variable in the DECLARE section
        assertThat(stmt).contains("cur CURSOR FOR SELECT salary FROM employees;");
        // OPEN dropped, FETCH/CLOSE pass through
        assertThat(stmt).doesNotContain("OPEN cur");
        assertThat(stmt).contains("FETCH cur INTO total;");
        assertThat(stmt).contains("CLOSE cur;");
        // NOT FOUND handler idiom injected after FETCH
        assertThat(stmt).contains("IF NOT FOUND THEN done := 1; END IF;");
        // approximation is reported as WARNING, not FAILED
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("NOT FOUND"));
    }

    @Test
    void nonNotFoundHandlersStillFail() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION f() RETURNS INT BEGIN "
                        + "DECLARE cur CURSOR FOR SELECT a FROM t; "
                        + "DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @x = 1; "
                        + "OPEN cur; CLOSE cur; RETURN 1; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.FAILED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("cur CURSOR FOR SELECT a FROM t;");
        assertThat(stmt).contains("/* FAILED: DECLARE HANDLER");
    }
}
