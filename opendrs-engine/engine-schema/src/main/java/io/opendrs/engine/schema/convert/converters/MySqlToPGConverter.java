package io.opendrs.engine.schema.convert.converters;

import io.opendrs.engine.schema.convert.*;
import io.opendrs.engine.schema.convert.listener.MySqlToPGParserListener;
import io.opendrs.engine.schema.convert.listener.ParserErrorListener;
import io.opendrs.engine.schema.convert.parser.mysql.MySqlLexer;
import io.opendrs.engine.schema.convert.parser.mysql.MySqlParser;
import io.opendrs.common.metadata.DatabaseType;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * MySQL to PostgreSQL DDL converter.
 *
 * <p>Structure-migration scripts that emit a plain {@code CREATE TABLE} followed by
 * {@code ALTER TABLE … PARTITION BY} are normalized first via
 * {@link DdlStatements#mergeCreateTableWithAlterPartitionBy}: the partition clause is folded onto
 * the CREATE so the existing CREATE-table partition rewriter runs. A lone
 * {@code ALTER … PARTITION BY} (CDC) is not merged and remains discarded with INFO inside the
 * listener.
 */
public class MySqlToPGConverter extends CommonSQLConverter {

    public MySqlToPGConverter(ConversionConfiguration configuration) {
        super(configuration);
    }

    @Override
    public ConversionResult convert(String statement) {
        if (statement == null || statement.isBlank()) {
            return ConversionResult.builder()
                    .statements(List.of())
                    .status(ConversionStatus.SUCCEEDED)
                    .infos(List.of())
                    .warnings(List.of())
                    .error("")
                    .build();
        }
        List<String> split = DdlStatements.splitStatements(statement);
        if (split.isEmpty()) {
            split = List.of(statement.trim());
        }
        List<String> merged = DdlStatements.mergeCreateTableWithAlterPartitionBy(split);
        if (merged.size() <= 1) {
            return convertSingle(merged.isEmpty() ? statement : merged.get(0));
        }
        return convertAll(merged);
    }

    private ConversionResult convertAll(List<String> statements) {
        List<String> out = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ConversionStatus status = ConversionStatus.SUCCEEDED;
        for (String stmt : statements) {
            ConversionResult part = convertSingle(stmt);
            out.addAll(part.getStatements());
            infos.addAll(part.getInfos());
            warnings.addAll(part.getWarnings());
            if (part.getErrors() != null && !part.getErrors().isBlank()) {
                errors.add(part.getErrors());
            }
            if (part.getStatus() == ConversionStatus.FAILED) {
                status = ConversionStatus.FAILED;
            }
        }
        return ConversionResult.builder()
                .statements(out)
                .status(status)
                .infos(infos)
                .warnings(warnings)
                .error(String.join("\n", errors))
                .build();
    }

    private ConversionResult convertSingle(String statement) {
        CodePointCharStream ddlContentCharStream = CharStreams.fromString(statement);
        Lexer lexer = new MySqlLexer(new CaseChangingCharStream(ddlContentCharStream, true));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ParserErrorListener());
        ParseTree parseTree = parser.root();
        ParseTreeWalker parseTreeWalker = new ParseTreeWalker();
        MySqlToPGParserListener parserListener = new MySqlToPGParserListener(tokens, configuration);
        ConversionStatus status = ConversionStatus.SUCCEEDED;
        String error = "";
        try {
            parseTreeWalker.walk(parserListener, parseTree);
        } catch (CancellationException e) {
            status = ConversionStatus.FAILED;
            error = e.getMessage();
        }
        ConversionMessage message = parserListener.getMessage();
        // FAILED-level messages mark constructs that cannot be approximately replaced; the
        // conversion is FAILED even though the remaining statements were still translated.
        List<String> errors = new ArrayList<>(message.getFailedMessages());
        if (status == ConversionStatus.FAILED && !error.isBlank()) {
            errors.add(error);
        }
        if (!errors.isEmpty()) {
            status = ConversionStatus.FAILED;
        }
        return ConversionResult.builder()
            .statements(parserListener.getStatements())
            .status(status)
            .infos(message.getInfoMessages())
            .warnings(message.getWarningMessages())
            .error(String.join("\n", errors))
            .build();
    }

    public static class MySqlToPGConverterProvider implements SQLConverterProvider {

        @Override
        public DatabaseType sourceDatabase() {
            return DatabaseType.MYSQL;
        }

        @Override
        public DatabaseType targetDatabase() {
            return DatabaseType.POSTGRESQL;
        }

        @Override
        public SQLConverter create(ConversionConfiguration configuration) {
            return new MySqlToPGConverter(configuration);
        }
    }

}
