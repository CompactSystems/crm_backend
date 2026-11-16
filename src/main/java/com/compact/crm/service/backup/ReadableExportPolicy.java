package com.compact.crm.service.backup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

// What the readable export may contain - an explicit, reviewed decision for
// EVERY table and EVERY column of the CRM database. Fail-closed:
//  - a table that is not listed here, or a column of an exported table that is
//    not listed, stops the whole export before anything is written
//    (ReadableExporter reports exactly which names need a decision);
//  - a column is either INCLUDE (its value is exported) or WITHHOLD (the
//    column stays in the CSV, but every non-empty value reads "[withheld]";
//    the value itself is never even read from the database);
//  - a table is either exported, or EXCLUDED with a reason the README lists;
//  - a column whose name looks like a credential (password, token, secret,
//    verifier, API key, ...) can never be INCLUDE - building the policy fails.
// The policy is the single place to change when the schema changes; the
// coverage tests (ReadableExportPolicyTest, the PostgreSQL export test) fail
// when a table or column has no decision.
public final class ReadableExportPolicy {

    public static final String WITHHELD = "[withheld]";

    // Column names that can only be WITHHOLD.
    static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|verifier|api_?key|private_?key|credential|nonce|otp|salt).*");

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    public enum Treatment { INCLUDE, WITHHOLD }

    public record ColumnRule(String name, Treatment treatment, String reason) {
    }

    // An exported table. labelColumn: the column other tables' CSVs show next
    // to an id that points here (e.g. employees.name next to assigned_employee_id).
    // logicalReferences: column -> table it points to by id where the database
    // has no foreign key (shown in relationships.csv as not enforced).
    public record TableRule(String name, String description, Map<String, ColumnRule> columns,
                            String labelColumn, Map<String, String> logicalReferences) {
    }

    public record ExcludedTable(String name, String reason) {
    }

    private final Map<String, TableRule> tables;
    private final Map<String, ExcludedTable> excluded;

    private ReadableExportPolicy(Map<String, TableRule> tables, Map<String, ExcludedTable> excluded) {
        this.tables = Collections.unmodifiableMap(tables);
        this.excluded = Collections.unmodifiableMap(excluded);
    }

    public Optional<TableRule> table(String name) {
        return Optional.ofNullable(tables.get(name));
    }

    public Optional<ExcludedTable> excluded(String name) {
        return Optional.ofNullable(excluded.get(name));
    }

    public Collection<TableRule> tables() {
        return tables.values();
    }

    public Collection<ExcludedTable> excludedTables() {
        return excluded.values();
    }

    // Every table / column of the given schema (lower-case names) that has no
    // decision in this policy, as "table" or "table.column". Empty = covered.
    public List<String> unreviewed(Map<String, List<String>> columnsByTable) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> t : new TreeMap<>(columnsByTable).entrySet()) {
            if (excluded.containsKey(t.getKey())) {
                continue;
            }
            TableRule rule = tables.get(t.getKey());
            if (rule == null) {
                missing.add(t.getKey());
                continue;
            }
            for (String column : t.getValue()) {
                if (!rule.columns().containsKey(column)) {
                    missing.add(t.getKey() + "." + column);
                }
            }
        }
        return missing;
    }

    // ---- building --------------------------------------------------------

    public static final class Builder {

        private final Map<String, TableRule> tables = new TreeMap<>();
        private final Map<String, ExcludedTable> excluded = new TreeMap<>();

        public TableBuilder table(String name, String description) {
            return new TableBuilder(this, name, description);
        }

        public Builder exclude(String name, String reason) {
            checkName(name);
            requireReason(reason, name);
            if (tables.containsKey(name) || excluded.putIfAbsent(name, new ExcludedTable(name, reason)) != null) {
                throw new IllegalStateException("Table " + name + " has more than one policy");
            }
            return this;
        }

        public ReadableExportPolicy build() {
            for (TableRule t : tables.values()) {
                for (String target : t.logicalReferences().values()) {
                    if (!tables.containsKey(target) && !excluded.containsKey(target)) {
                        throw new IllegalStateException(t.name() + " refers to " + target + ", which has no policy");
                    }
                }
            }
            return new ReadableExportPolicy(tables, excluded);
        }
    }

    public static final class TableBuilder {

        private final Builder parent;
        private final String name;
        private final String description;
        private final Map<String, ColumnRule> columns = new LinkedHashMap<>();
        private final Map<String, String> logicalReferences = new LinkedHashMap<>();
        private String labelColumn;

        private TableBuilder(Builder parent, String name, String description) {
            checkName(name);
            requireReason(description, name);
            this.parent = parent;
            this.name = name;
            this.description = description;
        }

        public TableBuilder include(String... names) {
            for (String c : names) {
                checkName(c);
                if (SECRET_NAME.matcher(c).matches()) {
                    throw new IllegalStateException(name + "." + c + " looks like a credential and can only be withheld");
                }
                put(new ColumnRule(c, Treatment.INCLUDE, null));
            }
            return this;
        }

        public TableBuilder withhold(String column, String reason) {
            checkName(column);
            requireReason(reason, name + "." + column);
            put(new ColumnRule(column, Treatment.WITHHOLD, reason));
            return this;
        }

        public TableBuilder label(String column) {
            this.labelColumn = column;
            return this;
        }

        // `column` holds the id of a row in `table`, without a database foreign key.
        public TableBuilder refersTo(String column, String table) {
            checkName(table);
            logicalReferences.put(column, table);
            return this;
        }

        private void put(ColumnRule rule) {
            if (columns.putIfAbsent(rule.name(), rule) != null) {
                throw new IllegalStateException(name + "." + rule.name() + " has more than one policy");
            }
        }

        public Builder done() {
            if (labelColumn != null) {
                ColumnRule label = columns.get(labelColumn);
                if (label == null || label.treatment() != Treatment.INCLUDE) {
                    throw new IllegalStateException(name + "." + labelColumn + " is used as a name column but is not included");
                }
            }
            for (String c : logicalReferences.keySet()) {
                ColumnRule rule = columns.get(c);
                if (rule == null || rule.treatment() != Treatment.INCLUDE) {
                    throw new IllegalStateException(name + "." + c + " is a reference but is not included");
                }
            }
            if (parent.excluded.containsKey(name) || parent.tables.putIfAbsent(name, new TableRule(name, description,
                    Collections.unmodifiableMap(columns), labelColumn, Collections.unmodifiableMap(logicalReferences))) != null) {
                throw new IllegalStateException("Table " + name + " has more than one policy");
            }
            return parent;
        }
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalStateException("Invalid table or column name in the export policy: " + name);
        }
    }

    private static void requireReason(String reason, String what) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException(what + " needs a reason in the export policy");
        }
    }
}
