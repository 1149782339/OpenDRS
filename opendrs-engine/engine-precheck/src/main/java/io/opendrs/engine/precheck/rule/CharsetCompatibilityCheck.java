package io.opendrs.engine.precheck.rule;

import io.opendrs.engine.precheck.CheckGroup;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.CheckMeta;
import io.opendrs.engine.precheck.CheckRule;
import io.opendrs.engine.precheck.Evaluation;
import io.opendrs.engine.precheck.Finding;
import io.opendrs.engine.precheck.PrecheckContext;
import io.opendrs.engine.precheck.Side;
import io.opendrs.engine.precheck.Verdict;
import io.opendrs.engine.precheck.fact.CharsetInfo;
import io.opendrs.engine.precheck.fact.FactStore;
import java.util.List;
import java.util.Locale;

/**
 * Whether the target encoding can hold the source characters.
 *
 * <p>The two products never use the same names - MySQL {@code utf8mb4} is PostgreSQL {@code UTF8} -
 * so a literal name comparison would fail on every healthy pair. What matters is convertibility:
 * everything MySQL can store fits in UTF8, while a target in {@code LATIN1} silently loses CJK data.
 */
public final class CharsetCompatibilityCheck implements CheckRule {

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.COMPAT_CHARSET, "源库与目标库的字符集是否一致", CheckGroup.PARAMETER, Side.BOTH);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        CharsetInfo source = facts.source().charset();
        CharsetInfo target = facts.target().charset();
        String observed = "源库 " + source.display() + " → 目标库 " + target.display();
        if (compatible(source.charset(), target.charset())) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(observed)));
        }
        return Evaluation.fail(
                "目标库字符集无法容纳源库字符",
                observed,
                "目标库为 UTF8",
                "把目标库编码改为 UTF8（或使用 UTF8 的模板库重建）后重新校验，否则中文等字符会写入失败");
    }

    /** UTF8 on the target accepts everything; otherwise only an identical (or ASCII) source is safe. */
    static boolean compatible(String sourceCharset, String targetCharset) {
        String target = normalise(targetCharset);
        if (target.isEmpty() || "utf8".equals(target) || "utf-8".equals(target) || "sql_ascii".equals(target)) {
            return true;
        }
        String source = normalise(sourceCharset);
        if (source.isEmpty() || "ascii".equals(source)) {
            return true;
        }
        return source.equals(target);
    }

    private static String normalise(String charset) {
        return charset == null ? "" : charset.trim().toLowerCase(Locale.ROOT);
    }
}
