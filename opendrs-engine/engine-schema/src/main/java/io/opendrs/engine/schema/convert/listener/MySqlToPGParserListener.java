package io.opendrs.engine.schema.convert.listener;

import io.opendrs.engine.schema.convert.ConversionMessage;
import io.opendrs.engine.schema.convert.ConversionMessage.Level;
import io.opendrs.engine.schema.convert.ParserContext;
import io.opendrs.engine.schema.convert.ConversionConfiguration;
import io.opendrs.engine.schema.convert.parser.mysql.MySqlParser;
import io.opendrs.engine.schema.convert.parser.mysql.MySqlParserBaseListener;
import io.opendrs.common.naming.ColumnNamingStrategy;
import io.opendrs.common.naming.SchemaNamingStrategy;
import io.opendrs.common.naming.TableNamingStrategy;
import io.opendrs.common.metadata.TableId;
import io.opendrs.common.util.StringUtil;
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
            statements.add(rewriter.getText());
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
        rewriter.replace(ctx.UNIQUE().getSymbol(), "UNIQUE KEY");
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
        TableId refTableId = parseTableId(referenceDefinitionContext.tableName().fullId().getText());
        List<String> refColumnNames = getColumnNamesFromIndexColumnNameContext(referenceDefinitionContext.indexColumnNames(), false);
        List<String> foreignColumnNames = getColumnNamesFromIndexColumnNameContext(ctx.indexColumnNames(), false);
        String tableName = tableId.toQuoted('"').toString();
        String sql = String.format(ALTER_TABLE_FOREIGN_KEY, tableName,
                StringUtil.quote(ctx.name.simpleId().getText(), '"'),
                String.join(",", foreignColumnNames), refTableId.toQuoted('"'),
                String.join(",", refColumnNames));
        afterStatements.add(sql);
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
        comment(ctx.getStart(), ctx.getStop());
    }

    @Override
    public void exitTableOptionCollate(MySqlParser.TableOptionCollateContext ctx) {
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
        if (onlyTableOptions(alterSpecificationContexts)) {
            // `ALTER TABLE t AUTO_INCREMENT = 11` — and its ENGINE= / CHARSET= / COLLATE= /
            // ROW_FORMAT= siblings — carries nothing PostgreSQL can execute. The table option exit
            // handlers below comment each option out, which would leave a bare
            // `ALTER TABLE "test"."test" /* AUTO_INCREMENT = 11 */`; PostgreSQL rejects that with
            // "syntax error at end of input" and the whole CDC job dies. MySQL writes such a
            // statement to the binlog whenever a client resets the counter, so it does reach the
            // applier as a schema change event.
            rewrittenTextDiscarded = true;
            return;
        }
        rewriter.replace(ctx.tableName().getStart(), ctx.tableName().getStop(), tableId.toQuoted('"').identifier());
        for (MySqlParser.AlterSpecificationContext alterSpecificationContext : alterSpecificationContexts) {
            if (alterSpecificationContext instanceof MySqlParser.AlterByModifyColumnContext) {
                rewriteAlterByModifyColumn((MySqlParser.AlterByModifyColumnContext) alterSpecificationContext);
            }
            if (alterSpecificationContext instanceof MySqlParser.AlterByChangeColumnContext) {
                rewriteAlterByChangeColumn((MySqlParser.AlterByChangeColumnContext) alterSpecificationContext);
            }
            if (alterSpecificationContext instanceof MySqlParser.AlterByAddColumnContext) {
                rewriteAlterByAddColumn((MySqlParser.AlterByAddColumnContext) alterSpecificationContext);
            }
            if (alterSpecificationContext instanceof MySqlParser.AlterByDropColumnContext) {
                rewriteAlterByDropColumn((MySqlParser.AlterByDropColumnContext) alterSpecificationContext);
            }
            if (alterSpecificationContext instanceof MySqlParser.AlterByAddColumnsContext) {
                rewriteAlterByAddColumns((MySqlParser.AlterByAddColumnsContext) alterSpecificationContext);
            }
        }
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
        String columnFullName = ctx.uid().getText();
        rewriter.replace(ctx.uid().getStart(), ctx.uid().getStop(), StringUtil.quote(columnFullName, '"'));
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
        rewriteColumnDefinition(columnFullName, ctx.columnDefinition());
    }
    private void rewriteAlterByChangeColumn(MySqlParser.AlterByChangeColumnContext ctx) {
        final TableId tableId = context.get(TABLE_ID);
        final ParserRuleContext parserRuleContext = context.get(PARSE_RULE_CONTEXT);
        String oldColumnName = StringUtil.quote(ctx.oldColumn.getText(), '"');
        String newColumnName = StringUtil.quote(ctx.newColumn.getText(), '"');
        rewriter.insertBefore(parserRuleContext.getStart(), String.format(ALTER_TABLE_COLUMN_NAME, tableId, oldColumnName, newColumnName));
        rewriter.replace(ctx.CHANGE().getSymbol(), "ALTER");
        rewriter.delete(ctx.oldColumn.getStart(), ctx.oldColumn.getStop());
        rewriter.insertBefore(ctx.columnDefinition().getStart(), "TYPE ");
        rewriteColumnDefinition(newColumnName, ctx.columnDefinition());
    }
    private void rewriteAlterByModifyColumn(MySqlParser.AlterByModifyColumnContext ctx) {
        rewriter.replace(ctx.MODIFY().getSymbol(), "ALTER");
        List<String> fullColumnNames = new ArrayList<>(ctx.uid().size());
        for (MySqlParser.UidContext uidContext : ctx.uid()) {
            rewriter.replace(uidContext.getStart(), uidContext.getStop(),
                    StringUtil.quote(StringUtil.unquote(uidContext.getText(), '`'), '"'));
            fullColumnNames.add(uidContext.getText());
        }
        rewriter.insertBefore(ctx.columnDefinition().getStart(), "TYPE ");
        for (String fullColumnName : fullColumnNames) {
            rewriteColumnDefinition(fullColumnName, ctx.columnDefinition());
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
        comment(ctx.getStart(), ctx.getStop());
    }

    @Override
    public void exitTableOptionAutoIncrement(MySqlParser.TableOptionAutoIncrementContext ctx) {
        comment(ctx.getStart(), ctx.getStop());
    }

    @Override
    public void exitTableOptionAutoextendSize(MySqlParser.TableOptionAutoextendSizeContext ctx) {
        comment(ctx.getStart(), ctx.getStop());
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
        comment(ctx.getStart(),ctx.getStop());
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
