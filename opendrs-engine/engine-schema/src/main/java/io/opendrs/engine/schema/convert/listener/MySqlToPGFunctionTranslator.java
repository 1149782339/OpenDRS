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
 * MySQL {@code CREATE FUNCTION} → PostgreSQL PL/pgSQL translator (demo).
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
 * with a warning. Expressions are carried over verbatim apart from a small function-name
 * whitelist ({@code IFNULL} → {@code COALESCE}).
 */
public class MySqlToPGFunctionTranslator {

    private final TokenStream tokens;
    private final ConversionMessage message;
    /** assignments collected from CONTINUE HANDLER FOR NOT FOUND, injected after each FETCH */
    private final List<String> notFoundAssignments = new ArrayList<>();
    private String commentStatement;

    public MySqlToPGFunctionTranslator(TokenStream tokens, ConversionMessage message) {
        this.tokens = tokens;
        this.message = message;
    }

    /** The {@code COMMENT ON FUNCTION …} statement derived from the routine COMMENT option. */
    public String getCommentStatement() {
        return commentStatement;
    }

    public String translate(MySqlParser.CreateFunctionContext ctx) {
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

        for (MySqlParser.RoutineOptionContext option : ctx.routineOption()) {
            if (option instanceof MySqlParser.RoutineCommentContext) {
                String literal = ((MySqlParser.RoutineCommentContext) option).STRING_LITERAL().getText();
                commentStatement =
                        "COMMENT ON FUNCTION " + quotedFullId(ctx.fullId()) + " IS " + literal + ";";
            } else {
                message.report("routine option '" + tokens.getText(option)
                        + "' dropped (no PostgreSQL equivalent)", Level.INFO);
            }
        }

        String body = emitRoutineBody(ctx);
        return header + " AS $$\n" + body + "$$ LANGUAGE plpgsql;";
    }

    // ---------------------------------------------------------------------------------------------
    // Body: clause-level re-emission
    // ---------------------------------------------------------------------------------------------

    private String emitRoutineBody(MySqlParser.CreateFunctionContext ctx) {
        if (ctx.returnStatement() != null) {
            return "BEGIN\n    RETURN " + expression(ctx.returnStatement().expression()) + ";\nEND;";
        }
        MySqlParser.RoutineBodyContext body = ctx.routineBody();
        if (body != null && body.blockStatement() != null) {
            message.report("function body statements are re-emitted, but expressions are carried over"
                    + " verbatim: verify function/operator semantics (e.g. ||, integer division, date"
                    + " arithmetic) differ between MySQL and PostgreSQL", Level.WARNING);
            notFoundAssignments.clear();
            StringBuilder sb = new StringBuilder();
            emitBlock(body.blockStatement(), sb, 0);
            return sb.toString();
        }
        // Rare shape: body is a single plain SQL statement.
        message.report("single-statement function body passed through untranslated", Level.WARNING);
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
        String ind = "    ".repeat(depth);
        if (ctx.compoundStatement() != null) {
            emitCompound(ctx.compoundStatement(), sb, depth);
            return;
        }
        MySqlParser.SqlStatementContext sql = ctx.sqlStatement();
        MySqlParser.SetStatementContext set =
                sql.administrationStatement() != null ? sql.administrationStatement().setStatement() : null;
        if (set instanceof MySqlParser.SetVariableContext) {
            emitSetAssignments(set, sb, depth);
        } else if (set instanceof MySqlParser.SetNewValueInsideTriggerContext) {
            emitSetAssignments(set, sb, depth);
        } else {
            passthrough(sql, sb, depth, "statement");
        }
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
            sb.append(ind).append("CASE\n");
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
                message.report("CHARSET/COLLATE clause dropped from function signature;"
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

    /** Expression text is carried over verbatim, apart from a small function-name whitelist. */
    private String expression(ParserRuleContext ctx) {
        return tokens.getText(ctx).replaceAll("(?i)\\bIFNULL\\s*\\(", "COALESCE(");
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
