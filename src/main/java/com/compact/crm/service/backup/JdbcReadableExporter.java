package com.compact.crm.service.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.sql.DataSource;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// The readable export, straight from the application's own datasource:
//  - ONE connection, ONE transaction, REPEATABLE READ + READ ONLY, so every
//    CSV comes from the same consistent moment and nothing can be written
//    (on PostgreSQL both settings are read back and checked before any data);
//  - the schema is discovered from the database itself (the core tables are
//    created by Hibernate, not by migrations) and checked against the export
//    policy BEFORE anything is written - an unreviewed table or column stops
//    the export;
//  - every table is read with a forward-only cursor (fetch size 500) and
//    streamed into the ZIP on disk, so memory use does not grow with the data;
//  - withheld columns are never read: the query only asks whether a value is
//    there (CASE WHEN col IS NULL ...), so a secret cannot reach the file;
//  - the ZIP is size-limited while it is written (crm.backup.readable-max-mb),
//    and the whole export has a time limit (crm.backup.readable-timeout).
// Error messages name tables, columns and SQLSTATEs only - never a host, user,
// URL or a raw driver message.
public class JdbcReadableExporter implements ReadableExporter {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final int FETCH_SIZE = 500;
    private static final int CHECK_EVERY_ROWS = 500;
    private static final int MAX_LISTED_NAMES = 25;

    private final DataSource dataSource;
    private final BackupProperties properties;
    private final ReadableExportPolicy policy;
    private final Clock clock;

    public JdbcReadableExporter(DataSource dataSource, BackupProperties properties, ReadableExportPolicy policy, Clock clock) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.policy = policy;
        this.clock = clock;
    }

    // ---- schema discovery ------------------------------------------------

    record Column(String name, String sqlName) {
    }

    record ForeignKey(String column, String table, String targetColumn, boolean enforced) {
    }

    record Table(String name, String sqlName, List<Column> columns, List<String> primaryKey, List<ForeignKey> foreignKeys) {
    }

    // Label columns next to an id: header, the referenced table and its label column.
    private record Label(String header, ForeignKey fk, String labelColumn) {
    }

    // Thrown by the size-limited stream once the ZIP is larger than allowed.
    static final class TooLargeException extends IOException {
        TooLargeException() {
            super("too large", null);
        }
    }

    @Override
    public Result export(Path zip, String rootFolder, long exportId) throws IOException, InterruptedException {

        if (!IDENTIFIER.matcher(rootFolder.replace("-", "_")).matches()) {
            throw new IllegalArgumentException("Invalid export folder name");
        }
        long deadline = System.nanoTime() + properties.getReadableTimeout().toNanos();
        long maxBytes = properties.getReadableMaxMb() * 1024L * 1024L;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return exportInSnapshot(c, zip, rootFolder, exportId, deadline, maxBytes);
            } finally {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // read-only: nothing to undo; the pool resets the connection
                }
            }
        } catch (TooLargeException e) {
            throw new BackupFailedException("The readable export is larger than the " + properties.getReadableMaxMb()
                    + " MB limit for one file in backup storage, so it was stopped and nothing was stored. "
                    + "Raise the bucket's file size limit and crm.backup.readable-max-mb together if needed.");
        } catch (SQLTimeoutException e) {
            throw timedOut();
        } catch (SQLException e) {
            if ("57014".equals(e.getSQLState())) {
                throw timedOut();
            }
            throw new BackupFailedException("Reading the CRM database for the readable export failed (SQLSTATE "
                    + (e.getSQLState() == null ? "unknown" : e.getSQLState()) + ").");
        }
    }

    private BackupFailedException timedOut() {
        return new BackupFailedException("The readable export took longer than "
                + properties.getReadableTimeout().toMinutes() + " min and was stopped.");
    }

    private Result exportInSnapshot(Connection c, Path zip, String root, long exportId, long deadline, long maxBytes)
            throws SQLException, IOException, InterruptedException {

        DatabaseMetaData meta = c.getMetaData();
        boolean postgres = meta.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
        String snapshot = postgres ? checkPostgresSnapshot(c) : "REPEATABLE READ, READ ONLY requested (not a PostgreSQL database; not checked)";

        String schema = c.getSchema();
        if (schema == null || !IDENTIFIER.matcher(schema).matches()) {
            throw new BackupFailedException("The CRM database schema could not be determined.");
        }
        if (postgres && !"public".equals(schema)) {
            throw new BackupFailedException("The readable export only covers the public schema, but the connection uses another schema.");
        }

        Map<String, Table> tables = discover(meta, schema);

        List<String> unreviewed = policy.unreviewed(columnNames(tables));
        if (!unreviewed.isEmpty()) {
            List<String> shown = unreviewed.subList(0, Math.min(MAX_LISTED_NAMES, unreviewed.size()));
            throw new BackupFailedException("The readable export was stopped before anything was written: "
                    + unreviewed.size() + " table(s)/column(s) have no reviewed export policy: " + String.join(", ", shown)
                    + (unreviewed.size() > shown.size() ? ", ..." : "")
                    + ". Add a decision for each to ReadableExportPolicies (see docs/backup.md).");
        }
        for (String required : properties.getReadableRequiredTables()) {
            if (!tables.containsKey(required) || policy.table(required).isEmpty()) {
                throw new BackupFailedException("The readable export is incomplete: table \"" + required + "\" is missing.");
            }
        }

        List<Table> exported = tables.values().stream().filter(t -> policy.table(t.name()).isPresent()).toList();
        String crmVersion = tables.containsKey("flyway_schema_history") ? crmSchemaVersion(c, tables.get("flyway_schema_history")) : null;
        String dbVersion = truncate(meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion(), 60);

        List<ReadableExporter.TableFile> files = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        Map<String, String> sha = new LinkedHashMap<>();
        List<String[]> relationships = new ArrayList<>();
        long totalRows = 0;
        Instant createdAt = clock.instant();

        try (OutputStream file = new LimitedOutputStream(new BufferedOutputStream(Files.newOutputStream(zip), 64 * 1024), maxBytes);
             ZipOutputStream out = new ZipOutputStream(file, StandardCharsets.UTF_8)) {

            for (Table t : exported) {
                checkTime(deadline);
                ReadableExportPolicy.TableRule rule = policy.table(t.name()).orElseThrow();
                List<Label> labels = labels(t, tables);
                for (ForeignKey fk : t.foreignKeys()) {
                    String label = labels.stream().filter(l -> l.fk().equals(fk)).map(Label::header).findFirst().orElse("");
                    relationships.add(new String[]{t.name(), fk.column(), fk.table(), fk.targetColumn(),
                            fk.enforced() ? "yes" : "no (logical link, no database constraint)", label});
                }

                String entry = root + "/tables/" + t.name() + ".csv";
                ReadableExporter.TableFile written = writeTable(c, out, schema, t, tables, rule, labels, entry, createdAt, deadline, sha);
                files.add(written);
                entries.add(entry);
                totalRows += written.rows();
            }

            String relEntry = root + "/relationships.csv";
            sha.put(relEntry, writeEntry(out, relEntry, createdAt, w -> {
                CsvWriter csv = new CsvWriter(w);
                csv.writeHeader(List.of("table", "column", "refers_to_table", "refers_to_column", "enforced_by_database", "name_column_in_export"));
                for (String[] r : relationships) {
                    csv.writeRecord(List.of(r), null);
                }
            }, true));
            entries.add(relEntry);

            Map<String, Object> manifest = manifest(exportId, createdAt, snapshot, dbVersion, crmVersion, files, tables, totalRows,
                    relationships.size(), sha);
            String manifestEntry = root + "/manifest.json";
            byte[] manifestJson = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(manifest);
            sha.put(manifestEntry, writeEntry(out, manifestEntry, createdAt, w -> w.write(new String(manifestJson, StandardCharsets.UTF_8)), false));
            entries.add(manifestEntry);

            String readmeEntry = root + "/README.txt";
            String readme = ReadableExportReadme.text(exportId, createdAt, snapshot, crmVersion, files, policy, tables.keySet(), totalRows);
            sha.put(readmeEntry, writeEntry(out, readmeEntry, createdAt, w -> w.write(readme), true));
            entries.add(readmeEntry);
        }

        return new Result(root, List.copyOf(files), List.copyOf(entries), Map.copyOf(sha), relationships.size(),
                totalRows, dbVersion, crmVersion);
    }

    private String checkPostgresSnapshot(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('transaction_isolation'), current_setting('transaction_read_only')")) {
            rs.next();
            String isolation = rs.getString(1);
            String readOnly = rs.getString(2);
            if (!"repeatable read".equals(isolation) || !"on".equals(readOnly)) {
                throw new BackupFailedException("The readable export could not open a consistent read-only snapshot of the database.");
            }
        }
        return "REPEATABLE READ, READ ONLY (checked on the database)";
    }

    private static Map<String, List<String>> columnNames(Map<String, Table> tables) {
        Map<String, List<String>> out = new TreeMap<>();
        tables.forEach((name, t) -> out.put(name, t.columns().stream().map(Column::name).toList()));
        return out;
    }

    Map<String, Table> discover(DatabaseMetaData meta, String schema) throws SQLException {

        Map<String, String> sqlNames = new TreeMap<>();
        try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE", "BASE TABLE", "PARTITIONED TABLE"})) {
            while (rs.next()) {
                String sqlName = rs.getString("TABLE_NAME");
                sqlNames.put(lowerIdentifier(sqlName), sqlName);
            }
        }

        Map<String, Table> tables = new TreeMap<>();
        for (Map.Entry<String, String> e : sqlNames.entrySet()) {
            String name = e.getKey();
            String sqlName = e.getValue();

            List<Column> columns = new ArrayList<>();
            Map<Integer, Column> byPosition = new TreeMap<>();
            try (ResultSet rs = meta.getColumns(null, schema, sqlName, "%")) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    byPosition.put(rs.getInt("ORDINAL_POSITION"), new Column(lowerIdentifier(col), col));
                }
            }
            columns.addAll(byPosition.values());

            Map<Integer, String> pk = new TreeMap<>();
            try (ResultSet rs = meta.getPrimaryKeys(null, schema, sqlName)) {
                while (rs.next()) {
                    pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                }
            }

            // single-column foreign keys only (the CRM has no composite ones)
            Map<String, List<String[]>> fkParts = new LinkedHashMap<>();
            try (ResultSet rs = meta.getImportedKeys(null, schema, sqlName)) {
                while (rs.next()) {
                    String key = rs.getString("FK_NAME") + "|" + rs.getString("PKTABLE_NAME");
                    fkParts.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[]{
                            lowerIdentifier(rs.getString("FKCOLUMN_NAME")),
                            lowerIdentifier(rs.getString("PKTABLE_NAME")),
                            lowerIdentifier(rs.getString("PKCOLUMN_NAME"))});
                }
            }
            List<ForeignKey> fks = new ArrayList<>();
            for (List<String[]> parts : fkParts.values()) {
                if (parts.size() == 1) {
                    String[] p = parts.get(0);
                    ForeignKey fk = new ForeignKey(p[0], p[1], p[2], true);
                    if (!fks.contains(fk)) {
                        fks.add(fk);
                    }
                }
            }
            // logical links the policy declares, where the database has no constraint
            policy.table(name).ifPresent(rule -> rule.logicalReferences().forEach((column, target) -> {
                boolean hasColumn = byPosition.values().stream().anyMatch(col -> col.name().equals(column));
                boolean constrained = fks.stream().anyMatch(fk -> fk.column().equals(column));
                if (hasColumn && !constrained && sqlNames.containsKey(target)) {
                    fks.add(new ForeignKey(column, target, "id", false));
                }
            }));
            fks.sort((a, b) -> a.column().compareTo(b.column()));

            tables.put(name, new Table(name, sqlName, List.copyOf(columns),
                    pk.values().stream().toList(), List.copyOf(fks)));
        }
        return tables;
    }

    private List<Label> labels(Table t, Map<String, Table> tables) {
        List<Label> labels = new ArrayList<>();
        ReadableExportPolicy.TableRule rule = policy.table(t.name()).orElseThrow();
        List<String> headers = new ArrayList<>(t.columns().stream().map(Column::name).toList());
        for (ForeignKey fk : t.foreignKeys()) {
            ReadableExportPolicy.ColumnRule own = rule.columns().get(fk.column());
            Table target = tables.get(fk.table());
            ReadableExportPolicy.TableRule targetRule = policy.table(fk.table()).orElse(null);
            if (own == null || own.treatment() != ReadableExportPolicy.Treatment.INCLUDE || target == null
                    || targetRule == null || targetRule.labelColumn() == null
                    || target.columns().stream().noneMatch(col -> col.name().equals(targetRule.labelColumn()))
                    || target.columns().stream().noneMatch(col -> col.name().equals(fk.targetColumn()))) {
                continue;
            }
            String base = fk.column().endsWith("_id") ? fk.column().substring(0, fk.column().length() - 3) : fk.column();
            String header = base + "__" + targetRule.labelColumn();
            if (headers.contains(header)) {
                continue;
            }
            headers.add(header);
            labels.add(new Label(header, fk, targetRule.labelColumn()));
        }
        return labels;
    }

    // ---- writing ---------------------------------------------------------

    private ReadableExporter.TableFile writeTable(Connection c, ZipOutputStream out, String schema, Table t,
                                                  Map<String, Table> tables, ReadableExportPolicy.TableRule rule,
                                                  List<Label> labels, String entry, Instant createdAt, long deadline,
                                                  Map<String, String> sha)
            throws SQLException, IOException, InterruptedException {

        StringBuilder select = new StringBuilder("SELECT ");
        List<String> header = new ArrayList<>();
        List<Boolean> withheld = new ArrayList<>();
        List<String> withheldNames = new ArrayList<>();
        for (Column col : t.columns()) {
            ReadableExportPolicy.ColumnRule cr = rule.columns().get(col.name());
            if (!header.isEmpty()) {
                select.append(", ");
            }
            if (cr.treatment() == ReadableExportPolicy.Treatment.WITHHOLD) {
                // only whether a value exists - the value itself is never read
                select.append("CASE WHEN t.").append(q(col.sqlName())).append(" IS NULL THEN 0 ELSE 1 END");
                withheld.add(true);
                withheldNames.add(col.name());
            } else {
                select.append("t.").append(q(col.sqlName()));
                withheld.add(false);
            }
            header.add(col.name());
        }
        StringBuilder from = new StringBuilder(" FROM ").append(q(schema)).append('.').append(q(t.sqlName())).append(" t");
        int j = 0;
        for (Label l : labels) {
            String alias = "j" + j++;
            Table target = tables.get(l.fk().table());
            select.append(", ").append(alias).append('.').append(q(sqlColumn(target, l.labelColumn())));
            from.append(" LEFT JOIN ").append(q(schema)).append('.').append(q(target.sqlName())).append(' ').append(alias)
                    .append(" ON ").append(alias).append('.').append(q(sqlColumn(target, l.fk().targetColumn())))
                    .append(" = t.").append(q(sqlColumn(t, l.fk().column())));
            header.add(l.header());
            withheld.add(false);
        }
        String order = t.primaryKey().isEmpty() ? "" : " ORDER BY " + String.join(", ",
                t.primaryKey().stream().map(k -> "t." + q(k)).toList());

        long expected;
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(remainingSeconds(deadline));
            try (ResultSet rs = st.executeQuery("SELECT count(*)" + " FROM " + q(schema) + "." + q(t.sqlName()))) {
                rs.next();
                expected = rs.getLong(1);
            }
        }

        long[] rows = {0};
        long[] guarded = {0};
        sha.put(entry, writeEntry(out, entry, createdAt, w -> {
            CsvWriter csv = new CsvWriter(w);
            csv.writeHeader(header);
            try (Statement st = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                st.setFetchSize(FETCH_SIZE);
                st.setQueryTimeout(remainingSeconds(deadline));
                try (ResultSet rs = st.executeQuery(select.toString() + from + order)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = header.size();
                    boolean[] isText = new boolean[n];
                    for (int i = 0; i < n; i++) {
                        isText[i] = !withheld.get(i) && isTextType(md.getColumnType(i + 1));
                    }
                    List<String> values = new ArrayList<>(n);
                    while (rs.next()) {
                        values.clear();
                        for (int i = 0; i < n; i++) {
                            values.add(withheld.get(i)
                                    ? (rs.getInt(i + 1) == 1 ? ReadableExportPolicy.WITHHELD : null)
                                    : value(rs, i + 1, md.getColumnType(i + 1)));
                        }
                        csv.writeRecord(values, isText);
                        if (++rows[0] % CHECK_EVERY_ROWS == 0) {
                            checkTime(deadline);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new SqlInWriter(e);
            } catch (InterruptedException e) {
                throw new InterruptedInWriter();
            }
            guarded[0] = csv.guardedCells();
        }, true));

        if (rows[0] != expected) {
            throw new BackupFailedException("The readable export is inconsistent: table \"" + t.name() + "\" has "
                    + expected + " rows but " + rows[0] + " were written.");
        }
        return new ReadableExporter.TableFile(t.name(), entry, List.copyOf(header), rows[0], guarded[0], List.copyOf(withheldNames));
    }

    private static String sqlColumn(Table t, String name) {
        return t.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow().sqlName();
    }

    private static boolean isTextType(int type) {
        return switch (type) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB, Types.OTHER, Types.ARRAY, Types.SQLXML, Types.STRUCT, Types.JAVA_OBJECT -> true;
            default -> false;
        };
    }

    static String value(ResultSet rs, int i, int type) throws SQLException {
        switch (type) {
            case Types.TIMESTAMP: {
                LocalDateTime v = rs.getObject(i, LocalDateTime.class);
                return v == null ? null : dateTime(v);
            }
            case Types.TIMESTAMP_WITH_TIMEZONE: {
                OffsetDateTime v = rs.getObject(i, OffsetDateTime.class);
                return v == null ? null : dateTime(v.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()) + "Z";
            }
            case Types.DATE: {
                LocalDate v = rs.getObject(i, LocalDate.class);
                return v == null ? null : v.toString();
            }
            case Types.BOOLEAN:
            case Types.BIT: {
                boolean v = rs.getBoolean(i);
                return rs.wasNull() ? null : Boolean.toString(v);
            }
            case Types.DECIMAL:
            case Types.NUMERIC: {
                BigDecimal v = rs.getBigDecimal(i);
                return v == null ? null : v.toPlainString();
            }
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE: {
                String v = rs.getString(i);
                if (v == null || !(v.contains("E") || v.contains("e"))) {
                    return v;
                }
                try {
                    return new BigDecimal(v).toPlainString();
                } catch (NumberFormatException e) {
                    return v;
                }
            }
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB: {
                byte[] v = rs.getBytes(i);
                return v == null ? null : "[binary data, " + v.length + " bytes]";
            }
            default:
                return rs.getString(i);
        }
    }

    private static String dateTime(LocalDateTime v) {
        String s = DATE_TIME.format(v);
        int micros = v.getNano() / 1000;
        return micros == 0 ? s : s + "." + String.format("%06d", micros);
    }

    private String crmSchemaVersion(Connection c, Table history) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + q(sqlColumn(history, "version")) + " FROM " + q(c.getSchema()) + "."
                     + q(history.sqlName()) + " WHERE " + q(sqlColumn(history, "success")) + " = TRUE ORDER BY "
                     + q(sqlColumn(history, "installed_rank")) + " DESC")) {
            while (rs.next()) {
                String v = rs.getString(1);
                if (v != null) {
                    return truncate(v, 30);
                }
            }
            return null;
        }
    }

    private Map<String, Object> manifest(long exportId, Instant createdAt, String snapshot, String dbVersion, String crmVersion,
                                         List<ReadableExporter.TableFile> files, Map<String, Table> tables, long totalRows,
                                         int relationships, Map<String, String> sha) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "CRM readable data export");
        m.put("formatVersion", 1);
        m.put("restorable", false);
        m.put("notice", "This is a readable copy of the CRM data for viewing (for example in Excel). It is NOT a database "
                + "backup and cannot be used to restore the CRM. Use a full backup (.dump) for that.");
        m.put("exportId", exportId);
        m.put("createdAtUtc", DateTimeFormatter.ISO_INSTANT.format(createdAt));
        m.put("timesInFiles", "Date/time columns are written exactly as stored by the CRM (no time zone); the server's time zone is "
                + ZoneId.systemDefault().getId() + ".");
        m.put("snapshot", snapshot);
        m.put("database", dbVersion);
        m.put("crmSchemaVersion", crmVersion);
        m.put("csv", Map.of(
                "encoding", "UTF-8 with byte order mark",
                "delimiter", ",",
                "lineEnding", "CRLF",
                "null", "empty field",
                "emptyText", "\"\"",
                "withheld", ReadableExportPolicy.WITHHELD + " = a value exists but is deliberately not exported",
                "formulaGuard", "text that a spreadsheet would run as a formula starts with an added apostrophe (')"));
        List<Map<String, Object>> t = new ArrayList<>();
        for (ReadableExporter.TableFile f : files) {
            ReadableExportPolicy.TableRule rule = policy.table(f.table()).orElseThrow();
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", f.table());
            e.put("description", rule.description());
            e.put("file", f.entry().substring(f.entry().indexOf('/') + 1));
            e.put("rows", f.rows());
            e.put("columns", f.header());
            List<Map<String, String>> w = new ArrayList<>();
            for (String col : f.withheldColumns()) {
                w.add(Map.of("column", col, "reason", rule.columns().get(col).reason()));
            }
            e.put("withheldColumns", w);
            e.put("formulaGuardedCells", f.guardedCells());
            e.put("sha256", sha.get(f.entry()));
            t.add(e);
        }
        m.put("tables", t);
        List<Map<String, String>> ex = new ArrayList<>();
        for (ReadableExportPolicy.ExcludedTable x : policy.excludedTables()) {
            if (tables.containsKey(x.name())) {
                ex.add(Map.of("name", x.name(), "reason", x.reason()));
            }
        }
        m.put("excludedTables", ex);
        m.put("relationships", Map.of("file", "relationships.csv", "count", relationships));
        m.put("totalRows", totalRows);
        return m;
    }

    // ---- zip entries ------------------------------------------------------

    @FunctionalInterface
    private interface EntryBody {
        void write(Writer w) throws IOException;
    }

    private static final class SqlInWriter extends RuntimeException {
        final SQLException cause;

        SqlInWriter(SQLException cause) {
            super(null, null, false, false);
            this.cause = cause;
        }
    }

    private static final class InterruptedInWriter extends RuntimeException {
        InterruptedInWriter() {
            super(null, null, false, false);
        }
    }

    // Writes one entry and returns the SHA-256 of its bytes.
    private static String writeEntry(ZipOutputStream out, String name, Instant createdAt, EntryBody body, boolean bom)
            throws IOException, SQLException, InterruptedException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(createdAt.toEpochMilli());
        out.putNextEntry(e);
        MessageDigest digest = sha256();
        Writer w = new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(new NonClosing(out), digest), StandardCharsets.UTF_8), 64 * 1024);
        try {
            if (bom) {
                w.write('﻿');
            }
            body.write(w);
        } catch (SqlInWriter s) {
            throw s.cause;
        } catch (InterruptedInWriter i) {
            throw new InterruptedException();
        }
        w.flush();
        out.closeEntry();
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void checkTime(long deadline) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (System.nanoTime() > deadline) {
            throw timedOut();
        }
    }

    private int remainingSeconds(long deadline) {
        long s = (deadline - System.nanoTime()) / 1_000_000_000L;
        if (s <= 0) {
            throw timedOut();
        }
        return (int) Math.min(Integer.MAX_VALUE, s);
    }

    private static String q(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new BackupFailedException("The readable export does not support the database object name \"" + identifier + "\".");
        }
        return "\"" + identifier + "\"";
    }

    private static String lowerIdentifier(String sqlName) {
        if (sqlName == null || !IDENTIFIER.matcher(sqlName).matches()) {
            throw new BackupFailedException("The readable export does not support the database object name \"" + sqlName + "\".");
        }
        return sqlName.toLowerCase(Locale.ROOT);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    // Lets an entry writer be closed/flushed without closing the ZIP.
    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    // Fails as soon as more than maxBytes have been written to the file.
    static final class LimitedOutputStream extends FilterOutputStream {
        private final long maxBytes;
        private long written;

        LimitedOutputStream(OutputStream out, long maxBytes) {
            super(out);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            count(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count(len);
            out.write(b, off, len);
        }

        private void count(long n) throws TooLargeException {
            written += n;
            if (written > maxBytes) {
                throw new TooLargeException();
            }
        }
    }
}
