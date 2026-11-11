package io.opendrs.engine.schema.convert.listener;

import io.opendrs.engine.schema.convert.ConversionMessage;
import io.opendrs.engine.schema.convert.ConversionMessage.Level;
import io.opendrs.engine.schema.convert.ParserContext;
import io.opendrs.engine.schema.convert.ConversionConfiguration;
import io.opendrs.engine.schema.convert.parser.mysql.MySqlParser;
import io.opendrs.engine.schema.convert.parser.mysql.MySqlParserBaseListener;
import io.opendrs.engine.common.naming.ColumnNamingStrategy;
import io.opendrs.engine.common.naming.SchemaNamingStrategy;
import io.opendrs.engine.common.naming.TableNamingStrategy;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.common.util.StringUtil;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL to PostgreSQL parser listener, used to rewrite sql in MySQL into PostgreSQL
 *
 */
public class MySqlToPGParserListener extends MySqlParserBaseListener {

    private final static String TABLE_ID = "TABLE_ID";

    private final static String COLUMN_CREAT_TABLE_CONTEXT = "COLUMN_CREAT_TABLE_CONTEXT";

    private final static String COLUMN_INDEX = "COLUMN_INDEX";

    private final static String PARSE_RULE_CONTEXT = "PARSE_RULE_CONTEXT";

    private final static String COLUMN_DECLARATION_CONTEXT = "COLUMN_DECLARATION_CONTEXT";

    private final static String TABLE_OPTION_AUTOINCREMENT_CONTEXT = "TABLE_OPTION_AUTOINCREMENT_CONTEXT";

    private final static String CREATE_INDEX_TEMPLATE = "CREATE INDEX %s ON %s(%s)";

    private final static String CREATE_ENUM_TEMPLATE = "CREATE TYPE %s AS ENUM(%s)";

    private final static String CREATE_COMMENT_ON_COLUMN_TEMPLATE = "COMMENT ON COLUMN %s.%s is %s";

    private final static String CREATE_COMMENT_ON_TABLE_TEMPLATE = "COMMENT ON TABLE %s is %s";

    private final static String ALTER_TABLE_COLUMN_NAME = "ALTER TABLE %s RENAME %s TO %s";

    private final static String ALTER_TABLE_FOREIGN_KEY = "ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY(%s) REFERENCES %s(%s)";

    private final static String CREATE_SEQUENCE_TEMPLATE = "CREATE SEQUENCE %s increment by 1 minvalue %d no maxvalue start with %d";

    private final static String CREATE_PRIMARY_KEY_TEMPLATE = "ALTER TABLE %s ADD CONSTRAINT %s PRIMARY KEY (%s)";

    private final static String CREATE_UNIQUE_KEY_TEMPLATE = "ALTER TABLE %s ADD CONSTRAINT %s UNIQUE (%s)";

    private final static String ALTER_SEQUENCE_OWNER_TEMPLATE = "ALTER SEQUENCE %s OWNED BY %s.%s";

    private final TokenStreamRewriter rewriter;

    private final ParserContext context = new ParserContext();

    private final List<String> beforeStatements = new ArrayList<>();

    private final List<String> afterStatements = new ArrayList<>();

    private final ConversionMessage message;

    /**
     * Set when the input rewrote to something PostgreSQL must not receive at all. The rewritten text
     * is a single statement, so one flag per conversion is enough.
     */
    private boolean rewrittenTextDiscarded;

    private final ColumnNamingStrategy columnNamingStrategy;

    private final TableNamingStrategy tableNamingStrategy;

    /** When false, foreign key constraints are dropped rather than deferred to an ALTER TABLE. */
    private final boolean includeForeignKeys;

    private final boolean includePrimaryKey;

    private final boolean includeUniqueKeys;

    private final boolean includeSecondaryIndexes;

    private final boolean includeComments;

    private final boolean ifNotExists;

    private final SchemaNamingStrategy schemaNamingStrategy;

    public MySqlToPGParserListener(TokenStream tokenStream, ConversionConfiguration configuration) {
        this.rewriter = new TokenStreamRewriter(tokenStream);
        this.message = new ConversionMessage();
        this.columnNamingStrategy = configuration.getColumnNamingStrategy();
        this.tableNamingStrategy = configuration.getTableNamingStrategy();
        this.includeForeignKeys = configuration.includeForeignKeys();
        this.includePrimaryKey = configuration.includePrimaryKey();
        this.includeUniqueKeys = configuration.includeUniqueKeys();
        this.includeSecondaryIndexes = configuration.includeSecondaryIndexes();
        this.includeComments = configuration.includeComments();
        this.ifNotExists = configuration.ifNotExists();
        this.schemaNamingStrategy = configuration.getSchemaNamingStrategy();
    }

    public List<String> getStatements() {
        List<String> statements = new ArrayList<>(beforeStatements.size() + afterStatements.size() + 1 );
        statements.addAll(beforeStatements);
        if (!rewrittenTextDiscarded) {
            String rewritten = rewriter.getText().trim();
            // Deleting ALTER clauses leaves doubled spaces from the original gaps; collapse those
            // only for ALTER TABLE so routine / CREATE TABLE indentation stays intact.
            if (rewritten.length() >= 11 && rewritten.regionMatches(true, 0, "ALTER TABLE", 0, 11)) {
                rewritten = rewritten.replaceAll(" {2,}", " ");
            }
            if (!rewritten.isEmpty()) {
                statements.add(rewritten);
            }
        }
        statements.addAll(afterStatements);
        return Collections.unmodifiableList(statements);
    }

    public ConversionMessage getMessage() {
        return message;
    }

    @Override
    public void exitCreateDatabase(MySqlParser.CreateDatabaseContext ctx) {
        for (MySqlParser.CreateDatabaseOptionContext createDatabaseOptionContext : ctx.createDatabaseOption()) {
            comment(createDatabaseOptionContext.getStart(), createDatabaseOptionContext.getStop());
        }
        Token keyword = ctx.dbFormat;
        rewriter.replace(keyword, "SCHEMA");
        if (ifNotExists && ctx.ifNotExists() == null) {
            rewriter.insertAfter(keyword, " IF NOT EXISTS");
        }
        String sourceName = StringUtil.unquote(ctx.uid().getText(), '`');
        String targetName = schemaNamingStrategy.resolveSchemaName(sourceName);
        rewriter.replace(ctx.uid().getStart(), ctx.uid().getStop(), StringUtil.quote(targetName, '"'));
    }

    @Override
    public void enterColumnDeclaration(MySqlParser.ColumnDeclarationContext ctx) {
        context.put(COLUMN_DECLARATION_CONTEXT, ctx);
    }

    private void rewriteCollectionDataType(String columnName, MySqlParser.CollectionDataTypeContext ctx) {
        TableId tableId = context.get(TABLE_ID);
        if (MySqlDataTypeToPGMap.contains(ctx.typeName.getType())) {
            rewriter.replace(ctx.getStart(), ctx.getStop(), MySqlDataTypeToPGMap.get(ctx.typeName.getType()));
            return;
        }
        if (ctx.typeName.getType() == MySqlParser.ENUM) {
            String enumValues = ctx.collectionOptions()
                    .STRING_LITERAL()
                    .stream()
                    .map(collectionOptionContext -> StringUtil.quote(collectionOptionContext.getText(), '\''))
                    .collect(Collectors.joining(","));
            String enumName = StringUtil.unquote(tableId.getTable()) + "_" + StringUtil.unquote(columnName);
            String sql = String.format(CREATE_ENUM_TEMPLATE, StringUtil.quote(enumName, '"'),  enumValues);
            beforeStatements.add(sql);
            rewriter.replace(ctx.getStart(), ctx.getStop(), StringUtil.quote(enumName, '"'));
        }
    }


    @Override
    public void exitSimpleDataType(MySqlParser.SimpleDataTypeContext ctx) {

    }

    private void rewriteStringDataTypeContext(MySqlParser.StringDataTypeContext ctx) {
        if (ctx.charSet() != null) {
            comment(ctx.charSet().getStart(), ctx.charsetName().getStop());
        }

        if (ctx.COLLATE() != null) {
            comment(ctx.COLLATE().getSymbol(), ctx.collationName().getStop());
        }

        if (MySqlDataTypeToPGMap.contains(ctx.typeName.getType())) {
            rewriter.replace(ctx.typeName, MySqlDataTypeToPGMap.get(ctx.typeName.getType()));
        }
    }

    /**
     * MySQL {@code CREATE FUNCTION} becomes a PL/pgSQL function. The routine header keeps its
     * structure (token-level type mapping), while the procedural body is re-emitted clause by
     * clause; see {@link MySqlToPGRoutineTranslator}.
     */
    @Override
    public void exitCreateFunction(MySqlParser.CreateFunctionContext ctx) {
        MySqlToPGRoutineTranslator translator = new MySqlToPGRoutineTranslator(rewriter.getTokenStream(), message);
        String translated = translator.translateFunction(ctx);
        rewriter.replace(ctx.getStart(), ctx.getStop(), translated);
        if (translator.getCommentStatement() != null) {
            afterStatements.add(translator.getCommentStatement());
        }
    }

    private void rewriteDimensionDataType(MySqlParser.DimensionDataTypeContext ctx) {
        int type = ctx.typeName.getType();
        if ((ctx.lengthOneDimension() != null) && (type == MySqlParser.TINYINT
                || type == MySqlParser.INT || type == MySqlParser.BIGINT
                || type == MySqlParser.SMALLINT || type == MySqlParser.MEDIUMINT)) {
            comment(ctx.lengthOneDimension().getStart(), ctx.lengthOneDimension().getStop());
            message.report("NOT SUPPORT SPECIFY DISPLAY WIDTH", Level.INFO);
        }
        for (TerminalNode unsigned :  ctx.UNSIGNED()) {
            comment(unsigned.getSymbol());
        }
        if (MySqlDataTypeToPGMap.contains(ctx.typeName.getType())) {
            rewriter.replace(ctx.typeName, MySqlDataTypeToPGMap.get(ctx.typeName.getType()));
            return;
        }
        if (ctx.typeName.getType() == MySqlParser.TINYINT) {
            if (ctx.lengthOneDimension() != null && "1".equals(ctx.lengthOneDimension().decimalLiteral().getText())) {
                rewriter.replace(ctx.TINYINT().getSymbol(), "BOOLEAN");
            } else {
                rewriter.replace(ctx.TINYINT().getSymbol(), "SMALLINT");
            }
        }
        if (ctx.typeName.getType() == MySqlParser.FLOAT) {
            if (ctx.lengthOneDimension() == null && ctx.lengthTwoOptionalDimension() == null) {
                rewriter.replace(ctx.FLOAT().getSymbol(), "REAL");
            }
            if (ctx.lengthTwoOptionalDimension() != null) {
                rewriter.replace(ctx.FLOAT().getSymbol(), "NUMERIC");
            }
        }
        if (ctx.typeName.getType() == MySqlParser.DOUBLE) {
            if (ctx.lengthOneDimension() == null && ctx.lengthTwoDimension() == null) {
                rewriter.replace(ctx.DOUBLE().getSymbol(), "DOUBLE PRECISION");
            }
            if (ctx.lengthTwoDimension() != null) {
                rewriter.replace(ctx.DOUBLE().getSymbol(), "NUMERIC");
            }
        }
        if (ctx.typeName.getType() == MySqlParser.TIMESTAMP) {
            String precision = null;
            if (ctx.lengthOneDimension() != null) {
                precision = ctx.lengthOneDimension().getText();
                rewriter.delete(ctx.lengthOneDimension().getStart(), ctx.lengthOneDimension().getStop());
            }
            if  (precision == null) {
                rewriter.replace(ctx.TIMESTAMP().getSymbol(), "TIMESTAMP WITH TIME ZONE");
            } else {
                rewriter.replace(ctx.TIMESTAMP().getSymbol(), "TIMESTAMP" + precision + " WITH TIME ZONE");
            }
        }
        if (ctx.typeName.getType() == MySqlParser.DATETIME) {
            String precision = null;
            if (ctx.lengthOneDimension() != null) {
                precision = ctx.lengthOneDimension().getText();
                rewriter.delete(ctx.lengthOneDimension().getStart(), ctx.lengthOneDimension().getStop());
            }
            if  (precision == null) {
                rewriter.replace(ctx.DATETIME().getSymbol(), "TIMESTAMP WITHOUT TIME ZONE");
            } else {
                rewriter.replace(ctx.DATETIME().getSymbol(), "TIMESTAMP" + precision + " WITHOUT TIME ZONE");
            }
        }
        if (ctx.typeName.getType() == MySqlParser.DATE) {
            rewriter.replace(ctx.DATETIME().getSymbol(), "TIMESTAMP(0) WITHOUT TIME ZONE");
        }
    }

    /**
     * MySQL identifiers reach us either bare ({@code db.tbl}) or quoted ({@code `db`.`tbl`}), and
     * the two styles can be mixed, so split on the first dot that is not inside backticks.
     */
    private TableId parseTableId(String identifier) {
        int separator = separatorIndex(identifier);
        if (separator < 0) {
            return new TableId(null, null, StringUtil.unquote(identifier, '`'));
        }
        String catalog = StringUtil.unquote(identifier.substring(0, separator), '`');
        String table = StringUtil.unquote(identifier.substring(separator + 1), '`');
        return new TableId(catalog, null, table);
    }

    private static int separatorIndex(String identifier) {
        boolean quoted = false;
        for (int i = 0; i < identifier.length(); i++) {
            char current = identifier.charAt(i);
            if (current == '`') {
                quoted = !quoted;
            } else if (current == '.' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void enterColumnCreateTable(MySqlParser.ColumnCreateTableContext ctx) {
        TableId tableId = parseTableId(ctx.tableName().fullId().getText());
        tableId = tableNamingStrategy.resolveTableId(tableId);
        context.put(TABLE_ID,  tableId);
        context.put(COLUMN_CREAT_TABLE_CONTEXT, ctx);
        for (MySqlParser.TableOptionContext tableOptionContext : ctx.tableOption()) {
            if (tableOptionContext instanceof MySqlParser.TableOptionAutoIncrementContext) {
                context.put(TABLE_OPTION_AUTOINCREMENT_CONTEXT, tableOptionContext);
            }
        }
    }


    @Override
    public void exitDropTable(MySqlParser.DropTableContext ctx) {
        for (MySqlParser.TableNameContext tableNameContext : ctx.tables().tableName()) {
            TableId tableId = tableNamingStrategy.resolveTableId(parseTableId(tableNameContext.fullId().getText()));
            rewriter.replace(tableNameContext.fullId().getStart(), tableNameContext.fullId().getStop(), tableId.toQuoted('"'));
        }
        rewriter.insertAfter(ctx.getStop(), " cascade");
    }

    @Override
    public void exitColumnCreateTable(MySqlParser.ColumnCreateTableContext ctx) {
        context.put(PARSE_RULE_CONTEXT, ctx);
        if (ifNotExists && ctx.ifNotExists() == null) {
            rewriter.insertAfter(ctx.TABLE().getSymbol(), " IF NOT EXISTS");
        }
        TableId tableId = context.get(TABLE_ID);
        tableId = tableNamingStrategy.resolveTableId(tableId);
        rewriter.replace(ctx.tableName().getStart(), ctx.tableName().getStop(), tableId.toQuoted('"'));
        List<MySqlParser.CreateDefinitionContext> createDefinitionContexts = ctx.createDefinitions().createDefinition();
        for (int i = 0; i < createDefinitionContexts.size(); i++) {
            context.put(COLUMN_INDEX, i);
            MySqlParser.CreateDefinitionContext createDefinitionContext = createDefinitionContexts.get(i);
            if (createDefinitionContext instanceof MySqlParser.IndexDeclarationContext) {
                rewriteIndexDeclaration((MySqlParser.IndexDeclarationContext)createDefinitionContext);
            }
            if (createDefinitionContext instanceof MySqlParser.ConstraintDeclarationContext) {
                rewriteConstraintDeclarationContext(((MySqlParser.ConstraintDeclarationContext)createDefinitionContext).tableConstraint());
            }
            if (createDefinitionContext instanceof MySqlParser.ColumnDeclarationContext) {
                rewriteColumnDeclaration((MySqlParser.ColumnDeclarationContext) createDefinitionContext);
            }
        }
    }

    @Override
    public void exitCreateView(MySqlParser.CreateViewContext ctx) {
        if (ctx.ownerStatement() != null) {
            comment(ctx.ownerStatement().getStart(), ctx.ownerStatement().getStop());
        }
        if (ctx.ALGORITHM() != null) {
            comment(ctx.ALGORITHM().getSymbol(), ctx.algType);
        }
        if (ctx.SQL() != null) {
            comment(ctx.SQL().getSymbol(), ctx.secContext);
        }
        TableId viewId = tableNamingStrategy.resolveTableId(parseTableId(ctx.fullId().getText()));
        rewriter.replace(ctx.fullId().getStart(), ctx.fullId().getStop(), viewId.toQuoted('"').toString());
        if (ctx.uidList() != null) {
            for (MySqlParser.UidContext uid : ctx.uidList().uid()) {
                String name = StringUtil.unquote(uid.getText(), '`');
                rewriter.replace(uid.getStart(), uid.getStop(),
                        StringUtil.quote(columnNamingStrategy.resolveColumnName(name), '"'));
            }
        }
        if (ctx.selectStatement() != null) {
            new ParseTreeWalker().walk(new ViewSelectRewriter(), ctx.selectStatement());
        }
        message.report(
                "view query body rewritten for identifiers only; functions are not translated",
                Level.WARNING);
    }

    /**
     * Rewrites identifiers inside a view's SELECT: real table references go through the table
     * naming strategy, column references through the column naming strategy, and remaining
     * backtick-quoted identifiers (aliases and such) are re-quoted with double quotes. The query
     * structure itself is not touched.
     */
    private class ViewSelectRewriter extends MySqlParserBaseListener {

        @Override
        public void exitTableName(MySqlParser.TableNameContext ctx) {
            TableId tableId = tableNamingStrategy.resolveTableId(parseTableId(ctx.fullId().getText()));
            rewriter.replace(ctx.getStart(), ctx.getStop(), tableId.toQuoted('"').toString());
        }

        @Override
        public void exitFullColumnName(MySqlParser.FullColumnNameContext ctx) {
            List<MySqlParser.UidContext> parts = new ArrayList<>();
            parts.add(ctx.uid());
            for (MySqlParser.DottedIdContext dotted : ctx.dottedId()) {
                parts.add(dotted.uid());
            }
            List<String> rewritten = new ArrayList<>(parts.size());
            for (int i = 0; i < parts.size(); i++) {
                String name = StringUtil.unquote(parts.get(i).getText(), '`');
                if (i == parts.size() - 1) {
                    name = columnNamingStrategy.resolveColumnName(name);
                }
                rewritten.add(StringUtil.quote(name, '"'));
            }
            rewriter.replace(ctx.getStart(), ctx.getStop(), String.join(".", rewritten));
        }

        @Override
        public void exitUid(MySqlParser.UidContext ctx) {
            // backticked identifiers nest as uid -> simpleId -> engineName -> REVERSE_QUOTE_ID,
            // so the token type alone is not reachable here; test the text instead
            if (!ctx.getText().startsWith("`")
                    || within(ctx, MySqlParser.FullColumnNameContext.class)
                    || within(ctx, MySqlParser.TableNameContext.class)) {
                return;
            }
            rewriter.replace(ctx.getStart(), ctx.getStop(),
                    StringUtil.quote(StringUtil.unquote(ctx.getText(), '`'), '"'));
        }
    }

    private static boolean within(ParserRuleContext ctx, Class<? extends ParserRuleContext> type) {
        for (ParserRuleContext parent = ctx.getParent(); parent != null; parent = parent.getParent()) {
            if (type.isInstance(parent)) {
                return true;
            }
        }
        return false;
    }

    private void comment(Token start, Token end) {
        rewriter.insertBefore(start, "/* ");
        rewriter.insertAfter(end, " */");
    }
    private void comment(Token start) {
        rewriter.insertBefore(start, "/* ");
        rewriter.insertAfter(start, " */");
    }
    private void rewriteColumnDeclaration(MySqlParser.ColumnDeclarationContext ctx) {
        String columnName = StringUtil.unquote(ctx.fullColumnName().uid().getText(), '`');
        columnName = columnNamingStrategy.resolveColumnName(columnName);
        rewriter.replace(ctx.fullColumnName().uid().getStart(), ctx.fullColumnName().uid().getStop(),
            StringUtil.quote(columnName, '"'));
        rewriteColumnDefinition(ctx.fullColumnName().getText(), ctx.columnDefinition());
    }

    private void rewriteUniqueKeyTableConstraint(MySqlParser.UniqueKeyTableConstraintContext ctx) {
        int columnIndex = context.get(COLUMN_INDEX);
        MySqlParser.ColumnCreateTableContext columnCreateTableContext = context.get(COLUMN_CREAT_TABLE_CONTEXT);
        if (!includeUniqueKeys) {
            deleteTableConstraint(ctx, columnIndex, columnCreateTableContext);
            return;
        }
        List<String> columnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), ctx.index == null);
        String columnParts = String.join(",", columnNames);
        TableId tableId = context.get(TABLE_ID);
        if (ctx.index != null) {
            afterStatements.add(String.format(CREATE_UNIQUE_KEY_TEMPLATE, tableId.getTable(),
                    StringUtil.quote(tableId.getTable() + "_" + StringUtil.unquote(ctx.index.getText()), '"'), columnParts));
            rewriter.delete(ctx.getStart(), ctx.getStop());
            if (columnIndex > 0) {
                rewriter.delete(columnCreateTableContext.createDefinitions().COMMA(columnIndex - 1).getSymbol());
            }
            return;
        }
        // PostgreSQL table constraints are UNIQUE (...), not UNIQUE KEY (...). MySQL's optional
        // INDEX/KEY after UNIQUE must be dropped or we emit the illegal "UNIQUE KEY KEY (...)".
        if (ctx.indexFormat != null) {
            rewriter.replace(ctx.UNIQUE().getSymbol(), ctx.indexFormat, "UNIQUE");
        }
        for (MySqlParser.IndexOptionContext indexOptionContext : ctx.indexOption()) {
            comment(indexOptionContext.getStart(), indexOptionContext.getStop());
        }
    }

    private void rewriteConstraintDeclarationContext(MySqlParser.TableConstraintContext ctx) {
        if (ctx instanceof MySqlParser.UniqueKeyTableConstraintContext) {
            rewriteUniqueKeyTableConstraint((MySqlParser.UniqueKeyTableConstraintContext) ctx);
        }
        if (ctx instanceof MySqlParser.PrimaryKeyTableConstraintContext) {
            rewritePrimaryKeyTableConstraint((MySqlParser.PrimaryKeyTableConstraintContext) ctx);
        }
        if (ctx instanceof MySqlParser.ForeignKeyTableConstraintContext) {
            rewriteForeignKeyTableConstraint((MySqlParser.ForeignKeyTableConstraintContext) ctx);
        }
    }

    void rewriteForeignKeyTableConstraint(MySqlParser.ForeignKeyTableConstraintContext ctx) {
        final int columnIndex = context.get(COLUMN_INDEX);
        final MySqlParser.ColumnCreateTableContext columnCreateTableContext = context.get(COLUMN_CREAT_TABLE_CONTEXT);
        if (columnIndex > 0) {
            rewriter.delete(columnCreateTableContext.createDefinitions().COMMA(columnIndex - 1).getSymbol());
        }
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (!includeForeignKeys) {
            // Table-only pass: drop the constraint instead of deferring it. See
            // ConversionConfiguration#includeForeignKeys.
            return;
        }
        final TableId tableId = context.get(TABLE_ID);
        MySqlParser.ReferenceDefinitionContext referenceDefinitionContext = ctx.referenceDefinition();
        TableId refTableId = tableNamingStrategy.resolveTableId(
                parseTableId(referenceDefinitionContext.tableName().fullId().getText()));
        List<String> refColumnNames = getColumnNamesFromIndexColumnNameContext(referenceDefinitionContext.indexColumnNames(), false);
        List<String> foreignColumnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), false);
        String tableName = tableId.toQuoted('"').toString();
        // Anonymous FOREIGN KEY (no CONSTRAINT name) is common in MySQL dumps; generate a
        // default like ALTER ... ADD FOREIGN KEY already does. Prefer CONSTRAINT name, then
        // the optional index uid after FOREIGN KEY.
        String constraintName;
        if (ctx.name != null) {
            constraintName = quoteIdent(ctx.name.getText());
        } else if (ctx.index != null) {
            constraintName = quoteIdent(ctx.index.getText());
        } else {
            constraintName = defaultForeignKeyName(tableId, foreignColumnNames);
        }
        StringBuilder sql = new StringBuilder(String.format(ALTER_TABLE_FOREIGN_KEY, tableName,
                constraintName,
                String.join(",", foreignColumnNames), refTableId.toQuoted('"'),
                String.join(",", refColumnNames)));
        // ON DELETE / ON UPDATE used to be dropped on this path (the ALTER path kept them).
        if (referenceDefinitionContext.referenceAction() != null) {
            appendReferenceAction(sql, referenceDefinitionContext.referenceAction());
        }
        afterStatements.add(sql.toString());
    }

    void rewritePrimaryKeyTableConstraint(MySqlParser.PrimaryKeyTableConstraintContext ctx) {
        int columnIndex = context.get(COLUMN_INDEX);
        MySqlParser.ColumnCreateTableContext columnCreateTableContext = context.get(COLUMN_CREAT_TABLE_CONTEXT);
        TableId tableId = context.get(TABLE_ID);
        if (!includePrimaryKey) {
            deleteTableConstraint(ctx, columnIndex, columnCreateTableContext);
            return;
        }
        for (MySqlParser.IndexOptionContext indexOptionContext : ctx.indexOption()) {
            comment(indexOptionContext.getStart(), indexOptionContext.getStop());
        }
        List<String> columnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), ctx.index == null);
        if (ctx.index != null) {
            String columnParts = String.join(",", columnNames);
            afterStatements.add(String.format(CREATE_PRIMARY_KEY_TEMPLATE, tableId.getTable(),
                    StringUtil.quote(tableId.getTable() + "_" + StringUtil.unquote(ctx.index.getText()), '"'), columnParts));
            rewriter.delete(ctx.getStart(), ctx.getStop());
            if (columnIndex > 0) {
                rewriter.delete(columnCreateTableContext.createDefinitions().COMMA(columnIndex - 1).getSymbol());
            }
        }
       // List<String> columnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames());
       // String columnParts = String.join(",", columnNames);
       // rewriter.replace(ctx.getStart(), ctx.getStop(), "PRIMARY KEY(" + columnParts + ")");
    }

    /** Drops a table-level constraint together with the comma that separated it from the previous one. */
    private void deleteTableConstraint(
            ParserRuleContext ctx, int columnIndex, MySqlParser.ColumnCreateTableContext table) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (columnIndex > 0) {
            rewriter.delete(table.createDefinitions().COMMA(columnIndex - 1).getSymbol());
        }
    }


    private void rewriteIndexDeclaration(MySqlParser.IndexDeclarationContext ctx) {
        MySqlParser.IndexColumnDefinitionContext indexColumnDefinitionContext = ctx.indexColumnDefinition();
        if (indexColumnDefinitionContext instanceof MySqlParser.SimpleIndexDeclarationContext) {
            rewriteSimpleIndexDeclaration((MySqlParser.SimpleIndexDeclarationContext) indexColumnDefinitionContext);
        }
        if (indexColumnDefinitionContext instanceof MySqlParser.SpecialIndexDeclarationContext) {
            rewriteSpecialIndexDeclaration((MySqlParser.SpecialIndexDeclarationContext) indexColumnDefinitionContext);
        }
    }

    private void rewriteSpecialIndexDeclaration(MySqlParser.SpecialIndexDeclarationContext ctx) {
        final String tableId = context.get(TABLE_ID);
        final MySqlParser.ColumnCreateTableContext columnCreateTableContext = context.get(COLUMN_CREAT_TABLE_CONTEXT);
        List<String> columnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), false);
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (!includeSecondaryIndexes) {
            return;
        }
        String columnParts = String.join(",", columnNames);
        afterStatements.add(String.format(CREATE_INDEX_TEMPLATE, tableId, columnParts));
    }

    private void rewriteSimpleIndexDeclaration(MySqlParser.SimpleIndexDeclarationContext ctx) {
        final TableId tableId = context.get(TABLE_ID);
        final int columnIndex = context.get(COLUMN_INDEX);
        final MySqlParser.ColumnCreateTableContext columnCreateTableContext = context.get(COLUMN_CREAT_TABLE_CONTEXT);
        final MySqlParser.ColumnDeclarationContext ColumnDeclarationContext = context.get(COLUMN_DECLARATION_CONTEXT);
        final String columnName = ColumnDeclarationContext.fullColumnName().getText();
        List<String> columnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), false);
        if (columnIndex > 0) {
            rewriter.delete(columnCreateTableContext.createDefinitions().COMMA(columnIndex - 1).getSymbol());
        }
        Token token = ctx.getStop();
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (!includeSecondaryIndexes) {
            return;
        }
        String columnParts = String.join(",", columnNames);
        String indexName;
        if (ctx.uid() != null) {
            indexName = StringUtil.quote(tableId.getTable() + "_" +  StringUtil.unquote(ctx.uid().getText()), '"');
        } else {
            indexName = StringUtil.quote(tableId.getTable() + "_" + columnName, '"');
        }
        afterStatements.add(indexStatement(indexName, tableId.toQuoted('"').toString(), columnParts));
    }

    private String indexStatement(String indexName, String table, String columns) {
        String prefix = ifNotExists ? "CREATE INDEX IF NOT EXISTS " : "CREATE INDEX ";
        return prefix + indexName + " ON " + table + "(" + columns + ")";
    }

    @Override
    public void exitTableOptionEngine(MySqlParser.TableOptionEngineContext ctx) {
        dropOrCommentTableOption(ctx);
    }

    @Override
    public void exitTableOptionCollate(MySqlParser.TableOptionCollateContext ctx) {
        // COLLATE is meaningless on PostgreSQL for both CREATE and ALTER.
        rewriter.delete(ctx.getStart(), ctx.getStop());
    }


    @Override
    public void exitUid(MySqlParser.UidContext ctx) {
        //rewriter.replace(ctx.getStart(), ctx.getStop(),  StringUtil.quote(ctx.getText(), '"'));
    }

    @Override
    public void exitAlterTable(MySqlParser.AlterTableContext ctx) {
        // Same mapping as CREATE TABLE: a renamed target must receive the ALTER too.
        TableId tableId = tableNamingStrategy.resolveTableId(parseTableId(ctx.tableName().fullId().getText()));
        context.put(TABLE_ID, tableId);
        context.put(PARSE_RULE_CONTEXT, ctx);
        List<MySqlParser.AlterSpecificationContext> alterSpecificationContexts = ctx.alterSpecification();
        // Trailing `ALTER TABLE t PARTITION BY ...` (grammar: alterTable.partitionDefinitions?)
        // is not an alterSpecification — drop it so MySQL partition syntax cannot leak.
        // Structure-migration scripts should fold this onto a preceding CREATE via
        // DdlStatements.mergeCreateTableWithAlterPartitionBy (MySqlToPGConverter does so).
        // Isolated CDC events stay discarded here.
        if (ctx.partitionDefinitions() != null) {
            rewriter.delete(
                    ctx.partitionDefinitions().getStart(), ctx.partitionDefinitions().getStop());
            message.report("unsupported ALTER TABLE PARTITION BY clause dropped", Level.INFO);
        }
        if (onlyTableOptions(alterSpecificationContexts)) {
            // `ALTER TABLE t AUTO_INCREMENT = 11` — and its ENGINE= / CHARSET= / COLLATE= /
            // ROW_FORMAT= siblings — carries nothing PostgreSQL can execute. The table option exit
            // handlers below comment each option out, which would leave a bare
            // `ALTER TABLE "test"."test" /* AUTO_INCREMENT = 11 */`; PostgreSQL rejects that with
            // "syntax error at end of input" and the whole CDC job dies. MySQL writes such a
            // statement to the binlog whenever a client resets the counter, so it does reach the
            // applier as a schema change event.
            // Also covers `ALTER TABLE t PARTITION BY ...` with no other clauses.
            rewrittenTextDiscarded = true;
            return;
        }
        if (ctx.intimeAction != null) {
            rewriter.delete(ctx.intimeAction);
        }
        if (ctx.IGNORE() != null) {
            rewriter.delete(ctx.IGNORE().getSymbol());
        }
        if (ctx.waitNowaitClause() != null) {
            rewriter.delete(ctx.waitNowaitClause().getStart(), ctx.waitNowaitClause().getStop());
        }
        rewriter.replace(ctx.tableName().getStart(), ctx.tableName().getStop(), tableId.toQuoted('"').identifier());

        boolean[] keepInAlter = new boolean[alterSpecificationContexts.size()];
        for (int i = 0; i < alterSpecificationContexts.size(); i++) {
            MySqlParser.AlterSpecificationContext spec = alterSpecificationContexts.get(i);
            keepInAlter[i] = rewriteAlterSpecification(tableId, spec);
        }
        compactAlterSpecifications(ctx, keepInAlter);

        boolean anyKept = false;
        for (boolean kept : keepInAlter) {
            if (kept) {
                anyKept = true;
                break;
            }
        }
        if (!anyKept) {
            // Every clause became a follow-up statement, a comment, or was dropped.
            rewrittenTextDiscarded = true;
        }
    }

    /**
     * Rewrites one ALTER TABLE clause. Returns {@code true} when the clause should remain inside
     * the original {@code ALTER TABLE} text; {@code false} when it was deleted / extracted to
     * {@link #afterStatements} / discarded.
     */
    private boolean rewriteAlterSpecification(TableId tableId, MySqlParser.AlterSpecificationContext spec) {
        if (spec instanceof MySqlParser.AlterByModifyColumnContext) {
            rewriteAlterByModifyColumn((MySqlParser.AlterByModifyColumnContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByChangeColumnContext) {
            rewriteAlterByChangeColumn((MySqlParser.AlterByChangeColumnContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByAddColumnContext) {
            rewriteAlterByAddColumn((MySqlParser.AlterByAddColumnContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByDropColumnContext) {
            rewriteAlterByDropColumn((MySqlParser.AlterByDropColumnContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByAddColumnsContext) {
            rewriteAlterByAddColumns((MySqlParser.AlterByAddColumnsContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByAddIndexContext) {
            return rewriteAlterByAddIndex(tableId, (MySqlParser.AlterByAddIndexContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByAddPrimaryKeyContext) {
            return rewriteAlterByAddPrimaryKey(tableId, (MySqlParser.AlterByAddPrimaryKeyContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByAddUniqueKeyContext) {
            return rewriteAlterByAddUniqueKey(tableId, (MySqlParser.AlterByAddUniqueKeyContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByAddForeignKeyContext) {
            return rewriteAlterByAddForeignKey(tableId, (MySqlParser.AlterByAddForeignKeyContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByAddCheckTableConstraintContext) {
            return rewriteAlterByAddCheck(tableId, (MySqlParser.AlterByAddCheckTableConstraintContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByAddSpecialIndexContext) {
            rewriter.delete(spec.getStart(), spec.getStop());
            message.report("FULLTEXT/SPATIAL index is not converted", Level.INFO);
            return false;
        }
        if (spec instanceof MySqlParser.AlterByDropPrimaryKeyContext) {
            return rewriteAlterByDropPrimaryKey(tableId, (MySqlParser.AlterByDropPrimaryKeyContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByDropIndexContext) {
            return rewriteAlterByDropIndex(tableId, (MySqlParser.AlterByDropIndexContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByDropForeignKeyContext) {
            rewriteAlterByDropForeignKey((MySqlParser.AlterByDropForeignKeyContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByDropConstraintCheckContext) {
            rewriteAlterByDropConstraintCheck((MySqlParser.AlterByDropConstraintCheckContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByRenameContext) {
            rewriteAlterByRename(tableId, (MySqlParser.AlterByRenameContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByRenameColumnContext) {
            rewriteAlterByRenameColumn((MySqlParser.AlterByRenameColumnContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByRenameIndexContext) {
            return rewriteAlterByRenameIndex(tableId, (MySqlParser.AlterByRenameIndexContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByChangeDefaultContext) {
            rewriteAlterByChangeDefault((MySqlParser.AlterByChangeDefaultContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByAlterColumnDefaultContext) {
            rewriteAlterByAlterColumnDefault((MySqlParser.AlterByAlterColumnDefaultContext) spec);
            return true;
        }
        if (spec instanceof MySqlParser.AlterByTableOptionContext) {
            // Tokens already deleted by exitTableOption*; drop the empty clause + its comma.
            return false;
        }
        if (spec instanceof MySqlParser.AlterBySetAlgorithmContext
                || spec instanceof MySqlParser.AlterByLockContext
                || spec instanceof MySqlParser.AlterByDisableKeysContext
                || spec instanceof MySqlParser.AlterByEnableKeysContext
                || spec instanceof MySqlParser.AlterByConvertCharsetContext
                || spec instanceof MySqlParser.AlterByDefaultCharsetContext
                || spec instanceof MySqlParser.AlterByOrderContext
                || spec instanceof MySqlParser.AlterByForceContext
                || spec instanceof MySqlParser.AlterByValidateContext
                || spec instanceof MySqlParser.AlterByDiscardTablespaceContext
                || spec instanceof MySqlParser.AlterByImportTablespaceContext
                || spec instanceof MySqlParser.AlterByAlterIndexVisibilityContext) {
            rewriter.delete(spec.getStart(), spec.getStop());
            return false;
        }
        if (spec instanceof MySqlParser.AlterByAddPartitionContext) {
            return rewriteAlterByAddPartition(
                    tableId, (MySqlParser.AlterByAddPartitionContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByDropPartitionContext) {
            return rewriteAlterByDropPartition(
                    tableId, (MySqlParser.AlterByDropPartitionContext) spec);
        }
        if (spec instanceof MySqlParser.AlterByTruncatePartitionContext) {
            return rewriteAlterByTruncatePartition(
                    tableId, (MySqlParser.AlterByTruncatePartitionContext) spec);
        }
        if (isPartitionAlterSpecification(spec)) {
            rewriter.delete(spec.getStart(), spec.getStop());
            message.report(
                    "unsupported ALTER TABLE partition clause dropped: "
                            + abbreviateSql(spec.getText()),
                    Level.INFO);
            return false;
        }
        // Other unsupported ALTER forms: drop them with a note.
        rewriter.delete(spec.getStart(), spec.getStop());
        message.report("unsupported ALTER TABLE clause dropped: " + abbreviateSql(spec.getText()), Level.INFO);
        return false;
    }

    /**
     * Deletes clauses marked {@code false} and rewrites commas so remaining clauses stay valid.
     */
    private void compactAlterSpecifications(MySqlParser.AlterTableContext ctx, boolean[] keepInAlter) {
        List<MySqlParser.AlterSpecificationContext> specs = ctx.alterSpecification();
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < keepInAlter.length; i++) {
            if (keepInAlter[i]) {
                kept.add(i);
            } else {
                rewriter.delete(specs.get(i).getStart(), specs.get(i).getStop());
            }
        }
        for (TerminalNode comma : ctx.COMMA()) {
            rewriter.delete(comma.getSymbol());
        }
        for (int k = 0; k < kept.size() - 1; k++) {
            rewriter.insertAfter(specs.get(kept.get(k)).getStop(), ",");
        }
    }

    private boolean rewriteAlterByAddIndex(TableId tableId, MySqlParser.AlterByAddIndexContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (!includeSecondaryIndexes) {
            return false;
        }
        List<String> columnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), false);
        String indexName;
        if (ctx.uid() != null) {
            indexName = pgIndexName(tableId, StringUtil.unquote(ctx.uid().getText(), '`'));
        } else {
            indexName = pgIndexName(tableId, StringUtil.unquote(columnNames.get(0), '"'));
        }
        afterStatements.add(indexStatement(indexName, quotedTable(tableId), String.join(",", columnNames)));
        return false;
    }

    private boolean rewriteAlterByAddPrimaryKey(TableId tableId, MySqlParser.AlterByAddPrimaryKeyContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (!includePrimaryKey) {
            return false;
        }
        List<String> columnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), false);
        String constraintName = ctx.name != null
                ? quoteIdent(ctx.name.getText())
                : StringUtil.quote(tableId.getTable() + "_pkey", '"');
        afterStatements.add(String.format(
                "ALTER TABLE %s ADD CONSTRAINT %s PRIMARY KEY (%s)",
                quotedTable(tableId), constraintName, String.join(",", columnNames)));
        return false;
    }

    private boolean rewriteAlterByAddUniqueKey(TableId tableId, MySqlParser.AlterByAddUniqueKeyContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (!includeUniqueKeys) {
            return false;
        }
        List<String> columnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), false);
        String constraintName;
        if (ctx.name != null) {
            constraintName = quoteIdent(ctx.name.getText());
        } else if (ctx.indexName != null) {
            constraintName = StringUtil.quote(
                    tableId.getTable() + "_" + StringUtil.unquote(ctx.indexName.getText(), '`'), '"');
        } else {
            constraintName = StringUtil.quote(
                    tableId.getTable() + "_" + StringUtil.unquote(columnNames.get(0), '"') + "_key", '"');
        }
        afterStatements.add(String.format(
                "ALTER TABLE %s ADD CONSTRAINT %s UNIQUE (%s)",
                quotedTable(tableId), constraintName, String.join(",", columnNames)));
        return false;
    }

    private boolean rewriteAlterByAddForeignKey(TableId tableId, MySqlParser.AlterByAddForeignKeyContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (!includeForeignKeys) {
            return false;
        }
        MySqlParser.ReferenceDefinitionContext reference = ctx.referenceDefinition();
        TableId refTableId = tableNamingStrategy.resolveTableId(
                parseTableId(reference.tableName().fullId().getText()));
        List<String> foreignColumns = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), false);
        List<String> refColumns = reference.indexColumnNames() != null
                ? getColumnNamesFromIndexColumnNameContext(reference.indexColumnNames(), false)
                : foreignColumns;
        String constraintName = ctx.name != null
                ? quoteIdent(ctx.name.getText())
                : defaultForeignKeyName(tableId, foreignColumns);
        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLE ").append(quotedTable(tableId))
                .append(" ADD CONSTRAINT ").append(constraintName)
                .append(" FOREIGN KEY(").append(String.join(",", foreignColumns)).append(')')
                .append(" REFERENCES ").append(quotedTable(refTableId))
                .append('(').append(String.join(",", refColumns)).append(')');
        if (reference.referenceAction() != null) {
            appendReferenceAction(sql, reference.referenceAction());
        }
        afterStatements.add(sql.toString());
        return false;
    }

    /** Default names handed out by this listener, so two anonymous foreign keys never collide. */
    private final java.util.Set<String> defaultForeignKeyNames = new java.util.HashSet<>();

    /**
     * Name for an anonymous foreign key, following PostgreSQL's own convention
     * ({@code <table>_<col>[_<col>...]_fkey}). Two anonymous keys of one table used to both get
     * {@code <table>_fkey}, and the second ADD CONSTRAINT failed; a numeric suffix now breaks any
     * remaining tie (same table, same columns).
     */
    private String defaultForeignKeyName(TableId tableId, List<String> foreignColumns) {
        StringBuilder base = new StringBuilder(tableId.getTable());
        for (String column : foreignColumns) {
            base.append('_').append(StringUtil.unquote(column, '"'));
        }
        base.append("_fkey");
        String name = base.toString();
        for (int suffix = 1; !defaultForeignKeyNames.add(tableId.getSchema() + "." + name); suffix++) {
            name = base + String.valueOf(suffix);
        }
        return StringUtil.quote(name, '"');
    }

    private void appendReferenceAction(StringBuilder sql, MySqlParser.ReferenceActionContext action) {
        if (action.onDelete != null) {
            sql.append(" ON DELETE ").append(referenceControlText(action.onDelete));
        }
        if (action.onUpdate != null) {
            sql.append(" ON UPDATE ").append(referenceControlText(action.onUpdate));
        }
    }

    private static String referenceControlText(MySqlParser.ReferenceControlTypeContext ctx) {
        if (ctx.SET() != null && ctx.NULL_LITERAL() != null) {
            return "SET NULL";
        }
        if (ctx.SET() != null && ctx.DEFAULT() != null) {
            return "SET DEFAULT";
        }
        if (ctx.NO() != null) {
            return "NO ACTION";
        }
        return ctx.getText();
    }

    private boolean rewriteAlterByAddCheck(TableId tableId, MySqlParser.AlterByAddCheckTableConstraintContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (ctx.expression() == null) {
            message.report("CHECK constraint without expression dropped", Level.INFO);
            return false;
        }
        String expr = rewriteBackticks(rewriter.getTokenStream().getText(ctx.expression()));
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(quotedTable(tableId)).append(" ADD");
        if (ctx.name != null) {
            sql.append(" CONSTRAINT ").append(quoteIdent(ctx.name.getText()));
        }
        sql.append(" CHECK (").append(expr).append(')');
        afterStatements.add(sql.toString());
        if (ctx.NOT() != null || ctx.ENFORCED() != null) {
            message.report("CHECK ENFORCED/NOT ENFORCED dropped", Level.INFO);
        }
        return false;
    }

    private boolean rewriteAlterByDropPrimaryKey(TableId tableId, MySqlParser.AlterByDropPrimaryKeyContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        String constraintName = StringUtil.quote(tableId.getTable() + "_pkey", '"');
        afterStatements.add("ALTER TABLE " + quotedTable(tableId) + " DROP CONSTRAINT " + constraintName);
        message.report(
                "DROP PRIMARY KEY assumes PostgreSQL default name " + constraintName,
                Level.INFO);
        return false;
    }

    private boolean rewriteAlterByDropIndex(TableId tableId, MySqlParser.AlterByDropIndexContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        String mysqlName = StringUtil.unquote(ctx.uid().getText(), '`');
        afterStatements.add("DROP INDEX " + pgIndexName(tableId, mysqlName));
        return false;
    }

    private void rewriteAlterByDropForeignKey(MySqlParser.AlterByDropForeignKeyContext ctx) {
        rewriter.replace(ctx.getStart(), ctx.getStop(),
                "DROP CONSTRAINT " + quoteIdent(ctx.uid().getText()));
    }

    private void rewriteAlterByDropConstraintCheck(MySqlParser.AlterByDropConstraintCheckContext ctx) {
        rewriter.replace(ctx.getStart(), ctx.getStop(),
                "DROP CONSTRAINT " + quoteIdent(ctx.uid().getText()));
    }

    private void rewriteAlterByRename(TableId tableId, MySqlParser.AlterByRenameContext ctx) {
        String newNameText = ctx.fullId() != null ? ctx.fullId().getText() : ctx.uid().getText();
        TableId newTableId = tableNamingStrategy.resolveTableId(parseTableId(newNameText));
        // Keep schema of the original table when MySQL only supplies an unqualified new name.
        if (newTableId.getCatalog() == null && newTableId.getSchema() == null) {
            newTableId = new TableId(tableId.getCatalog(), tableId.getSchema(), newTableId.getTable());
            newTableId = tableNamingStrategy.resolveTableId(newTableId);
        }
        rewriter.replace(ctx.getStart(), ctx.getStop(),
                "RENAME TO " + StringUtil.quote(newTableId.getTable(), '"'));
    }

    private void rewriteAlterByRenameColumn(MySqlParser.AlterByRenameColumnContext ctx) {
        rewriter.replace(ctx.oldColumn.getStart(), ctx.oldColumn.getStop(),
                quoteIdent(ctx.oldColumn.getText()));
        rewriter.replace(ctx.newColumn.getStart(), ctx.newColumn.getStop(),
                quoteIdent(ctx.newColumn.getText()));
    }

    private boolean rewriteAlterByRenameIndex(TableId tableId, MySqlParser.AlterByRenameIndexContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        String oldName = StringUtil.unquote(ctx.uid(0).getText(), '`');
        String newName = StringUtil.unquote(ctx.uid(1).getText(), '`');
        afterStatements.add("ALTER INDEX " + pgIndexName(tableId, oldName)
                + " RENAME TO " + pgIndexName(tableId, newName));
        return false;
    }

    private void rewriteAlterByChangeDefault(MySqlParser.AlterByChangeDefaultContext ctx) {
        rewriter.replace(ctx.uid().getStart(), ctx.uid().getStop(), quoteIdent(ctx.uid().getText()));
        if (ctx.defaultValue() != null && ctx.defaultValue().ON() != null) {
            comment(ctx.defaultValue().ON().getSymbol(), ctx.defaultValue().getStop());
        }
    }

    private void rewriteAlterByAlterColumnDefault(MySqlParser.AlterByAlterColumnDefaultContext ctx) {
        rewriter.replace(ctx.uid().getStart(), ctx.uid().getStop(), quoteIdent(ctx.uid().getText()));
        if (ctx.VISIBLE() != null || ctx.INVISIBLE() != null) {
            // MySQL invisible columns have no PG equivalent inside ALTER DEFAULT.
            if (ctx.VISIBLE() != null) {
                rewriter.delete(ctx.SET().getSymbol());
                rewriter.delete(ctx.VISIBLE().getSymbol());
            }
            if (ctx.INVISIBLE() != null) {
                rewriter.delete(ctx.SET().getSymbol());
                rewriter.delete(ctx.INVISIBLE().getSymbol());
            }
            message.report("ALTER COLUMN VISIBLE/INVISIBLE dropped", Level.INFO);
        }
    }

    private static String quotedTable(TableId tableId) {
        return tableId.toQuoted('"').toString();
    }

    private static String quoteIdent(String raw) {
        return StringUtil.quote(StringUtil.unquote(raw, '`'), '"');
    }

    private static String pgIndexName(TableId tableId, String mysqlIndexName) {
        return StringUtil.quote(tableId.getTable() + "_" + mysqlIndexName, '"');
    }

    private static String rewriteBackticks(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '`') {
                int end = sql.indexOf('`', i + 1);
                if (end < 0) {
                    out.append(c);
                    continue;
                }
                out.append('"').append(sql, i + 1, end).append('"');
                i = end;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String abbreviateSql(String sql) {
        String compact = sql.replaceAll("\\s+", " ").trim();
        return compact.length() <= 80 ? compact : compact.substring(0, 77) + "...";
    }

    /**
     * True when the ALTER has no specification at all, or nothing but table options — i.e. after the
     * options are commented out there is no action left for PostgreSQL to run.
     */
    private static boolean onlyTableOptions(List<MySqlParser.AlterSpecificationContext> specifications) {
        for (MySqlParser.AlterSpecificationContext specification : specifications) {
            if (!(specification instanceof MySqlParser.AlterByTableOptionContext)) {
                return false;
            }
        }
        return true;
    }

    private void rewriteAlterByDropColumn( MySqlParser.AlterByDropColumnContext ctx) {
        String columnFullName = columnNamingStrategy.resolveColumnName(
                StringUtil.unquote(ctx.uid().getText(), '`'));
        rewriter.replace(ctx.uid().getStart(), ctx.uid().getStop(), StringUtil.quote(columnFullName, '"'));
        if (ctx.RESTRICT() != null) {
            rewriter.delete(ctx.RESTRICT().getSymbol());
        }
    }
    private void rewriteAlterByAddColumns(MySqlParser.AlterByAddColumnsContext ctx) {
        for (int i = 0; i < ctx.uid().size(); i++) {
            String columnFullName = ctx.uid(i).getText();
            rewriteColumnDefinition(columnFullName, ctx.columnDefinition(i));
        }

    }
    private void rewriteAlterByAddColumn(MySqlParser.AlterByAddColumnContext ctx) {
        String columnFullName = StringUtil.unquote(ctx.uid().get(0).getText(), '`');
        columnFullName = columnNamingStrategy.resolveColumnName(columnFullName);
        rewriter.replace(ctx.uid().get(0).getStart(), ctx.uid().get(0).getStop(), StringUtil.quote(columnFullName, '"'));
        stripFirstAfter(ctx.FIRST(), ctx.AFTER(), ctx.uid().size() > 1 ? ctx.uid(1) : null);
        rewriteColumnDefinition(columnFullName, ctx.columnDefinition());
    }
    private void rewriteAlterByChangeColumn(MySqlParser.AlterByChangeColumnContext ctx) {
        final TableId tableId = context.get(TABLE_ID);
        String oldColumnName = quoteIdent(ctx.oldColumn.getText());
        String newColumnName = quoteIdent(ctx.newColumn.getText());
        // RENAME must run before the type change; emit it as its own statement.
        beforeStatements.add(String.format(
                "ALTER TABLE %s RENAME COLUMN %s TO %s",
                quotedTable(tableId), oldColumnName, newColumnName));
        String alterColumn = ctx.COLUMN() != null
                ? "ALTER COLUMN " + newColumnName
                : "ALTER " + newColumnName;
        rewriter.replace(ctx.CHANGE().getSymbol(), ctx.newColumn.getStop(), alterColumn);
        rewriter.insertBefore(ctx.columnDefinition().getStart(), "TYPE ");
        stripFirstAfter(ctx.FIRST(), ctx.AFTER(), ctx.afterColumn);
        rewriteColumnDefinition(newColumnName, ctx.columnDefinition());
    }
    private void rewriteAlterByModifyColumn(MySqlParser.AlterByModifyColumnContext ctx) {
        rewriter.replace(ctx.MODIFY().getSymbol(), "ALTER");
        // First uid is the column; a second uid is only present with AFTER <col>.
        MySqlParser.UidContext columnUid = ctx.uid(0);
        String columnName = columnNamingStrategy.resolveColumnName(
                StringUtil.unquote(columnUid.getText(), '`'));
        rewriter.replace(columnUid.getStart(), columnUid.getStop(), StringUtil.quote(columnName, '"'));
        rewriter.insertBefore(ctx.columnDefinition().getStart(), "TYPE ");
        stripFirstAfter(ctx.FIRST(), ctx.AFTER(), ctx.uid().size() > 1 ? ctx.uid(1) : null);
        rewriteColumnDefinition(columnName, ctx.columnDefinition());
    }

    private void stripFirstAfter(TerminalNode first, TerminalNode after, MySqlParser.UidContext afterColumn) {
        if (first != null) {
            rewriter.delete(first.getSymbol());
            message.report("FIRST column position dropped", Level.INFO);
        }
        if (after != null) {
            rewriter.delete(after.getSymbol());
            if (afterColumn != null) {
                rewriter.delete(afterColumn.getStart(), afterColumn.getStop());
            }
            message.report("AFTER column position dropped", Level.INFO);
        }
    }

    private void rewriteColumnDefinition(String fullColumnName, MySqlParser.ColumnDefinitionContext ctx) {
        final ParserRuleContext parserRuleContext = context.get(PARSE_RULE_CONTEXT);
        List<MySqlParser.ColumnConstraintContext> columnConstraintContexts = ctx.columnConstraint();
        for (MySqlParser.ColumnConstraintContext columnConstraintContext : columnConstraintContexts) {
            if (columnConstraintContext instanceof MySqlParser.PrimaryKeyColumnConstraintContext) {
                if (includePrimaryKey) {
                    rewriter.replace(columnConstraintContext.getStart(), columnConstraintContext.getStop(), "PRIMARY KEY");
                } else {
                    rewriter.delete(columnConstraintContext.getStart(), columnConstraintContext.getStop());
                }
            }
            if (columnConstraintContext instanceof MySqlParser.UniqueKeyColumnConstraintContext) {
                if (includeUniqueKeys) {
                    rewriter.replace(columnConstraintContext.getStart(), columnConstraintContext.getStop(), "UNIQUE");
                } else {
                    rewriter.delete(columnConstraintContext.getStart(), columnConstraintContext.getStop());
                }
            }
            if (columnConstraintContext instanceof MySqlParser.CommentColumnConstraintContext) {
                rewriteCommentConstraint((MySqlParser.CommentColumnConstraintContext) columnConstraintContext, fullColumnName);
            }
            if (columnConstraintContext instanceof MySqlParser.AutoIncrementColumnConstraintContext) {
                rewriteAutoIncrementColumnConstraint((MySqlParser.AutoIncrementColumnConstraintContext) columnConstraintContext, fullColumnName);
            }
            if (columnConstraintContext instanceof MySqlParser.DefaultColumnConstraintContext) {
                rewriteDefaultColumnConstraint((MySqlParser.DefaultColumnConstraintContext) columnConstraintContext);
            }
        }
        rewriteDataType(parserRuleContext, fullColumnName, ctx.dataType());
    }

    private void rewriteDefaultColumnConstraint(MySqlParser.DefaultColumnConstraintContext ctx) {
        MySqlParser.DefaultValueContext defaultValueContext = ctx.defaultValue();
        if (defaultValueContext.ON() != null) {
            comment(defaultValueContext.ON().getSymbol(), ctx.getStop());
        }
    }

    private void rewriteAutoIncrementColumnConstraint(MySqlParser.AutoIncrementColumnConstraintContext ctx, String fullColumnName) {
        TableId tableId = context.get(TABLE_ID);
        MySqlParser.TableOptionAutoIncrementContext tableOptionAutoIncrementContext = context.get(TABLE_OPTION_AUTOINCREMENT_CONTEXT);
        String columnName = columnNamingStrategy.resolveColumnName(StringUtil.unquote(fullColumnName));
        String sequenceName = tableId.getTable() + "_" + columnName + "_sequence";
        long initialValue = 1;
        if (tableOptionAutoIncrementContext != null
                && tableOptionAutoIncrementContext.decimalLiteral() != null
                && tableOptionAutoIncrementContext.decimalLiteral().DECIMAL_LITERAL() != null) {
            initialValue = Long.parseLong(tableOptionAutoIncrementContext.decimalLiteral().DECIMAL_LITERAL().getText());
        }
        beforeStatements.add(String.format(CREATE_SEQUENCE_TEMPLATE, StringUtil.quote(sequenceName, '"'), initialValue, initialValue));
        afterStatements.add(String.format(ALTER_SEQUENCE_OWNER_TEMPLATE, StringUtil.quote(sequenceName, '"'),
            StringUtil.quote(tableId.getTable(), '"'), StringUtil.quote(columnName, '"')));
        rewriter.replace(ctx.getStart(), ctx.getStop(), String.format("default nextval('%s')", sequenceName));
    }

    private void rewriteCommentConstraint(MySqlParser.CommentColumnConstraintContext columnConstraintContext, String fullColumnName) {
        TableId tableId = context.get(TABLE_ID);
        String columnName = StringUtil.quote(fullColumnName, '"');
        String comment = columnConstraintContext.STRING_LITERAL().getText();
        rewriter.delete(columnConstraintContext.getStart(), columnConstraintContext.getStop());
        if (!includeComments) {
            return;
        }
        afterStatements.add(String.format(CREATE_COMMENT_ON_COLUMN_TEMPLATE,
                StringUtil.quote(tableId.getTable(), '"'), columnName, comment));
    }

    @Override
    public void enterTableOptionComment(MySqlParser.TableOptionCommentContext ctx) {
        TableId tableId = context.get(TABLE_ID);
        String comment = ctx.STRING_LITERAL().getText();
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (!includeComments) {
            return;
        }
        afterStatements.add(String.format(CREATE_COMMENT_ON_TABLE_TEMPLATE,
                StringUtil.quote(tableId.getTable(), '"') , comment));
    }

    @Override
    public void enterTableOptionRowFormat(MySqlParser.TableOptionRowFormatContext ctx) {
        dropOrCommentTableOption(ctx);
    }

    @Override
    public void exitTableOptionAutoIncrement(MySqlParser.TableOptionAutoIncrementContext ctx) {
        dropOrCommentTableOption(ctx);
    }

    @Override
    public void exitTableOptionAutoextendSize(MySqlParser.TableOptionAutoextendSizeContext ctx) {
        dropOrCommentTableOption(ctx);
    }
    private void rewriteDataType(ParserRuleContext parserRuleContext, String fullColumnName, MySqlParser.DataTypeContext dataTypeContext) {
        if (dataTypeContext instanceof MySqlParser.DimensionDataTypeContext) {
            rewriteDimensionDataType((MySqlParser.DimensionDataTypeContext) dataTypeContext);
        }
        if (dataTypeContext instanceof MySqlParser.StringDataTypeContext) {
            rewriteStringDataTypeContext((MySqlParser.StringDataTypeContext) dataTypeContext);
        }
        if (dataTypeContext instanceof MySqlParser.CollectionDataTypeContext) {
            rewriteCollectionDataType(fullColumnName, (MySqlParser.CollectionDataTypeContext) dataTypeContext);
        }
        if (dataTypeContext instanceof MySqlParser.SimpleDataTypeContext) {
            rewriteSimpleDataType((MySqlParser.SimpleDataTypeContext) dataTypeContext);
        }
    }

    private void rewriteSimpleDataType(MySqlParser.SimpleDataTypeContext ctx) {
        if (MySqlDataTypeToPGMap.contains(ctx.typeName.getType())) {
            rewriter.replace(ctx.getStart(), ctx.getStop(), MySqlDataTypeToPGMap.get(ctx.typeName.getType()));
        }
    }

    @Override
    public void exitTableOptionCharset(MySqlParser.TableOptionCharsetContext ctx) {
        dropOrCommentTableOption(ctx);
    }

    /**
     * CREATE TABLE keeps MySQL table options as comments for traceability; ALTER TABLE drops them
     * so comma compaction cannot leave a stray {@code /* ... * /} around a deleted clause.
     */
    private void dropOrCommentTableOption(ParserRuleContext ctx) {
        if (within(ctx, MySqlParser.AlterTableContext.class)) {
            rewriter.delete(ctx.getStart(), ctx.getStop());
        } else {
            comment(ctx.getStart(), ctx.getStop());
        }
    }

    /**
     * @param rewriteNames whether to quote the column names in the original text. Must be
     *     {@code false} when the caller deletes the surrounding node: {@link TokenStreamRewriter}
     *     rejects a replace that overlaps a delete, and the rewritten text is discarded anyway.
     */
    private List<String> getColumnNamesFromIndexColumnNameContext(
            MySqlParser.IndexColumnNamesContext ctx, boolean rewriteNames) {
        List<String> columnNames = new ArrayList<>(ctx.indexColumnName().size());
        for (MySqlParser.IndexColumnNameContext indexColumnNameContext : ctx.indexColumnName()) {
            String columnName = indexColumnNameContext.uid().getText();
            if (rewriteNames) {
                rewriter.replace(indexColumnNameContext.uid().getStart(), indexColumnNameContext.uid().getStop(), StringUtil.quote(columnName, '"'));
            }
            if (indexColumnNameContext.sortType != null) {
                columnName = StringUtil.quote(StringUtil.unquote(columnName, '`'), '"') + " " + indexColumnNameContext.sortType.getText();
            } else {
                columnName = StringUtil.quote(StringUtil.unquote(columnName, '`'), '"');
            }
            columnNames.add(columnNamingStrategy.resolveColumnName(columnName));
        }
        return columnNames;
    }


    /**
     * MySQL {@code CREATE PROCEDURE} becomes a PostgreSQL {@code CREATE PROCEDURE} with a shared
     * PL/pgSQL body emitter; see {@link MySqlToPGRoutineTranslator#translateProcedure}.
     */
    @Override
    public void exitCreateProcedure(MySqlParser.CreateProcedureContext ctx) {
        MySqlToPGRoutineTranslator translator = new MySqlToPGRoutineTranslator(rewriter.getTokenStream(), message);
        String translated = translator.translateProcedure(ctx);
        rewriter.replace(ctx.getStart(), ctx.getStop(), translated);
        if (translator.getCommentStatement() != null) {
            afterStatements.add(translator.getCommentStatement());
        }
    }

    /**
     * MySQL {@code CREATE TABLE ... PARTITION BY ...} → PostgreSQL declarative partitioning.
     * P0: RANGE/LIST (bare columns or COLUMNS) and HASH (bare column); KEY / LINEAR /
     * expression keys / SUBPARTITION are stripped to a flat table with a WARNING.
     *
     * <p>Runs before {@link #exitColumnCreateTable}, so {@code PARTITION OF} children land in
     * {@link #afterStatements} ahead of indexes / FKs / comments emitted there.
     */
    @Override
    public void exitPartitionDefinitions(MySqlParser.PartitionDefinitionsContext ctx) {
        // Trailing PARTITION BY on ALTER TABLE is removed in exitAlterTable.
        if (within(ctx, MySqlParser.AlterTableContext.class)) {
            return;
        }
        TableId tableId = context.get(TABLE_ID);
        if (tableId == null) {
            rewriter.delete(ctx.getStart(), ctx.getStop());
            message.report("partition clause dropped (missing table context)", Level.WARNING);
            return;
        }
        if (ctx.subpartitionFunctionDefinition() != null || hasInlineSubpartitions(ctx)) {
            stripPartitioning(ctx, "SUBPARTITION is not converted; creating a non-partitioned table");
            return;
        }
        MySqlParser.PartitionFunctionDefinitionContext fn = ctx.partitionFunctionDefinition();
        if (fn instanceof MySqlParser.PartitionFunctionKeyContext) {
            stripPartitioning(ctx, "PARTITION BY KEY is not converted; creating a non-partitioned table");
            return;
        }
        if (fn instanceof MySqlParser.PartitionFunctionHashContext) {
            MySqlParser.PartitionFunctionHashContext hash = (MySqlParser.PartitionFunctionHashContext) fn;
            if (hash.LINEAR() != null) {
                stripPartitioning(ctx,
                        "LINEAR HASH partitioning is not converted; creating a non-partitioned table");
                return;
            }
            if (!isBareColumnExpression(hash.expression())) {
                stripPartitioning(ctx,
                        "partition expression is not a bare column; creating a non-partitioned table");
                return;
            }
            rewriteHashPartitioning(tableId, ctx, hash);
            return;
        }
        if (fn instanceof MySqlParser.PartitionFunctionRangeContext) {
            MySqlParser.PartitionFunctionRangeContext range = (MySqlParser.PartitionFunctionRangeContext) fn;
            String keys = partitionKeyList(range.COLUMNS() != null, range.uidList(), range.expression());
            if (keys == null) {
                stripPartitioning(ctx,
                        "partition expression is not a bare column; creating a non-partitioned table");
                return;
            }
            rewriteRangePartitioning(tableId, ctx, keys);
            return;
        }
        if (fn instanceof MySqlParser.PartitionFunctionListContext) {
            MySqlParser.PartitionFunctionListContext list = (MySqlParser.PartitionFunctionListContext) fn;
            String keys = partitionKeyList(list.COLUMNS() != null, list.uidList(), list.expression());
            if (keys == null) {
                stripPartitioning(ctx,
                        "partition expression is not a bare column; creating a non-partitioned table");
                return;
            }
            rewriteListPartitioning(tableId, ctx, keys);
            return;
        }
        stripPartitioning(ctx, "unsupported PARTITION BY form; creating a non-partitioned table");
    }

    private static boolean isPartitionAlterSpecification(MySqlParser.AlterSpecificationContext spec) {
        // ADD/DROP/TRUNCATE PARTITION are handled explicitly in rewriteAlterSpecification.
        return spec instanceof MySqlParser.AlterByDiscardPartitionContext
                || spec instanceof MySqlParser.AlterByImportPartitionContext
                || spec instanceof MySqlParser.AlterByCoalescePartitionContext
                || spec instanceof MySqlParser.AlterByReorganizePartitionContext
                || spec instanceof MySqlParser.AlterByExchangePartitionContext
                || spec instanceof MySqlParser.AlterByAnalyzePartitionContext
                || spec instanceof MySqlParser.AlterByCheckPartitionContext
                || spec instanceof MySqlParser.AlterByOptimizePartitionContext
                || spec instanceof MySqlParser.AlterByRebuildPartitionContext
                || spec instanceof MySqlParser.AlterByRepairPartitionContext
                || spec instanceof MySqlParser.AlterByRemovePartitioningContext
                || spec instanceof MySqlParser.AlterByUpgradePartitioningContext;
    }

    /**
     * P1: {@code ADD PARTITION} with LIST {@code VALUES IN} → {@code CREATE TABLE … PARTITION OF … FOR VALUES IN}.
     * RANGE/HASH (and anything else) stay dropped with INFO — RANGE needs catalog for FROM bounds.
     */
    private boolean rewriteAlterByAddPartition(
            TableId tableId, MySqlParser.AlterByAddPartitionContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        List<MySqlParser.PartitionDefinitionContext> defs = ctx.partitionDefinition();
        for (MySqlParser.PartitionDefinitionContext def : defs) {
            if (!(def instanceof MySqlParser.PartitionListAtomContext)
                    && !(def instanceof MySqlParser.PartitionListVectorContext)) {
                message.report(
                        "unsupported ALTER TABLE partition clause dropped: "
                                + abbreviateSql(ctx.getText()),
                        Level.INFO);
                return false;
            }
        }
        boolean notedOptions = false;
        String parent = quotedTable(tableId);
        for (MySqlParser.PartitionDefinitionContext def : defs) {
            List<? extends MySqlParser.PartitionOptionContext> options = partitionOptionsOf(def);
            if (!notedOptions && options != null && !options.isEmpty()) {
                message.report(
                        "partition-level ENGINE/DIRECTORY/TABLESPACE options dropped", Level.INFO);
                notedOptions = true;
            }
            if (def instanceof MySqlParser.PartitionListAtomContext) {
                MySqlParser.PartitionListAtomContext atom = (MySqlParser.PartitionListAtomContext) def;
                String uid = StringUtil.unquote(atom.uid().getText(), '`');
                afterStatements.add("CREATE TABLE " + childPartitionTable(tableId, uid)
                        + " PARTITION OF " + parent
                        + " FOR VALUES IN (" + formatPartitionBound(atom.partitionDefinerAtom()) + ")");
            } else {
                MySqlParser.PartitionListVectorContext vector =
                        (MySqlParser.PartitionListVectorContext) def;
                String uid = StringUtil.unquote(vector.uid().getText(), '`');
                List<String> tuples = new ArrayList<>();
                for (MySqlParser.PartitionDefinerVectorContext v : vector.partitionDefinerVector()) {
                    tuples.add("(" + formatPartitionBound(v.partitionDefinerAtom()) + ")");
                }
                afterStatements.add("CREATE TABLE " + childPartitionTable(tableId, uid)
                        + " PARTITION OF " + parent
                        + " FOR VALUES IN (" + String.join(", ", tuples) + ")");
            }
        }
        return false;
    }

    /** P1: {@code DROP PARTITION uid[,…]} → {@code DROP TABLE} each child named like P0 CREATE. */
    private boolean rewriteAlterByDropPartition(
            TableId tableId, MySqlParser.AlterByDropPartitionContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        for (MySqlParser.UidContext uid : ctx.uidList().uid()) {
            String name = StringUtil.unquote(uid.getText(), '`');
            afterStatements.add("DROP TABLE " + childPartitionTable(tableId, name));
        }
        return false;
    }

    /**
     * P1: {@code TRUNCATE PARTITION uid[,…]} → {@code TRUNCATE TABLE} each child.
     * {@code ALL} stays INFO-dropped (no catalog to enumerate children).
     */
    private boolean rewriteAlterByTruncatePartition(
            TableId tableId, MySqlParser.AlterByTruncatePartitionContext ctx) {
        rewriter.delete(ctx.getStart(), ctx.getStop());
        if (ctx.ALL() != null) {
            message.report(
                    "TRUNCATE PARTITION ALL dropped (no catalog to enumerate children)", Level.INFO);
            return false;
        }
        for (MySqlParser.UidContext uid : ctx.uidList().uid()) {
            String name = StringUtil.unquote(uid.getText(), '`');
            afterStatements.add("TRUNCATE TABLE " + childPartitionTable(tableId, name));
        }
        return false;
    }

        private void stripPartitioning(MySqlParser.PartitionDefinitionsContext ctx, String warning) {
        notePartitionOptions(ctx);
        rewriter.delete(ctx.getStart(), ctx.getStop());
        message.report(warning, Level.WARNING);
    }

    private void rewriteHashPartitioning(
            TableId tableId,
            MySqlParser.PartitionDefinitionsContext ctx,
            MySqlParser.PartitionFunctionHashContext hash) {
        notePartitionOptions(ctx);
        String key = quotePartitionColumn(hash.expression().getText());
        List<String> names = new ArrayList<>();
        List<MySqlParser.PartitionDefinitionContext> defs = ctx.partitionDefinition();
        if (defs != null && !defs.isEmpty()) {
            for (MySqlParser.PartitionDefinitionContext def : defs) {
                if (!(def instanceof MySqlParser.PartitionSimpleContext)) {
                    stripPartitioning(ctx,
                            "HASH partition definition is not convertible; creating a non-partitioned table");
                    return;
                }
                names.add(StringUtil.unquote(
                        ((MySqlParser.PartitionSimpleContext) def).uid().getText(), '`'));
            }
        } else if (ctx.count != null) {
            int n = Integer.parseInt(ctx.count.getText());
            for (int i = 0; i < n; i++) {
                names.add("p" + i);
            }
        } else {
            stripPartitioning(ctx,
                    "HASH partitioning requires PARTITIONS n or explicit partitions; "
                            + "creating a non-partitioned table");
            return;
        }
        int modulus = names.size();
        rewriter.replace(ctx.getStart(), ctx.getStop(), "PARTITION BY HASH (" + key + ")");
        message.report(
                "HASH partition mapping uses PostgreSQL hash; values may land in different "
                        + "partitions than MySQL",
                Level.WARNING);
        String parent = quotedTable(tableId);
        for (int i = 0; i < names.size(); i++) {
            afterStatements.add("CREATE TABLE " + childPartitionTable(tableId, names.get(i))
                    + " PARTITION OF " + parent
                    + " FOR VALUES WITH (MODULUS " + modulus + ", REMAINDER " + i + ")");
        }
    }

    private void rewriteRangePartitioning(
            TableId tableId, MySqlParser.PartitionDefinitionsContext ctx, String keys) {
        notePartitionOptions(ctx);
        List<MySqlParser.PartitionDefinitionContext> defs = ctx.partitionDefinition();
        if (defs == null || defs.isEmpty()) {
            stripPartitioning(ctx,
                    "RANGE partitioning requires explicit partition definitions; "
                            + "creating a non-partitioned table");
            return;
        }
        List<String> fromBounds = new ArrayList<>();
        List<String> toBounds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int arity = 1;
        MySqlParser.PartitionDefinitionContext first = defs.get(0);
        if (first instanceof MySqlParser.PartitionComparisonContext) {
            arity = Math.max(1,
                    ((MySqlParser.PartitionComparisonContext) first).partitionDefinerAtom().size());
        }
        String prev = minValueBound(arity);
        for (MySqlParser.PartitionDefinitionContext def : defs) {
            if (!(def instanceof MySqlParser.PartitionComparisonContext)) {
                stripPartitioning(ctx,
                        "RANGE partition definition is not convertible; creating a non-partitioned table");
                return;
            }
            MySqlParser.PartitionComparisonContext cmp = (MySqlParser.PartitionComparisonContext) def;
            String bound = formatPartitionBound(cmp.partitionDefinerAtom());
            names.add(StringUtil.unquote(cmp.uid().getText(), '`'));
            fromBounds.add(prev);
            toBounds.add(bound);
            prev = bound;
        }
        rewriter.replace(ctx.getStart(), ctx.getStop(), "PARTITION BY RANGE (" + keys + ")");
        String parent = quotedTable(tableId);
        for (int i = 0; i < names.size(); i++) {
            afterStatements.add("CREATE TABLE " + childPartitionTable(tableId, names.get(i))
                    + " PARTITION OF " + parent
                    + " FOR VALUES FROM (" + fromBounds.get(i) + ") TO (" + toBounds.get(i) + ")");
        }
    }

    private void rewriteListPartitioning(
            TableId tableId, MySqlParser.PartitionDefinitionsContext ctx, String keys) {
        notePartitionOptions(ctx);
        List<MySqlParser.PartitionDefinitionContext> defs = ctx.partitionDefinition();
        if (defs == null || defs.isEmpty()) {
            stripPartitioning(ctx,
                    "LIST partitioning requires explicit partition definitions; "
                            + "creating a non-partitioned table");
            return;
        }
        List<String> names = new ArrayList<>();
        List<String> valueLists = new ArrayList<>();
        for (MySqlParser.PartitionDefinitionContext def : defs) {
            if (def instanceof MySqlParser.PartitionListAtomContext) {
                MySqlParser.PartitionListAtomContext atom = (MySqlParser.PartitionListAtomContext) def;
                names.add(StringUtil.unquote(atom.uid().getText(), '`'));
                valueLists.add(formatPartitionBound(atom.partitionDefinerAtom()));
            } else if (def instanceof MySqlParser.PartitionListVectorContext) {
                MySqlParser.PartitionListVectorContext vector =
                        (MySqlParser.PartitionListVectorContext) def;
                names.add(StringUtil.unquote(vector.uid().getText(), '`'));
                List<String> tuples = new ArrayList<>();
                for (MySqlParser.PartitionDefinerVectorContext v : vector.partitionDefinerVector()) {
                    tuples.add("(" + formatPartitionBound(v.partitionDefinerAtom()) + ")");
                }
                valueLists.add(String.join(", ", tuples));
            } else {
                stripPartitioning(ctx,
                        "LIST partition definition is not convertible; creating a non-partitioned table");
                return;
            }
        }
        rewriter.replace(ctx.getStart(), ctx.getStop(), "PARTITION BY LIST (" + keys + ")");
        String parent = quotedTable(tableId);
        for (int i = 0; i < names.size(); i++) {
            afterStatements.add("CREATE TABLE " + childPartitionTable(tableId, names.get(i))
                    + " PARTITION OF " + parent
                    + " FOR VALUES IN (" + valueLists.get(i) + ")");
        }
    }

    /**
     * @return quoted key list, or {@code null} when the expression form is not a bare column
     */
    private String partitionKeyList(
            boolean columnsForm,
            MySqlParser.UidListContext uidList,
            MySqlParser.ExpressionContext expression) {
        if (columnsForm) {
            if (uidList == null || uidList.uid().isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>(uidList.uid().size());
            for (MySqlParser.UidContext uid : uidList.uid()) {
                parts.add(quotePartitionColumn(uid.getText()));
            }
            return String.join(", ", parts);
        }
        if (expression == null || !isBareColumnExpression(expression)) {
            return null;
        }
        return quotePartitionColumn(expression.getText());
    }

    private static boolean isBareColumnExpression(MySqlParser.ExpressionContext expression) {
        if (expression == null) {
            return false;
        }
        String text = expression.getText();
        if (text == null || text.isEmpty()) {
            return false;
        }
        // Function calls / operators contain '(' or other punctuation beyond a simple ident.
        String stripped = StringUtil.unquote(text.trim(), '`').trim();
        return stripped.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private String quotePartitionColumn(String raw) {
        String name = StringUtil.unquote(raw.trim(), '`');
        name = columnNamingStrategy.resolveColumnName(name);
        return StringUtil.quote(name, '"');
    }

    private String childPartitionTable(TableId parent, String partitionUid) {
        TableId child = tableNamingStrategy.resolveTableId(
                new TableId(parent.getCatalog(), parent.getSchema(),
                        parent.getTable() + "_" + partitionUid));
        return quotedTable(child);
    }

    private static String minValueBound(int arity) {
        if (arity <= 1) {
            return "MINVALUE";
        }
        List<String> parts = new ArrayList<>(arity);
        for (int i = 0; i < arity; i++) {
            parts.add("MINVALUE");
        }
        return String.join(", ", parts);
    }

    private static String formatPartitionBound(List<MySqlParser.PartitionDefinerAtomContext> atoms) {
        List<String> parts = new ArrayList<>(atoms.size());
        for (MySqlParser.PartitionDefinerAtomContext atom : atoms) {
            parts.add(formatPartitionAtom(atom));
        }
        return String.join(", ", parts);
    }

    private static String formatPartitionAtom(MySqlParser.PartitionDefinerAtomContext atom) {
        if (atom.MAXVALUE() != null) {
            return "MAXVALUE";
        }
        return rewriteBackticks(atom.getText());
    }

    private static boolean hasInlineSubpartitions(MySqlParser.PartitionDefinitionsContext ctx) {
        for (MySqlParser.PartitionDefinitionContext def : ctx.partitionDefinition()) {
            if (def instanceof MySqlParser.PartitionComparisonContext
                    && !((MySqlParser.PartitionComparisonContext) def).subpartitionDefinition().isEmpty()) {
                return true;
            }
            if (def instanceof MySqlParser.PartitionListAtomContext
                    && !((MySqlParser.PartitionListAtomContext) def).subpartitionDefinition().isEmpty()) {
                return true;
            }
            if (def instanceof MySqlParser.PartitionListVectorContext
                    && !((MySqlParser.PartitionListVectorContext) def).subpartitionDefinition().isEmpty()) {
                return true;
            }
            if (def instanceof MySqlParser.PartitionSimpleContext
                    && !((MySqlParser.PartitionSimpleContext) def).subpartitionDefinition().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void notePartitionOptions(MySqlParser.PartitionDefinitionsContext ctx) {
        boolean any = false;
        for (MySqlParser.PartitionDefinitionContext def : ctx.partitionDefinition()) {
            List<? extends MySqlParser.PartitionOptionContext> options = partitionOptionsOf(def);
            if (options != null && !options.isEmpty()) {
                any = true;
                break;
            }
        }
        if (any) {
            message.report("partition-level ENGINE/DIRECTORY/TABLESPACE options dropped", Level.INFO);
        }
    }

    private static List<? extends MySqlParser.PartitionOptionContext> partitionOptionsOf(
            MySqlParser.PartitionDefinitionContext def) {
        if (def instanceof MySqlParser.PartitionComparisonContext) {
            return ((MySqlParser.PartitionComparisonContext) def).partitionOption();
        }
        if (def instanceof MySqlParser.PartitionListAtomContext) {
            return ((MySqlParser.PartitionListAtomContext) def).partitionOption();
        }
        if (def instanceof MySqlParser.PartitionListVectorContext) {
            return ((MySqlParser.PartitionListVectorContext) def).partitionOption();
        }
        if (def instanceof MySqlParser.PartitionSimpleContext) {
            return ((MySqlParser.PartitionSimpleContext) def).partitionOption();
        }
        return null;
    }

        @Override
    public void exitRenameTable(MySqlParser.RenameTableContext ctx) {
        List<String> statements = new ArrayList<>();
        for (MySqlParser.RenameTableClauseContext clause : ctx.renameTableClause()) {
            TableId from = tableNamingStrategy.resolveTableId(
                    parseTableId(clause.tableName(0).fullId().getText()));
            TableId to = tableNamingStrategy.resolveTableId(
                    parseTableId(clause.tableName(1).fullId().getText()));
            if (to.getCatalog() == null && to.getSchema() == null) {
                to = tableNamingStrategy.resolveTableId(
                        new TableId(from.getCatalog(), from.getSchema(), to.getTable()));
            }
            statements.add("ALTER TABLE " + quotedTable(from) + " RENAME TO "
                    + StringUtil.quote(to.getTable(), '"'));
        }
        rewriter.replace(ctx.getStart(), ctx.getStop(), statements.get(0));
        for (int i = 1; i < statements.size(); i++) {
            afterStatements.add(statements.get(i));
        }
    }

    @Override
    public void exitTruncateTable(MySqlParser.TruncateTableContext ctx) {
        TableId tableId = tableNamingStrategy.resolveTableId(
                parseTableId(ctx.tableName().fullId().getText()));
        if (ctx.TABLE() == null) {
            rewriter.insertAfter(ctx.TRUNCATE().getSymbol(), " TABLE");
        }
        rewriter.replace(ctx.tableName().getStart(), ctx.tableName().getStop(),
                quotedTable(tableId));
    }

    /**
     * MySQL {@code CREATE TRIGGER} becomes a trigger function ({@code RETURNS TRIGGER}) plus a
     * follow-up {@code CREATE TRIGGER … EXECUTE FUNCTION …}; see
     * {@link MySqlToPGRoutineTranslator#translateTrigger}.
     */
    @Override
    public void exitCreateTrigger(MySqlParser.CreateTriggerContext ctx) {
        MySqlToPGRoutineTranslator translator = new MySqlToPGRoutineTranslator(rewriter.getTokenStream(), message);
        String translated = translator.translateTrigger(ctx);
        rewriter.replace(ctx.getStart(), ctx.getStop(), translated);
        if (translator.getCreateTriggerStatement() != null) {
            afterStatements.add(translator.getCreateTriggerStatement());
        }
    }

}
