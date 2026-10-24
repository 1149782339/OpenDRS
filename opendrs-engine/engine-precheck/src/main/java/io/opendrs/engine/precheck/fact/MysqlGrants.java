package io.opendrs.engine.precheck.fact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parsed {@code SHOW GRANTS} output. MySQL reports privileges as GRANT statements, not as a
 * capability matrix, so the only way to answer "can this user read schema X" without probing every
 * table is to read the scopes back out of those statements.
 *
 * <p>Scope is normalised to {@code *.*}, {@code db.*} or {@code db.table}; {@code ALL PRIVILEGES}
 * becomes the wildcard {@code *}.
 */
public final class MysqlGrants {

    private static final String ALL = "*";
    private static final String GLOBAL = "*.*";

    private record Entry(Set<String> privileges, String scope) {
    }

    private final List<Entry> entries;

    private MysqlGrants(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static MysqlGrants parse(List<String> statements) {
        List<Entry> entries = new ArrayList<>();
        if (statements == null) {
            return new MysqlGrants(entries);
        }
        for (String statement : statements) {
            Entry entry = parseLine(statement);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return new MysqlGrants(entries);
    }

    private static Entry parseLine(String statement) {
        if (statement == null || !statement.regionMatches(true, 0, "GRANT ", 0, 6)) {
            return null;
        }
        int on = indexOf(statement, " ON ");
        int to = indexOf(statement, " TO ");
        if (on < 0 || to < 0 || to <= on) {
            return null;
        }
        Set<String> privileges = parsePrivileges(statement.substring(6, on));
        String scope = normaliseScope(statement.substring(on + 4, to));
        if (privileges.isEmpty() || scope.isEmpty()) {
            return null;
        }
        return new Entry(privileges, scope);
    }

    private static Set<String> parsePrivileges(String text) {
        Set<String> privileges = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            String privilege = part.trim().toUpperCase(Locale.ROOT);
            if (privilege.isEmpty()) {
                continue;
            }
            // "ALL" and "ALL PRIVILEGES" grant everything, including privileges added later.
            privileges.add(privilege.startsWith("ALL") ? ALL : privilege);
        }
        return privileges;
    }

    private static String normaliseScope(String text) {
        return text.trim()
                .replace("`", "")
                .replace("\"", "")
                .replace("'", "")
                .replace(" ", "");
    }

    /** True when {@code privilege} is granted globally or on {@code schema} (schema-wide or per table). */
    public boolean has(String privilege, String schema) {
        String want = privilege.toUpperCase(Locale.ROOT);
        for (Entry entry : entries) {
            if (!entry.privileges.contains(ALL) && !entry.privileges.contains(want)) {
                continue;
            }
            if (covers(entry.scope, schema)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code privilege} is granted on {@code *.*}, i.e. for every schema. */
    public boolean hasGlobally(String privilege) {
        String want = privilege.toUpperCase(Locale.ROOT);
        for (Entry entry : entries) {
            if (!entry.privileges.contains(ALL) && !entry.privileges.contains(want)) {
                continue;
            }
            if (GLOBAL.equals(entry.scope)) {
                return true;
            }
        }
        return false;
    }

    private static boolean covers(String scope, String schema) {
        if (GLOBAL.equals(scope)) {
            return true;
        }
        if (schema == null || schema.isBlank()) {
            return false;
        }
        if (scope.equalsIgnoreCase(schema + ".*")) {
            return true;
        }
        // A table-level grant still lets us read that table.
        return scope.regionMatches(true, 0, schema + ".", 0, schema.length() + 1);
    }

    private static int indexOf(String text, String needle) {
        return text.toUpperCase(Locale.ROOT).indexOf(needle.toUpperCase(Locale.ROOT));
    }
}
