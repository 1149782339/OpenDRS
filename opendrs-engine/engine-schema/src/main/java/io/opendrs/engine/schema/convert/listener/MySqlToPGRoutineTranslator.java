package io.opendrs.engine.schema.convert.listener;

import io.opendrs.common.util.StringUtil;
import io.opendrs.engine.schema.convert.ConversionMessage;
import io.opendrs.engine.schema.convert.ConversionMessage.Level;
import io.opendrs.engine.schema.convert.parser.mysql.MySqlParser;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * MySQL {@code CREATE FUNCTION}/{@code CREATE PROCEDURE} → PostgreSQL PL/pgSQL translator.
 *
 * <p>Function and procedure share the same body emitter; only the header differs
 * ({@code RETURNS} for functions, {@code IN}/{@code OUT}/{@code INOUT} for procedures,
 * {@code COMMENT ON FUNCTION}/{@code COMMENT ON PROCEDURE}).
 *
 * <p>Hybrid strategy — token rewriting and clause re-emission are complementary, not alternatives:
 * <ul>
 *   <li><b>Token-level rewrite</b> for fragments that stay structurally in place: parameter types
 *       and the {@code RETURNS} type are token-mapped in place via {@link MySqlDataTypeToPGMap}
 *       (drop {@code UNSIGNED}/{@code ZEROFILL}/display-width, map keywords).</li>
 *   <li><b>Clause-level re-emission</b> for the procedural body: MySQL and PL/pgSQL clause shapes
 *       differ structurally (DECLARE moves before BEGIN, {@code SET a = x} → {@code a := x},
 *       {@code ELSEIF} → {@code ELSIF}, {@code REPEAT} → {@code LOOP … EXIT WHEN}), so the body
 *       is regenerated statement-by-statement from the parse tree and wrapped in {@code $$…$$}.</li>
 * </ul>
 * The dividing line is the grammar-tree boundary: anything that merely changes <i>what a token
 * says</i> is a token edit; anything that moves, wraps, or reorders subtrees is regenerated from
 * the subtree.
 *
 * <p>Unsupported constructs are commented out with their original text and reported by severity:
 * <ul>
 *   <li><b>FAILED</b> — completely unsupported, no approximate replacement exists (DECLARE HANDLER
 *       beyond the NOT FOUND idiom, DECLARE CONDITION, user variables, AGGREGATE). The conversion
 *       result is FAILED; the comment preserves the original for manual repair.</li>
 *   <li><b>WARNING</b> — approximated: most cases are covered but semantics are not fully equivalent
 *       (statements passed through untranslated, expressions carried over verbatim, dropped
 *       CHARSET/COLLATE, the NOT FOUND handler idiom mapped to IF NOT FOUND THEN after FETCH).</li>
 * </ul>
 *
 * <p>Cursors: {@code DECLARE cur CURSOR FOR <select>} maps to a PL/pgSQL bound cursor variable in
 * the DECLARE section; {@code FETCH}/{@code CLOSE} pass through; {@code OPEN} is dropped (bound
 * cursors open implicitly); {@code CONTINUE HANDLER FOR NOT FOUND SET done = x} maps to
 * {@code IF NOT FOUND THEN done := x; END IF;} after every FETCH.
 *
 * <p>Scope (demo): scalar functions with a {@code blockStatement} body or a single
 * {@code RETURN} expression; IF/ELSIF/ELSE, WHILE/LOOP, REPEAT, CASE, LEAVE/ITERATE,
 * SET assignments, DECLARE variables. DECLARE CONDITION is reported and replaced with a
 * comment placeholder. Anything else inside the body is passed through untranslated
 * with a warning. Expressions are carried over verbatim apart from a function-name
 * whitelist ({@code IFNULL} → {@code COALESCE}, {@code ISNULL(x)} → {@code (x IS NULL)},
 * {@code IF(c,a,b)} → {@code CASE WHEN c THEN a ELSE b END}, {@code LOCATE} → {@code strpos},
 * {@code DATE_FORMAT} → {@code to_char}, {@code GROUP_CONCAT} → {@code string_agg},
 * {@code LAST_INSERT_ID()} → {@code lastval()}, plus same-name passthroughs such as
 * {@code CONCAT}/{@code SUBSTRING}/{@code NOW}).
 *
 * <p>Also emits {@code SELECT … INTO} for PL/pgSQL targets, {@code SIGNAL}/{@code RESIGNAL}
 * as {@code RAISE}, preserves simple {@code CASE} subjects, and maps
 * {@code SQL SECURITY DEFINER|INVOKER} to PostgreSQL {@code SECURITY DEFINER|INVOKER}.
 */

public class MySqlToPGRoutineTranslator {

    private final TokenStream tokens;
    private final ConversionMessage message;
    /** assignments collected from CONTINUE HANDLER FOR NOT FOUND, injected after each FETCH */
    private final List<String> notFoundAssignments = new ArrayList<>();
    private String commentStatement;

    public MySqlToPGRoutineTranslator(TokenStream tokens, ConversionMessage message) {
        this.tokens = tokens;
        this.message = message;
    }

    /** The {@code COMMENT ON FUNCTION/PROCEDURE …} statement derived from the routine COMMENT option. */
    public String getCommentStatement() {
        return commentStatement;
    }

    /** MySQL {@code CREATE FUNCTION} → {@code CREATE FUNCTION … AS $$…$$ LANGUAGE plpgsql}. */
    public String translateFunction(MySqlParser.CreateFunctionContext ctx) {
        if (ctx.ownerStatement() != null) {
            message.report("DEFINER clause dropped: PostgreSQL has no per-function definer", Level.INFO);
        }
        if (ctx.AGGREGATE() != null) {
            message.report("AGGREGATE FUNCTION has no approximate PL/pgSQL equivalent"
                    + " and cannot be translated: " + abbreviate(tokens.getText(ctx)), Level.FAILED);
            return "/* FAILED: AGGREGATE FUNCTION not supported\n"
                    + "   original: " + abbreviate(tokens.getText(ctx), 500) + " */";
        }

        StringBuilder header = new StringBuilder("CREATE FUNCTION ");
        header.append(quotedFullId(ctx.fullId())).append('(');
        List<MySqlParser.FunctionParameterContext> params = ctx.functionParameter();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                header.append(", ");
            }
            MySqlParser.FunctionParameterContext param = params.get(i);
            // parameter names stay bare: PL/pgSQL folds unquoted identifiers to lowercase, and
            // quoted vs unquoted must match between the signature and the body
            header.append(plainUid(param.uid())).append(' ').append(emitDataType(param.dataType()));
        }
        header.append(") RETURNS ").append(emitDataType(ctx.dataType()));

        String security = collectRoutineOptions(ctx.routineOption(), "FUNCTION", ctx.fullId());

        String body;
        if (ctx.returnStatement() != null) {
            body = "BEGIN\n    RETURN " + expression(ctx.returnStatement().expression()) + ";\nEND;";
        } else {
            body = emitRoutineBody(ctx.routineBody(), "function");
        }
        if (security != null) {
            header.append(' ').append(security);
        }
        return header + " AS $$\n" + body + "$$ LANGUAGE plpgsql;";
    }

    /**
     * MySQL {@code CREATE PROCEDURE} → PostgreSQL {@code CREATE PROCEDURE … AS $$…$$ LANGUAGE plpgsql}.
     * Body emission is shared with {@link #translateFunction}; the header carries {@code IN}/{@code OUT}/
     * {@code INOUT} and has no {@code RETURNS}.
     */
    public String translateProcedure(MySqlParser.CreateProcedureContext ctx) {
        if (ctx.ownerStatement() != null) {
            message.report("DEFINER clause dropped: PostgreSQL has no per-procedure definer", Level.INFO);
        }

        StringBuilder header = new StringBuilder("CREATE PROCEDURE ");
        header.append(quotedFullId(ctx.fullId())).append('(');
        List<MySqlParser.ProcedureParameterContext> params = ctx.procedureParameter();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                header.append(", ");
            }
            MySqlParser.ProcedureParameterContext param = params.get(i);
            // MySQL omits direction → IN; always emit so PG signatures stay explicit
            String direction = param.direction != null ? param.direction.getText().toUpperCase() : "IN";
            header.append(direction).append(' ')
                    .append(plainUid(param.uid())).append(' ')
                    .append(emitDataType(param.dataType()));
        }
        header.append(')');

        String security = collectRoutineOptions(ctx.routineOption(), "PROCEDURE", ctx.fullId());

        String body = emitRoutineBody(ctx.routineBody(), "procedure");
        if (security != null) {
            header.append(' ').append(security);
        }
        return header + " AS $$\n" + body + "$$ LANGUAGE plpgsql;";
    }

    /**
     * Collects {@code COMMENT} into {@link #commentStatement} and returns
     * {@code SECURITY DEFINER}|{@code SECURITY INVOKER} when {@code SQL SECURITY} is present.
     * Other MySQL routine options are dropped with INFO.
     */
    private String collectRoutineOptions(List<MySqlParser.RoutineOptionContext> options,
                                         String kind, MySqlParser.FullIdContext fullId) {
        String security = null;
        for (MySqlParser.RoutineOptionContext option : options) {
            if (option instanceof MySqlParser.RoutineCommentContext) {
                String literal = ((MySqlParser.RoutineCommentContext) option).STRING_LITERAL().getText();
                commentStatement =
                        "COMMENT ON " + kind + " " + quotedFullId(fullId) + " IS " + literal + ";";
            } else if (option instanceof MySqlParser.RoutineSecurityContext sec) {
                security = "SECURITY " + sec.context.getText().toUpperCase();
            } else {
                message.report("routine option '" + tokens.getText(option)
                        + "' dropped (no PostgreSQL equivalent)", Level.INFO);
            }
        }
        return security;
    }

    // ---------------------------------------------------------------------------------------------
    // Body: clause-level re-emission (shared by FUNCTION and PROCEDURE)
    // ---------------------------------------------------------------------------------------------

    private String emitRoutineBody(MySqlParser.RoutineBodyContext body, String kind) {
        if (body != null && body.blockStatement() != null) {
            message.report(kind + " body statements are re-emitted, but expressions are carried over"
                    + " verbatim: verify function/operator semantics (e.g. ||, integer division, date"
                    + " arithmetic) differ between MySQL and PostgreSQL", Level.WARNING);
            notFoundAssignments.clear();
            StringBuilder sb = new StringBuilder();
            emitBlock(body.blockStatement(), sb, 0);
            return sb.toString();
        }
        // Rare shape: body is a single plain SQL statement.
        message.report("single-statement " + kind + " body passed through untranslated", Level.WARNING);
        return "BEGIN\n" + tokens.getText(body) + ";\nEND;";
    }

    /**
     * {@code [label:] BEGIN declares… statements… END [label]} → PL/pgSQL block. MySQL's
     * {@code BEGIN…END} <i>is</i> the block; PL/pgSQL wants the DECLARE section before its BEGIN,
     * so the clauses are re-emitted rather than token-edited.
     */
    private void emitBlock(MySqlParser.BlockStatementContext ctx, StringBuilder sb, int depth) {
        String ind = "    ".repeat(depth);
        if (!ctx.uid().isEmpty()) {
            sb.append(ind).append("<<").append(ctx.uid().get(0).getText()).append(">>\n");
        }
        List<MySqlParser.DeclareVariableContext> vars = ctx.declareVariable();
        boolean hasDeclares = !vars.isEmpty()
                || !ctx.declareCondition().isEmpty()
                || !ctx.declareCursor().isEmpty()
                || !ctx.declareHandler().isEmpty();
        if (hasDeclares) {
            sb.append(ind).append("DECLARE\n");
            for (MySqlParser.DeclareVariableContext decl : vars) {
                String type = emitDataType(decl.dataType());
                String dflt = decl.expression() != null ? " := " + expression(decl.expression()) : "";
                for (MySqlParser.UidContext uid : decl.uidList().uid()) {
                    sb.append(ind).append("    ").append(plainUid(uid)).append(' ')
                            .append(type).append(dflt).append(";\n");
                }
            }
            for (MySqlParser.DeclareConditionContext decl : ctx.declareCondition()) {
                unsupported(decl, sb, depth + 1, "DECLARE CONDITION", Level.FAILED,
                        "no PL/pgSQL equivalent; named conditions cannot be approximated");
                sb.append('\n');
            }
            for (MySqlParser.DeclareCursorContext decl : ctx.declareCursor()) {
                emitDeclareCursor(decl, sb, depth + 1);
            }
            for (MySqlParser.DeclareHandlerContext decl : ctx.declareHandler()) {
                String assignment = tryMapNotFoundHandler(decl);
                if (assignment != null) {
                    notFoundAssignments.add(assignment);
                    message.report("CONTINUE HANDLER FOR NOT FOUND mapped to 'IF NOT FOUND THEN "
                            + assignment + "; END IF;' after each FETCH — approximation: in MySQL"
                            + " it fires on any NOT FOUND condition, here only at FETCH sites",
                            Level.WARNING);
                } else {
                    unsupported(decl, sb, depth + 1, "DECLARE HANDLER", Level.FAILED,
                            "exception handlers have no approximate equivalent; rewrite with EXCEPTION blocks");
                    sb.append('\n');
                }
            }
        }
        sb.append(ind).append("BEGIN\n");
        for (MySqlParser.ProcedureSqlStatementContext stmt : ctx.procedureSqlStatement()) {
            emitProcStatement(stmt, sb, depth + 1);
        }
        sb.append(ind).append("END;\n");
    }

    private void emitProcStatement(MySqlParser.ProcedureSqlStatementContext ctx, StringBuilder sb, int depth) {
        if (ctx.compoundStatement() != null) {
            emitCompound(ctx.compoundStatement(), sb, depth);
            return;
        }
        MySqlParser.SqlStatementContext sql = ctx.sqlStatement();
        MySqlParser.SetStatementContext set =
                sql.administrationStatement() != null ? sql.administrationStatement().setStatement() : null;
        if (set instanceof MySqlParser.SetVariableContext
                || set instanceof MySqlParser.SetNewValueInsideTriggerContext) {
            emitSetAssignments(set, sb, depth);
            return;
        }
        MySqlParser.UtilityStatementContext util = sql.utilityStatement();
        if (util != null && util.signalStatement() != null) {
            emitSignal(util.signalStatement(), sb, depth);
            return;
        }
        if (util != null && util.resignalStatement() != null) {
            emitResignal(util.resignalStatement(), sb, depth);
            return;
        }
        MySqlParser.SelectStatementContext select =
                sql.dmlStatement() != null ? sql.dmlStatement().selectStatement() : null;
        if (select != null && tryEmitSelectInto(select, sb, depth)) {
            return;
        }
        passthrough(sql, sb, depth, "statement");
    }

    /** {@code SET a = x, b := y} → one PL/pgSQL assignment per pair. */
    private void emitSetAssignments(MySqlParser.SetStatementContext set, StringBuilder sb, int depth) {
        String ind = "    ".repeat(depth);
        List<ParseTree> children = set.children;
        for (int i = 0; i < children.size(); i++) {
            ParseTree child = children.get(i);
            boolean isVariable = child instanceof MySqlParser.VariableClauseContext
                    || child instanceof MySqlParser.FullIdContext;
            if (!isVariable) {
                continue;
            }
            String name = tokens.getText((ParserRuleContext) child);
            if (name.startsWith("@")) {
                unsupported((ParserRuleContext) child, sb, depth, "user variable " + name, Level.FAILED,
                        "PL/pgSQL has no session user variables; assignment dropped");
                sb.append('\n');
                continue;
            }
            String value = null;
            for (int j = i + 1; j < children.size(); j++) {
                ParseTree next = children.get(j);
                if (next instanceof MySqlParser.ExpressionContext) {
                    value = expression((ParserRuleContext) next);
                    break;
                }
                if (",".equals(next.getText())) {
                    break;
                }
            }
            sb.append(ind).append(name).append(" := ").append(value).append(";\n");
        }
    }

    /**
     * {@code DECLARE cur CURSOR FOR <select>} → PL/pgSQL bound cursor variable in the DECLARE
     * section. The SELECT itself is carried over verbatim.
     */
    private void emitDeclareCursor(MySqlParser.DeclareCursorContext ctx, StringBuilder sb, int depth) {
        String ind = "    ".repeat(depth);
        String name = plainUid(ctx.uid());
        String select = tokens.getText(ctx.selectStatement());
        message.report("cursor '" + name + "': SELECT statement carried over verbatim;"
                + " verify MySQL-specific SELECT syntax in the PostgreSQL target", Level.WARNING);
        sb.append(ind).append(name).append(" CURSOR FOR ").append(select).append(";\n");
    }

    /**
     * Maps the dominant cursor-loop idiom {@code DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1}
     * to {@code done := 1} injected as {@code IF NOT FOUND THEN done := 1; END IF;} after each
     * FETCH. Returns the assignment text, or {@code null} when the handler does not match the idiom.
     */
    private String tryMapNotFoundHandler(MySqlParser.DeclareHandlerContext handler) {
        if (handler.handlerConditionValue().size() != 1
                || !(handler.handlerConditionValue(0)
                        instanceof MySqlParser.HandlerConditionNotfoundContext)
                || handler.routineBody() == null
                || handler.routineBody().sqlStatement() == null
                || handler.routineBody().sqlStatement().administrationStatement() == null) {
            return null;
        }
        MySqlParser.SetStatementContext set = handler.routineBody().sqlStatement()
                .administrationStatement().setStatement();
        if (!(set instanceof MySqlParser.SetVariableContext)) {
            return null;
        }
        List<ParseTree> children = set.children;
        for (int i = 0; i < children.size(); i++) {
            ParseTree child = children.get(i);
            if (child instanceof MySqlParser.VariableClauseContext
                    || child instanceof MySqlParser.FullIdContext) {
                String name = tokens.getText((ParserRuleContext) child);
                if (name.startsWith("@")) {
                    return null;
                }
                for (int j = i + 1; j < children.size(); j++) {
                    ParseTree next = children.get(j);
                    if (next instanceof MySqlParser.ExpressionContext) {
                        return name + " := " + expression((ParserRuleContext) next);
                    }
                    if (",".equals(next.getText())) {
                        return null;
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** {@code OPEN} is dropped (bound cursors open implicitly); {@code FETCH}/{@code CLOSE} pass through. */
    private void emitCursorStatement(MySqlParser.CursorStatementContext ctx, StringBuilder sb, int depth) {
        String ind = "    ".repeat(depth);
        if (ctx instanceof MySqlParser.OpenCursorContext open) {
            message.report("OPEN " + plainUid(open.uid()) + " dropped: PostgreSQL bound cursor"
                    + " variables open implicitly on first FETCH", Level.INFO);
            return;
        }
        if (ctx instanceof MySqlParser.CloseCursorContext close) {
            sb.append(ind).append("CLOSE ").append(plainUid(close.uid())).append(";\n");
            return;
        }
        MySqlParser.FetchCursorContext fetch = (MySqlParser.FetchCursorContext) ctx;
        String vars = fetch.uidList().uid().stream()
                .map(this::plainUid)
                .collect(java.util.stream.Collectors.joining(", "));
        sb.append(ind).append("FETCH ").append(plainUid(fetch.uid())).append(" INTO ").append(vars)
                .append(";\n");
        for (String assignment : notFoundAssignments) {
            sb.append(ind).append("IF NOT FOUND THEN ").append(assignment).append("; END IF;\n");
        }
    }

    private void emitCompound(MySqlParser.CompoundStatementContext c, StringBuilder sb, int depth) {
        String ind = "    ".repeat(depth);
        if (c.blockStatement() != null) {
            emitBlock(c.blockStatement(), sb, depth);
        } else if (c.ifStatement() != null) {
            MySqlParser.IfStatementContext ifCtx = c.ifStatement();
            sb.append(ind).append("IF ").append(expression(ifCtx.expression())).append(" THEN\n");
            ifCtx.thenStatements.forEach(s -> emitProcStatement(s, sb, depth + 1));
            for (MySqlParser.ElifAlternativeContext elif : ifCtx.elifAlternative()) {
                sb.append(ind).append("ELSIF ").append(expression(elif.expression())).append(" THEN\n");
                elif.procedureSqlStatement().forEach(s -> emitProcStatement(s, sb, depth + 1));
            }
            if (ifCtx.elseStatements != null && !ifCtx.elseStatements.isEmpty()) {
                sb.append(ind).append("ELSE\n");
                ifCtx.elseStatements.forEach(s -> emitProcStatement(s, sb, depth + 1));
            }
            sb.append(ind).append("END IF;\n");
        } else if (c.whileStatement() != null) {
            MySqlParser.WhileStatementContext w = c.whileStatement();
            String label = w.uid().isEmpty() ? null : w.uid().get(0).getText();
            if (label != null) {
                sb.append(ind).append("<<").append(label).append(">> ");
            }
            sb.append(ind).append("WHILE ").append(expression(w.expression())).append(" LOOP\n");
            w.procedureSqlStatement().forEach(s -> emitProcStatement(s, sb, depth + 1));
            sb.append(ind).append("END LOOP;\n");
        } else if (c.loopStatement() != null) {
            MySqlParser.LoopStatementContext loop = c.loopStatement();
            String label = loop.uid().isEmpty() ? null : loop.uid().get(0).getText();
            if (label != null) {
                sb.append(ind).append("<<").append(label).append(">> ");
            }
            sb.append(ind).append("LOOP\n");
            loop.procedureSqlStatement().forEach(s -> emitProcStatement(s, sb, depth + 1));
            sb.append(ind).append("END LOOP;\n");
        } else if (c.repeatStatement() != null) {
            MySqlParser.RepeatStatementContext repeat = c.repeatStatement();
            String label = repeat.uid().isEmpty() ? null : repeat.uid().get(0).getText();
            if (label != null) {
                sb.append(ind).append("<<").append(label).append(">> ");
            }
            sb.append(ind).append("LOOP\n");
            repeat.procedureSqlStatement().forEach(s -> emitProcStatement(s, sb, depth + 1));
            sb.append(ind).append("    EXIT WHEN ").append(expression(repeat.expression())).append(";\n");
            sb.append(ind).append("END LOOP;\n");
        } else if (c.caseStatement() != null) {
            MySqlParser.CaseStatementContext caseCtx = c.caseStatement();
            // Preserve simple-CASE subject (uid / expression); searched CASE has none.
            if (caseCtx.uid() != null) {
                sb.append(ind).append("CASE ").append(plainUid(caseCtx.uid())).append("\n");
            } else if (caseCtx.expression() != null) {
                sb.append(ind).append("CASE ").append(expression(caseCtx.expression())).append("\n");
            } else {
                sb.append(ind).append("CASE\n");
            }
            for (MySqlParser.CaseAlternativeContext alt : caseCtx.caseAlternative()) {
                String when = alt.constant() != null
                        ? tokens.getText(alt.constant())
                        : expression(alt.expression());
                sb.append(ind).append("WHEN ").append(when).append(" THEN\n");
                alt.procedureSqlStatement().forEach(s -> emitProcStatement(s, sb, depth + 1));
            }
            if (!caseCtx.procedureSqlStatement().isEmpty()) {
                sb.append(ind).append("ELSE\n");
                caseCtx.procedureSqlStatement().forEach(s -> emitProcStatement(s, sb, depth + 1));
            }
            sb.append(ind).append("END CASE;\n");
        } else if (c.returnStatement() != null) {
            sb.append(ind).append("RETURN ").append(expression(c.returnStatement().expression())).append(";\n");
        } else if (c.leaveStatement() != null) {
            sb.append(ind).append("EXIT ").append(c.leaveStatement().uid().getText()).append(";\n");
        } else if (c.iterateStatement() != null) {
            sb.append(ind).append("CONTINUE ").append(c.iterateStatement().uid().getText()).append(";\n");
        } else if (c.cursorStatement() != null) {
            emitCursorStatement(c.cursorStatement(), sb, depth);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fragments: token-level rewrite
    // ---------------------------------------------------------------------------------------------

    /**
     * Type fragments that stay in place are token-mapped: known keywords are substituted via
     * {@link MySqlDataTypeToPGMap}, {@code UNSIGNED}/{@code ZEROFILL} and integer display widths
     * are dropped, {@code DECIMAL} keeps its precision. The tree shape is unchanged, so no
     * clause regeneration happens here.
     */
    private String emitDataType(MySqlParser.DataTypeContext ctx) {
        if (ctx instanceof MySqlParser.DimensionDataTypeContext d) {
            int type = d.typeName.getType();
            String name = MySqlDataTypeToPGMap.contains(type)
                    ? MySqlDataTypeToPGMap.get(type)
                    : d.typeName.getText();
            StringBuilder sb = new StringBuilder(name);
            boolean decimalFamily = type == MySqlParser.DECIMAL || type == MySqlParser.DEC
                    || type == MySqlParser.FIXED || type == MySqlParser.NUMERIC
                    || type == MySqlParser.FLOAT || type == MySqlParser.FLOAT4
                    || type == MySqlParser.FLOAT8;
            if (decimalFamily && d.lengthTwoOptionalDimension() != null) {
                sb.append(tokens.getText(d.lengthTwoOptionalDimension()));
            }
            if (type == MySqlParser.DOUBLE) {
                sb.append(" PRECISION");
            }
            return sb.toString();
        }
        if (ctx instanceof MySqlParser.StringDataTypeContext s) {
            StringBuilder sb = new StringBuilder(
                    MySqlDataTypeToPGMap.contains(s.typeName.getType())
                            ? MySqlDataTypeToPGMap.get(s.typeName.getType())
                            : s.typeName.getText());
            if (s.lengthOneDimension() != null) {
                sb.append(tokens.getText(s.lengthOneDimension()));
            }
            if (s.charSet() != null || s.collationName() != null) {
                message.report("CHARSET/COLLATE clause dropped from routine signature;"
                        + " comparison semantics may differ from MySQL", Level.WARNING);
            }
            return sb.toString();
        }
        Token first = ctx.getStart();
        if (MySqlDataTypeToPGMap.contains(first.getType())) {
            return MySqlDataTypeToPGMap.get(first.getType());
        }
        return tokens.getText(ctx);
    }

    /**
     * Expression text is carried over verbatim, apart from a function-name whitelist
     * ({@code IFNULL}, {@code ISNULL}, {@code IF}, {@code LOCATE}, {@code DATE_FORMAT},
     * {@code GROUP_CONCAT}, {@code LAST_INSERT_ID}, {@code ROW_COUNT}; {@code CONCAT}/
     * {@code SUBSTRING}/{@code NOW} keep PostgreSQL-compatible names).
     */
    private String expression(ParserRuleContext ctx) {
        return rewriteExprFunctions(tokens.getText(ctx));
    }

    private String rewriteExprFunctions(String text) {
        String out = text;
        out = replaceNamedCall(out, "IFNULL", args -> "COALESCE(" + args + ")");
        out = replaceNamedCall(out, "ISNULL", args -> "(" + args + " IS NULL)");
        out = replaceNamedCall(out, "IF", args -> {
            java.util.List<String> parts = splitTopLevelArgs(args);
            if (parts.size() != 3) {
                return "IF(" + args + ")";
            }
            return "(CASE WHEN " + parts.get(0) + " THEN " + parts.get(1)
                    + " ELSE " + parts.get(2) + " END)";
        });
        // CONCAT / SUBSTRING / NOW exist in PostgreSQL with compatible call shapes — leave names.
        out = replaceNamedCall(out, "LOCATE", this::rewriteLocate);
        out = replaceNamedCall(out, "DATE_FORMAT", this::rewriteDateFormat);
        out = replaceNamedCall(out, "GROUP_CONCAT", this::rewriteGroupConcat);
        out = replaceNamedCall(out, "LAST_INSERT_ID", this::rewriteLastInsertId);
        out = replaceNamedCall(out, "ROW_COUNT", this::rewriteRowCount);
        return out;
    }

    /** {@code LOCATE(substr, str[, pos])} → {@code strpos} (argument order swapped). */
    private String rewriteLocate(String args) {
        java.util.List<String> parts = splitTopLevelArgs(args);
        if (parts.size() == 2) {
            return "strpos(" + parts.get(1) + ", " + parts.get(0) + ")";
        }
        if (parts.size() == 3) {
            message.report("LOCATE(substr, str, pos) approximated with strpos/substr;"
                    + " verify edge cases around missing matches", Level.WARNING);
            return "(CASE WHEN strpos(substr(" + parts.get(1) + ", " + parts.get(2) + "), "
                    + parts.get(0) + ") = 0 THEN 0 ELSE strpos(substr(" + parts.get(1) + ", "
                    + parts.get(2) + "), " + parts.get(0) + ") + (" + parts.get(2) + ") - 1 END)";
        }
        return "LOCATE(" + args + ")";
    }

    /** {@code DATE_FORMAT(date, fmt)} → {@code to_char(date, mapped_fmt)}. */
    private String rewriteDateFormat(String args) {
        java.util.List<String> parts = splitTopLevelArgs(args);
        if (parts.size() != 2) {
            return "DATE_FORMAT(" + args + ")";
        }
        String fmt = mapMysqlDateFormat(parts.get(1));
        message.report("DATE_FORMAT mapped to to_char; verify MySQL format codes against"
                + " PostgreSQL to_char patterns", Level.WARNING);
        return "to_char(" + parts.get(0) + ", " + fmt + ")";
    }

    /**
     * Maps common MySQL {@code DATE_FORMAT} percent-codes inside a quoted literal to
     * PostgreSQL {@code to_char} patterns. Non-literals are left unchanged.
     */
    private String mapMysqlDateFormat(String fmtArg) {
        String trimmed = fmtArg.trim();
        if (trimmed.length() < 2) {
            return fmtArg;
        }
        char q = trimmed.charAt(0);
        if ((q != '\'' && q != '"') || trimmed.charAt(trimmed.length() - 1) != q) {
            return fmtArg;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1);
        StringBuilder mapped = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '%' && i + 1 < inner.length()) {
                char code = inner.charAt(++i);
                switch (code) {
                    case 'Y' -> mapped.append("YYYY");
                    case 'y' -> mapped.append("YY");
                    case 'm' -> mapped.append("MM");
                    case 'c' -> mapped.append("FMMM");
                    case 'd' -> mapped.append("DD");
                    case 'e' -> mapped.append("FMDD");
                    case 'H' -> mapped.append("HH24");
                    case 'h', 'I' -> mapped.append("HH12");
                    case 'i' -> mapped.append("MI");
                    case 's', 'S' -> mapped.append("SS");
                    case 'f' -> mapped.append("US");
                    case 'T' -> mapped.append("HH24:MI:SS");
                    case 'r' -> mapped.append("HH12:MI:SS AM");
                    case 'p' -> mapped.append("AM");
                    case 'W' -> mapped.append("Day");
                    case 'a' -> mapped.append("Dy");
                    case 'M' -> mapped.append("Month");
                    case 'b' -> mapped.append("Mon");
                    case '%' -> mapped.append('%');
                    default -> mapped.append('%').append(code);
                }
            } else {
                mapped.append(c);
            }
        }
        return q + mapped.toString() + q;
    }

    /**
     * {@code GROUP_CONCAT([DISTINCT] expr [ORDER BY …] [SEPARATOR sep])} →
     * {@code string_agg([DISTINCT] expr::text, sep [ORDER BY …])}.
     */
    private String rewriteGroupConcat(String args) {
        String sep = "','";
        String core = args.trim();
        int sepAt = indexOfKeyword(core, "SEPARATOR");
        if (sepAt >= 0) {
            String after = core.substring(sepAt + "SEPARATOR".length()).trim();
            int consumed = 0;
            if (!after.isEmpty() && (after.charAt(0) == '\'' || after.charAt(0) == '"')) {
                char q = after.charAt(0);
                int close = after.indexOf(q, 1);
                if (close > 0) {
                    sep = after.substring(0, close + 1);
                    consumed = close + 1;
                }
            } else {
                int sp = 0;
                while (sp < after.length() && !Character.isWhitespace(after.charAt(sp))) {
                    sp++;
                }
                sep = after.substring(0, sp);
                consumed = sp;
            }
            core = core.substring(0, sepAt).trim();
            // trailing junk after separator is ignored
            if (consumed == 0) {
                sep = "','";
            }
        }
        boolean distinct = false;
        if (core.regionMatches(true, 0, "DISTINCT ", 0, 9)) {
            distinct = true;
            core = core.substring(9).trim();
        }
        String orderBy = null;
        int orderAt = indexOfKeyword(core, "ORDER");
        if (orderAt >= 0) {
            String afterOrder = core.substring(orderAt + "ORDER".length()).trim();
            if (afterOrder.regionMatches(true, 0, "BY ", 0, 3)
                    || afterOrder.regionMatches(true, 0, "BY\t", 0, 3)) {
                orderBy = afterOrder.substring(2).trim();
                core = core.substring(0, orderAt).trim();
            }
        }
        message.report("GROUP_CONCAT mapped to string_agg — approximation: NULL/empty-group"
                + " and charset semantics may differ", Level.WARNING);
        StringBuilder sb = new StringBuilder("string_agg(");
        if (distinct) {
            sb.append("DISTINCT ");
        }
        sb.append(core).append("::text, ").append(sep);
        if (orderBy != null) {
            sb.append(" ORDER BY ").append(orderBy);
        }
        sb.append(')');
        return sb.toString();
    }

    /** Case-insensitive top-level keyword search (ignores text inside quotes / parentheses). */
    private int indexOfKeyword(String text, String keyword) {
        String upper = text.toUpperCase();
        String needle = keyword.toUpperCase();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i <= text.length() - needle.length(); i++) {
            char c = text.charAt(i);
            if (inSingle) {
                if (c == '\'' && (i + 1 >= text.length() || text.charAt(i + 1) != '\'')) {
                    inSingle = false;
                } else if (c == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (upper.startsWith(needle, i)) {
                boolean leftOk = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
                int end = i + needle.length();
                boolean rightOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
                if (leftOk && rightOk) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String rewriteLastInsertId(String args) {
        if (!args.trim().isEmpty()) {
            message.report("LAST_INSERT_ID(expr) has no PostgreSQL equivalent; left unchanged",
                    Level.WARNING);
            return "LAST_INSERT_ID(" + args + ")";
        }
        message.report("LAST_INSERT_ID() mapped to lastval() — approximation: requires a prior"
                + " nextval() in this session, not MySQL AUTO_INCREMENT semantics", Level.WARNING);
        return "lastval()";
    }

    private String rewriteRowCount(String args) {
        message.report("ROW_COUNT() has no expression-level equivalent in PostgreSQL;"
                + " use GET DIAGNOSTICS … = ROW_COUNT after DML — left unchanged", Level.WARNING);
        return "ROW_COUNT(" + args + ")";
    }

    @FunctionalInterface
    private interface CallRewriter {
        String rewrite(String args);
    }

    /**
     * Replace {@code NAME(…)} calls with balanced parentheses, outer-to-inner safe via left scan.
     */
    private String replaceNamedCall(String text, String name, CallRewriter rewriter) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        String upper = text.toUpperCase();
        String needle = name.toUpperCase() + "(";
        while (i < text.length()) {
            int found = upper.indexOf(needle, i);
            if (found < 0) {
                sb.append(text, i, text.length());
                break;
            }
            // require a word boundary before the name
            if (found > 0 && Character.isLetterOrDigit(text.charAt(found - 1))) {
                sb.append(text, i, found + 1);
                i = found + 1;
                continue;
            }
            int open = found + name.length();
            if (open >= text.length() || text.charAt(open) != '(') {
                sb.append(text, i, found + name.length());
                i = found + name.length();
                continue;
            }
            int close = matchingParen(text, open);
            if (close < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, found);
            sb.append(rewriter.rewrite(text.substring(open + 1, close)));
            i = close + 1;
        }
        return sb.toString();
    }

    private int matchingParen(String text, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private java.util.List<String> splitTopLevelArgs(String args) {
        java.util.List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(args.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(args.substring(start).trim());
        return parts;
    }

    /**
     * {@code SELECT … INTO var[, …]} → PL/pgSQL {@code SELECT … INTO …}. DUMPFILE/OUTFILE are FAILED.
     * Returns {@code true} when the statement was handled (including as unsupported).
     */
    private boolean tryEmitSelectInto(MySqlParser.SelectStatementContext select, StringBuilder sb, int depth) {
        if (!(select instanceof MySqlParser.SimpleSelectContext simple)) {
            return false;
        }
        MySqlParser.QuerySpecificationContext qs = simple.querySpecification();
        MySqlParser.SelectIntoExpressionContext into = qs.selectIntoExpression();
        if (into == null) {
            return false;
        }
        if (into instanceof MySqlParser.SelectIntoDumpFileContext
                || into instanceof MySqlParser.SelectIntoTextFileContext) {
            unsupported(into, sb, depth, "SELECT INTO DUMPFILE/OUTFILE", Level.FAILED,
                    "file exports have no PL/pgSQL equivalent inside a routine");
            sb.append('\n');
            return true;
        }
        if (!(into instanceof MySqlParser.SelectIntoVariablesContext varsInto)) {
            return false;
        }

        String ind = "    ".repeat(depth);
        java.util.List<String> targets = new ArrayList<>();
        for (MySqlParser.AssignmentFieldContext field : varsInto.assignmentField()) {
            if (field.LOCAL_ID() != null) {
                unsupported(field, sb, depth, "user variable " + field.LOCAL_ID().getText(), Level.FAILED,
                        "PL/pgSQL has no session user variables; SELECT INTO dropped");
                sb.append('\n');
                return true;
            }
            targets.add(plainUid(field.uid()));
        }

        for (MySqlParser.SelectSpecContext spec : qs.selectSpec()) {
            message.report("SELECT option '" + tokens.getText(spec)
                    + "' dropped from SELECT INTO (no PL/pgSQL equivalent)", Level.INFO);
        }

        String elements = rewriteExprFunctions(tokens.getText(qs.selectElements()));
        sb.append(ind).append("SELECT ").append(elements)
                .append(" INTO ").append(String.join(", ", targets));
        if (qs.fromClause() != null) {
            sb.append(' ').append(rewriteExprFunctions(tokens.getText(qs.fromClause())));
        }
        if (qs.groupByClause() != null) {
            sb.append(' ').append(rewriteExprFunctions(tokens.getText(qs.groupByClause())));
        }
        if (qs.havingClause() != null) {
            sb.append(' ').append(rewriteExprFunctions(tokens.getText(qs.havingClause())));
        }
        if (qs.windowClause() != null) {
            sb.append(' ').append(tokens.getText(qs.windowClause()));
        }
        if (qs.orderByClause() != null) {
            sb.append(' ').append(rewriteExprFunctions(tokens.getText(qs.orderByClause())));
        }
        if (qs.limitClause() != null) {
            sb.append(' ').append(tokens.getText(qs.limitClause()));
        }
        sb.append(";\n");
        message.report("SELECT INTO re-emitted for PL/pgSQL; verify SELECT list / FROM clause"
                + " MySQL-specific syntax on the PostgreSQL side", Level.WARNING);
        return true;
    }

    /** {@code SIGNAL SQLSTATE '…' [SET MESSAGE_TEXT = '…']} → {@code RAISE EXCEPTION …}. */
    private void emitSignal(MySqlParser.SignalStatementContext ctx, StringBuilder sb, int depth) {
        if (ctx.ID() != null || ctx.REVERSE_QUOTE_ID() != null) {
            unsupported(ctx, sb, depth, "SIGNAL named condition", Level.FAILED,
                    "named conditions require DECLARE CONDITION which is not supported;"
                            + " use SIGNAL SQLSTATE '…' instead");
            sb.append('\n');
            return;
        }
        emitRaise(sqlStateLiteral(ctx.stringLiteral()), messageTextFromSignal(ctx.signalConditionInformation()),
                sb, depth, "SIGNAL");
    }

    /**
     * Bare {@code RESIGNAL} → {@code RAISE;} (re-throw). With SQLSTATE/MESSAGE it maps like SIGNAL.
     */
    private void emitResignal(MySqlParser.ResignalStatementContext ctx, StringBuilder sb, int depth) {
        if (ctx.ID() != null || ctx.REVERSE_QUOTE_ID() != null) {
            unsupported(ctx, sb, depth, "RESIGNAL named condition", Level.FAILED,
                    "named conditions require DECLARE CONDITION which is not supported");
            sb.append('\n');
            return;
        }
        String sqlstate = ctx.stringLiteral() != null ? sqlStateLiteral(ctx.stringLiteral()) : null;
        String msg = messageTextFromSignal(ctx.signalConditionInformation());
        if (sqlstate == null && msg == null) {
            sb.append("    ".repeat(depth)).append("RAISE;\n");
            message.report("RESIGNAL mapped to bare RAISE (re-throw current exception)", Level.WARNING);
            return;
        }
        emitRaise(sqlstate, msg, sb, depth, "RESIGNAL");
    }

    private void emitRaise(String sqlstate, String messageLiteral, StringBuilder sb, int depth, String origin) {
        String ind = "    ".repeat(depth);
        if (messageLiteral != null && sqlstate != null) {
            sb.append(ind).append("RAISE EXCEPTION ").append(messageLiteral)
                    .append(" USING ERRCODE = '").append(sqlstate).append("';\n");
        } else if (messageLiteral != null) {
            sb.append(ind).append("RAISE EXCEPTION ").append(messageLiteral).append(";\n");
        } else if (sqlstate != null) {
            sb.append(ind).append("RAISE EXCEPTION USING ERRCODE = '").append(sqlstate).append("';\n");
        } else {
            sb.append(ind).append("RAISE;\n");
        }
        message.report(origin + " mapped to RAISE — approximation: MySQL condition items beyond"
                + " MESSAGE_TEXT/SQLSTATE are dropped", Level.WARNING);
    }

    private String sqlStateLiteral(MySqlParser.StringLiteralContext lit) {
        if (lit == null) {
            return null;
        }
        String raw = tokens.getText(lit).trim();
        if ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith("\"") && raw.endsWith("\""))) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private String messageTextFromSignal(
            java.util.List<MySqlParser.SignalConditionInformationContext> infos) {
        String messageText = null;
        for (MySqlParser.SignalConditionInformationContext info : infos) {
            if (info.MESSAGE_TEXT() != null) {
                if (info.stringLiteral() != null) {
                    messageText = tokens.getText(info.stringLiteral());
                } else {
                    messageText = tokens.getText(info).replaceFirst("(?i)MESSAGE_TEXT\\s*=\\s*", "");
                }
            } else {
                message.report("SIGNAL/RESIGNAL condition item '"
                        + info.getChild(0).getText() + "' dropped (no RAISE mapping)", Level.INFO);
            }
        }
        return messageText;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void passthrough(ParserRuleContext ctx, StringBuilder sb, int depth, String what) {
        message.report(what + " passed through untranslated (approximation, verify semantics): "
                + abbreviate(tokens.getText(ctx)), Level.WARNING);
        sb.append("    ".repeat(depth)).append(tokens.getText(ctx)).append(";\n");
    }

    /**
     * Emits the unsupported fragment as a C-style comment preserving the original text,
     * and reports it. FAILED marks conversions that cannot be approximately replaced; WARNING marks
     * approximations whose semantics are not fully equivalent.
     */
    private void unsupported(ParserRuleContext ctx, StringBuilder sb, int depth, String what,
                             Level severity, String explanation) {
        message.report(what + " is not supported (" + severity + "): " + explanation
                + " — original: " + abbreviate(tokens.getText(ctx)), severity);
        sb.append("    ".repeat(depth)).append("/* ").append(severity).append(": ").append(what)
                .append(" — original: ").append(abbreviate(tokens.getText(ctx), 120)).append(" */");
    }

    private String abbreviate(String text) {
        return abbreviate(text, 60);
    }

    private String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private String quotedUid(MySqlParser.UidContext uid) {
        return StringUtil.quote(StringUtil.unquote(uid.getText(), '`'), '"');
    }

    /** Variables/parameter names stay bare so they line up with the unquoted occurrences inside the body. */
    private String plainUid(MySqlParser.UidContext uid) {
        return StringUtil.unquote(uid.getText(), '`');
    }

    private String quotedFullId(MySqlParser.FullIdContext ctx) {
        List<MySqlParser.UidContext> uids = ctx.uid();
        StringBuilder sb = new StringBuilder(quotedUid(uids.get(0)));
        if (uids.size() > 1) {
            sb.append('.').append(quotedUid(uids.get(1)));
        }
        if (ctx.DOT_ID() != null) {
            // DOT_ID is a single lexer token that carries the leading dot, e.g. `.orders`
            String name = ctx.DOT_ID().getText().substring(1);
            sb.append('.').append(StringUtil.quote(StringUtil.unquote(name, '`'), '"'));
        }
        return sb.toString();
    }
}
