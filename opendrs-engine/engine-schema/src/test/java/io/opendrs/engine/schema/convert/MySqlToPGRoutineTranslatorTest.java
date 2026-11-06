package io.opendrs.engine.schema.convert;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.schema.convert.converters.MySqlToPGConverter;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@code CREATE FUNCTION}/{@code CREATE PROCEDURE} translation (MySQL → PL/pgSQL).
 */
class MySqlToPGRoutineTranslatorTest {

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

    @Test
    void translatesSimpleCaseWithSubject() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION grade(score INT) RETURNS VARCHAR(8) BEGIN "
                        + "DECLARE g VARCHAR(8); "
                        + "CASE score "
                        + "WHEN 100 THEN SET g = 'A'; "
                        + "WHEN 90 THEN SET g = 'B'; "
                        + "ELSE SET g = 'C'; "
                        + "END CASE; "
                        + "RETURN g; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("CASE score");
        assertThat(stmt).contains("WHEN 100 THEN");
        assertThat(stmt).contains("g := 'A';");
        assertThat(stmt).contains("END CASE;");
    }

    @Test
    void translatesSearchedCaseWithoutSubject() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION pick(n INT) RETURNS INT BEGIN "
                        + "DECLARE r INT DEFAULT 0; "
                        + "CASE "
                        + "WHEN n > 0 THEN SET r = 1; "
                        + "WHEN n < 0 THEN SET r = -1; "
                        + "ELSE SET r = 0; "
                        + "END CASE; "
                        + "RETURN r; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        // searched CASE must not invent a subject token between CASE and WHEN
        assertThat(stmt).contains("CASE\n    WHEN n > 0 THEN");
        assertThat(stmt).doesNotContain("CASE n");
        assertThat(stmt).contains("r := 1;");
        assertThat(stmt).contains("END CASE;");
    }

    @Test
    void translatesSelectIntoVariables() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION lookup_name(pid INT) RETURNS VARCHAR(64) BEGIN "
                        + "DECLARE n VARCHAR(64); "
                        + "SELECT name INTO n FROM people WHERE id = pid; "
                        + "RETURN n; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("SELECT name INTO n FROM people WHERE id = pid;");
        assertThat(stmt).contains("RETURN n;");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("SELECT INTO"));
    }

    @Test
    void selectIntoUserVariableFails() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION bad() RETURNS INT BEGIN "
                        + "SELECT 1 INTO @x; "
                        + "RETURN 1; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.FAILED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("user variable @x");
        assertThat(result.getErrors()).contains("user variable");
    }

    @Test
    void translatesSignalToRaise() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION must_positive(n INT) RETURNS INT BEGIN "
                        + "IF n <= 0 THEN "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'n must be > 0'; "
                        + "END IF; "
                        + "RETURN n; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("RAISE EXCEPTION 'n must be > 0' USING ERRCODE = '45000';");
        assertThat(stmt).doesNotContain("SIGNAL ");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("SIGNAL"));
    }

    @Test
    void translatesBareResignalToRaise() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION rethrow() RETURNS INT BEGIN "
                        + "RESIGNAL; "
                        + "RETURN 1; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("RAISE;");
        assertThat(stmt).doesNotContain("RESIGNAL");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("RESIGNAL"));
    }

    @Test
    void translatesLabeledLoopLeaveAndIterate() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION loop_demo(n INT) RETURNS INT BEGIN "
                        + "DECLARE i INT DEFAULT 0; "
                        + "DECLARE s INT DEFAULT 0; "
                        + "lp: LOOP "
                        + "SET i = i + 1; "
                        + "IF i > n THEN LEAVE lp; END IF; "
                        + "IF i % 2 = 0 THEN ITERATE lp; END IF; "
                        + "SET s = s + i; "
                        + "END LOOP lp; "
                        + "RETURN s; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("<<lp>>");
        assertThat(stmt).contains("LOOP");
        assertThat(stmt).contains("EXIT lp;");
        assertThat(stmt).contains("CONTINUE lp;");
        assertThat(stmt).contains("END LOOP;");
    }

    @Test
    void translatesRepeatUntil() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION repeat_demo(n INT) RETURNS INT BEGIN "
                        + "DECLARE i INT DEFAULT 0; "
                        + "REPEAT SET i = i + 1; UNTIL i >= n END REPEAT; "
                        + "RETURN i; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("LOOP");
        assertThat(stmt).contains("i := i + 1;");
        assertThat(stmt).contains("EXIT WHEN i >= n;");
        assertThat(stmt).contains("END LOOP;");
    }

    @Test
    void rewritesIfAndIsNullInExpressions() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION flags(a INT) RETURNS INT "
                        + "RETURN IF(ISNULL(a), 0, a)");

        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("RETURN (CASE WHEN (a IS NULL) THEN 0 ELSE a END);");
    }

    @Test
    void translatesProcedureWithInOutParamsAndBody() {
        ConversionResult result = identityConverter().convert(
                "CREATE DEFINER=`root`@`localhost` PROCEDURE bump(IN delta INT, OUT total INT) "
                        + "BEGIN "
                        + "DECLARE cur INT DEFAULT 0; "
                        + "SET cur = cur + delta; "
                        + "SET total = cur; "
                        + "END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("CREATE PROCEDURE \"bump\"(IN delta int, OUT total int) AS $$");
        assertThat(stmt).doesNotContain("RETURNS");
        assertThat(stmt).contains("DECLARE\n    cur int := 0;");
        assertThat(stmt).contains("cur := cur + delta;");
        assertThat(stmt).contains("total := cur;");
        assertThat(stmt).contains("$$ LANGUAGE plpgsql;");
        assertThat(stmt).doesNotContain("DEFINER");
        assertThat(result.getInfos()).anyMatch(m -> m.contains("DEFINER"));
    }

    @Test
    void translatesProcedureInoutAndDefaultInDirection() {
        ConversionResult result = identityConverter().convert(
                "CREATE PROCEDURE swap(INOUT a INT, b INT) "
                        + "BEGIN "
                        + "DECLARE tmp INT; "
                        + "SET tmp = a; SET a = b; SET b = tmp; "
                        + "END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        // omitted MySQL direction becomes explicit IN
        assertThat(stmt).contains("CREATE PROCEDURE \"swap\"(INOUT a int, IN b int) AS $$");
        assertThat(stmt).contains("tmp := a;");
        assertThat(stmt).contains("a := b;");
        assertThat(stmt).contains("b := tmp;");
    }

    @Test
    void emitsCommentOnProcedureForRoutineCommentOption() {
        ConversionResult result = identityConverter().convert(
                "CREATE PROCEDURE note_proc() COMMENT 'hello' BEGIN SELECT 1; END");

        assertThat(result.getStatements()).hasSize(2);
        // bare SELECT becomes OPEN __opendrs_rs FOR … and injects INOUT REFCURSOR
        assertThat(result.getStatements().get(0))
                .contains("CREATE PROCEDURE \"note_proc\"(INOUT __opendrs_rs REFCURSOR) AS $$");
        assertThat(result.getStatements().get(0)).contains("OPEN __opendrs_rs FOR SELECT 1;");
        assertThat(result.getStatements().get(1))
                .isEqualTo("COMMENT ON PROCEDURE \"note_proc\" IS 'hello';");
    }

    @Test
    void translatesProcedureSelectIntoAndSignal() {
        ConversionResult result = identityConverter().convert(
                "CREATE PROCEDURE lookup(IN pid INT, OUT n VARCHAR(64)) BEGIN "
                        + "SELECT name INTO n FROM people WHERE id = pid; "
                        + "IF n IS NULL THEN "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'missing'; "
                        + "END IF; "
                        + "END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("CREATE PROCEDURE \"lookup\"(IN pid int, OUT n VARCHAR(64)) AS $$");
        assertThat(stmt).contains("SELECT name INTO n FROM people WHERE id = pid;");
        assertThat(stmt).contains("RAISE EXCEPTION 'missing' USING ERRCODE = '45000';");
    }



    @Test
    void mapsSqlSecurityDefinerOnFunction() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION secured(a INT) RETURNS INT "
                        + "SQL SECURITY DEFINER "
                        + "RETURN a");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("CREATE FUNCTION \"secured\"(a int) RETURNS int SECURITY DEFINER AS $$");
        assertThat(stmt).doesNotContain("SQL SECURITY");
        assertThat(result.getInfos()).noneMatch(m -> m.contains("SQL SECURITY"));
    }

    @Test
    void mapsSqlSecurityInvokerOnProcedure() {
        ConversionResult result = identityConverter().convert(
                "CREATE PROCEDURE secured_proc(IN a INT) "
                        + "SQL SECURITY INVOKER "
                        + "BEGIN SET a = a + 1; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("CREATE PROCEDURE \"secured_proc\"(IN a int) SECURITY INVOKER AS $$");
        assertThat(stmt).doesNotContain("SQL SECURITY");
    }

    @Test
    void rewritesLocateDateFormatConcatSubstringAndLastInsertId() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION expr_demo(s VARCHAR(64), d DATETIME) RETURNS VARCHAR(64) BEGIN "
                        + "DECLARE outv VARCHAR(64); "
                        + "SET outv = CONCAT(SUBSTRING(s, 1, 3), DATE_FORMAT(d, '%Y-%m-%d')); "
                        + "IF LOCATE('x', s) > 0 THEN SET outv = CONCAT(outv, 'x'); END IF; "
                        + "SET outv = IFNULL(outv, LAST_INSERT_ID()); "
                        + "RETURN outv; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("CONCAT(SUBSTRING(s, 1, 3), to_char(d, 'YYYY-MM-DD'))");
        assertThat(stmt).contains("strpos(s, 'x')");
        assertThat(stmt).contains("COALESCE(outv, lastval())");
        assertThat(stmt).doesNotContain("DATE_FORMAT(");
        assertThat(stmt).doesNotContain("LOCATE(");
        assertThat(stmt).doesNotContain("LAST_INSERT_ID()");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("DATE_FORMAT"));
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("LAST_INSERT_ID"));
    }

    @Test
    void rewritesGroupConcatWithOrderByAndReportsRowCount() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION agg_demo() RETURNS VARCHAR(255) BEGIN "
                        + "DECLARE s VARCHAR(255); "
                        + "SELECT GROUP_CONCAT(name ORDER BY name SEPARATOR ';') INTO s FROM people; "
                        + "IF ROW_COUNT() = 0 THEN SET s = ''; END IF; "
                        + "RETURN s; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("string_agg(name::text, ';' ORDER BY name)");
        assertThat(stmt).contains("ROW_COUNT()");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("GROUP_CONCAT"));
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("ROW_COUNT"));
    }

    @Test
    void keepsNowInExpressions() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION now_demo() RETURNS DATETIME RETURN NOW()");

        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("RETURN NOW();");
    }


    @Test
    void rewritesInsertUpdateDeleteCallAndDoInProcedure() {
        ConversionResult result = identityConverter().convert(
                "CREATE PROCEDURE mutate(IN pid INT, IN nm VARCHAR(64)) BEGIN "
                        + "INSERT INTO people(id, name) VALUES (pid, IFNULL(nm, 'x')); "
                        + "UPDATE people SET name = CONCAT(nm, '!') WHERE id = pid; "
                        + "DELETE FROM people WHERE id = pid; "
                        + "CALL other_proc(IFNULL(pid, 0)); "
                        + "DO IFNULL(pid, 0); "
                        + "END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("INSERT INTO people(id, name) VALUES (pid, COALESCE(nm, 'x'));");
        assertThat(stmt).contains("UPDATE people SET name = CONCAT(nm, '!') WHERE id = pid;");
        assertThat(stmt).contains("DELETE FROM people WHERE id = pid;");
        assertThat(stmt).contains("CALL other_proc(COALESCE(pid, 0));");
        assertThat(stmt).contains("PERFORM COALESCE(pid, 0);");
        assertThat(stmt).doesNotContain("DO IFNULL");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("INSERT re-emitted"));
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("UPDATE re-emitted"));
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("DELETE re-emitted"));
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("CALL re-emitted"));
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("DO mapped to PERFORM"));
    }

    @Test
    void mapsProcedureBareSelectToRefcursorOpen() {
        ConversionResult result = identityConverter().convert(
                "CREATE PROCEDURE list_people(IN pid INT) BEGIN "
                        + "SELECT id, IFNULL(name, '') FROM people WHERE id = pid; "
                        + "END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains(
                "CREATE PROCEDURE \"list_people\"(IN pid int, INOUT __opendrs_rs REFCURSOR) AS $$");
        assertThat(stmt).contains(
                "OPEN __opendrs_rs FOR SELECT id, COALESCE(name, '') FROM people WHERE id = pid;");
        assertThat(stmt).doesNotContain("RETURN QUERY");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("REFCURSOR"));
    }

    @Test
    void mapsMultipleProcedureBareSelectsWithWarning() {
        ConversionResult result = identityConverter().convert(
                "CREATE PROCEDURE multi_rs() BEGIN "
                        + "SELECT 1; "
                        + "SELECT 2; "
                        + "END");

        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("INOUT __opendrs_rs REFCURSOR");
        assertThat(stmt).contains("OPEN __opendrs_rs FOR SELECT 1;");
        assertThat(stmt).contains("OPEN __opendrs_rs FOR SELECT 2;");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("multiple procedure result-set"));
    }

    @Test
    void failsBareSelectInsideFunction() {
        ConversionResult result = identityConverter().convert(
                "CREATE FUNCTION bad_rs() RETURNS INT BEGIN SELECT 1; RETURN 1; END");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.FAILED);
        String stmt = String.join("\n", result.getStatements());
        assertThat(stmt).contains("SELECT result set in FUNCTION");
        assertThat(stmt).doesNotContain("OPEN __opendrs_rs");
        assertThat(result.getErrors()).contains("SELECT result set");
    }

}
