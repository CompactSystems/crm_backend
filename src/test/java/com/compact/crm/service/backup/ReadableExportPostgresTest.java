package com.compact.crm.service.backup;

import com.compact.crm.entity.DbBackup;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.BackupKind;
import com.compact.crm.enums.BackupStatus;
import com.compact.crm.repository.DbBackupRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.security.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The readable export against a REAL PostgreSQL with the production schema:
 * the legacy baseline, Flyway V2..V20 and then Hibernate ddl-auto=update, i.e.
 * exactly how the deployed database is built. Proves on PostgreSQL that
 *  - every table and column of that schema has a reviewed export policy (the
 *    export refuses to run otherwise),
 *  - the export runs in a REPEATABLE READ, READ ONLY snapshot (checked on the
 *    server), finds the real foreign keys, and passes the independent check,
 *  - every CSV holds exactly the table's rows with the real values, and no
 *    password hash, token, OAuth secret or withheld consent text is in the ZIP,
 *  - the whole BackupService job (V20 kind column, upload, SUCCEEDED) works.
 *
 * DESTRUCTIVE - it DROPS the public schema of CRM_TEST_PG_URL. Skipped unless
 * CRM_TEST_PG_URL is set; then DisposableDatabaseGuard must pass first (local
 * host, *_restore_test database, CRM_TEST_PG_CONFIRM_DROP, non-superuser
 * owner, completely empty public schema) or the test FAILS before anything is
 * changed. See docs/backup.md for how to run it on a throwaway cluster.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${CRM_TEST_PG_URL}",
        "spring.datasource.username=${CRM_TEST_PG_USER}",
        "spring.datasource.password=${CRM_TEST_PG_PASSWORD:}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=1",
        "spring.jpa.properties.hibernate.enable_lazy_load_no_trans=true",
        "crm.backup.temp-dir=target/test-readable-pg",
})
class ReadableExportPostgresTest {

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuJ5x3H8lZgK3iMb6S0uK7yS4QwE9vWd2";
    private static final String TOKEN = "k1.PG-SECRET-REFRESH-TOKEN";
    private static final String NONCE = "pg-secret-nonce-value";
    private static final String EVIDENCE = "Customer said yes on a private call, see note PG-EVIDENCE-TEXT";

    @BeforeAll
    static void freshDisposableDatabase() throws Exception {
        // SAFETY: every check must pass before anything is changed (fails, never skips)
        PostgresTarget target = DisposableDatabaseGuard.checkConfiguration(System.getenv());
        DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("CRM_TEST_PG_URL"),
                System.getenv("CRM_TEST_PG_USER"), System.getenv().getOrDefault("CRM_TEST_PG_PASSWORD", ""));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            DisposableDatabaseGuard.checkConnection(c, target, DisposableDatabaseGuard.PublicSchema.MUST_BE_EMPTY);
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
            // Legacy columns the production database still has: older entity
            // versions created them and ddl-auto=update never drops columns
            // (see ReadableExportPolicies.LEGACY_COLUMNS). Without them this
            // schema was not production-shaped, which is how the deployed
            // export met 7 columns that no test had seen.
            st.execute("ALTER TABLE leads ADD COLUMN website VARCHAR(255)");
            st.execute("ALTER TABLE leads ADD COLUMN interested_product VARCHAR(255)");
            st.execute("ALTER TABLE leads ADD COLUMN lead_source VARCHAR(255)");
            st.execute("ALTER TABLE leads ADD COLUMN product_id BIGINT REFERENCES products(id)");
            st.execute("ALTER TABLE opportunities ADD COLUMN sales_stage VARCHAR(255)");
            st.execute("ALTER TABLE followups ADD COLUMN activity_type VARCHAR(255)");
            st.execute("ALTER TABLE followups ADD COLUMN custom_activity_type VARCHAR(255)");
        }
        // the application context (Flyway V2..V20, then Hibernate update) starts after this
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReadableExporter exporter;
    @Autowired private ReadableExportVerifier verifier;
    @Autowired private BackupService backupService;
    @Autowired private DbBackupRepository backupRepository;
    @Autowired private EmployeeRepository employeeRepository;

    @MockitoBean private BackupStorage storage;
    @MockitoBean private DatabaseDumper dumper;
    @MockitoBean private CurrentUserService currentUserService;

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static List<List<String>> csv(String text) throws Exception {
        assertThat(text).startsWith("﻿");
        CsvReader r = new CsvReader(new StringReader(text.substring(1)));
        List<List<String>> rows = new ArrayList<>();
        List<String> rec;
        while ((rec = r.next()) != null) {
            rows.add(rec);
        }
        return rows;
    }

    private static Map<String, String> row(List<List<String>> csv, String column, String value) {
        List<String> header = csv.get(0);
        int i = header.indexOf(column);
        for (List<String> r : csv.subList(1, csv.size())) {
            if (value.equals(r.get(i))) {
                Map<String, String> m = new HashMap<>();
                for (int k = 0; k < header.size(); k++) {
                    m.put(header.get(k), r.get(k));
                }
                return m;
            }
        }
        throw new AssertionError("no row with " + column + " = " + value);
    }

    private void seed() {
        jdbc.execute("INSERT INTO employees (id, name, email, phone, password, role_id, is_active) "
                + "SELECT 9800, 'Priyā Admin', 'pg-admin@pg.test', '9123498000', '" + HASH + "', id, true FROM roles WHERE name = 'ADMIN'");
        jdbc.execute("INSERT INTO leads (id, company_name, contact_person, phone, email, description, assigned_employee_id, "
                + "created_at, contact_deadline) VALUES (9810, '=1+2 Industries, \"Pune\"', 'Contact ' || chr(10) || 'Two', "
                + "'+91 98765 43210', 'lead@pg.test', 'Ünïcode 漢字', 9800, TIMESTAMP '2026-11-01 09:30:15.25', DATE '2026-12-01')");
        jdbc.execute("INSERT INTO opportunities (id, title, product_value, expected_closing_date, lead_id, created_at) "
                + "VALUES (9820, 'Big Deal', 1234567.5, DATE '2027-01-15', 9810, now())");
        jdbc.execute("INSERT INTO customers (id, company_name, contact_person, phone, email, gst_number, opportunity_id, "
                + "assigned_employee_id, created_at) VALUES (9830, 'Big Deal Customer', 'Cust Person', '02012345678', "
                + "'cust@pg.test', '27AAAAA0000A1Z5', 9820, 9800, now())");
        jdbc.execute("INSERT INTO followups (id, lead_id, employee_id, status, scheduled_date, remarks) "
                + "VALUES (9840, 9810, 9800, 'PENDING', TIMESTAMP '2026-11-20 10:00:00', 'call back')");
        jdbc.execute("INSERT INTO products (id, name, is_active) VALUES (9850, 'UPS 10kVA', true)");
        jdbc.execute("INSERT INTO lead_products (id, lead_id, product_id, quantity) VALUES (9860, 9810, 9850, 3)");
        // values in the legacy columns, as older versions of the CRM wrote them
        jdbc.execute("UPDATE leads SET website = 'https://legacy.example', interested_product = 'Genset, UPS', "
                + "lead_source = 'INDIA_MART', product_id = 9850 WHERE id = 9810");
        jdbc.execute("UPDATE opportunities SET sales_stage = 'QUOTATION_SENT' WHERE id = 9820");
        jdbc.execute("UPDATE followups SET activity_type = 'OTHER', custom_activity_type = 'Site survey' WHERE id = 9840");
        jdbc.execute("INSERT INTO whatsapp_consent (phone_e164, status, source, evidence, consented_at, lead_id, created_at, "
                + "updated_at) VALUES ('919876543210', 'OPTED_IN', 'MANUAL', '" + EVIDENCE + "', now(), 9810, now(), now())");
        jdbc.execute("INSERT INTO email_mailbox_account (employee_id, provider, email_address, provider_subject, "
                + "refresh_token_encrypted, status, connected_at, updated_at) VALUES (9800, 'GOOGLE', 'mbx@pg.test', "
                + "'pg-subject-id', '" + TOKEN + "', 'CONNECTED', now(), now())");
        jdbc.execute("INSERT INTO email_oauth_state (state_hash, employee_id, provider, code_verifier_encrypted, nonce, "
                + "created_at, expires_at) VALUES ('pg-state-hash', 9800, 'GOOGLE', 'pg-verifier', '" + NONCE + "', now(), now())");
    }

    @Test
    void theReadableExport_ofTheProductionShapedSchema_isCompleteConsistentAndSafe() throws Exception {

        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("20");
        seed();

        // ---- the exporter on its own ----------------------------------------
        Path dir = Files.createDirectories(Path.of("target/test-readable-pg"));
        Path zip = dir.resolve("pg-export.zip");
        Files.deleteIfExists(zip);
        ReadableExporter.Result result = exporter.export(zip, "crm-readable-export-pg-1", 1);
        verifier.verify(zip, result);
        assertThat(result.crmSchemaVersion()).isEqualTo("20");
        assertThat(result.databaseVersion()).startsWith("PostgreSQL");

        Map<String, String> files = unzip(Files.readAllBytes(zip));
        String root = "crm-readable-export-pg-1/";
        JsonNode manifest = new ObjectMapper().readTree(files.get(root + "manifest.json"));
        assertThat(manifest.get("snapshot").asText()).isEqualTo("REPEATABLE READ, READ ONLY (checked on the database)");

        // every table of the real schema is either exported (with exactly its rows) or listed as excluded
        List<String> pgTables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        Set<String> exported = new HashSet<>();
        manifest.get("tables").forEach(t -> exported.add(t.get("name").asText()));
        Set<String> excluded = new HashSet<>();
        manifest.get("excludedTables").forEach(t -> excluded.add(t.get("name").asText()));
        assertThat(excluded).containsExactlyInAnyOrder("flyway_schema_history", "db_backup", "email_oauth_state");
        for (String t : pgTables) {
            assertThat(exported.contains(t) || excluded.contains(t)).as("table %s is accounted for", t).isTrue();
        }
        for (JsonNode t : manifest.get("tables")) {
            long count = jdbc.queryForObject("SELECT count(*) FROM public.\"" + t.get("name").asText() + "\"", Long.class);
            assertThat(t.get("rows").asLong()).as(t.get("name").asText()).isEqualTo(count);
            assertThat(csv(files.get(root + t.get("file").asText()))).hasSize((int) count + 1);
        }

        // no secret anywhere
        String everything = String.join("\n", files.values());
        assertThat(everything).doesNotContain(HASH).doesNotContain(TOKEN).doesNotContain(NONCE)
                .doesNotContain("pg-verifier").doesNotContain("pg-state-hash").doesNotContain("pg-subject-id")
                .doesNotContain("PG-EVIDENCE-TEXT");

        // real values, as stored
        Map<String, String> lead = row(csv(files.get(root + "tables/leads.csv")), "id", "9810");
        assertThat(lead).containsEntry("company_name", "'=1+2 Industries, \"Pune\"")
                .containsEntry("contact_person", "Contact \nTwo")
                .containsEntry("phone", "+91 98765 43210")
                .containsEntry("description", "Ünïcode 漢字")
                .containsEntry("created_at", "2026-11-01 09:30:15.250000")
                .containsEntry("contact_deadline", "2026-12-01")
                .containsEntry("assigned_employee__name", "Priyā Admin")
                // legacy columns: exported as stored, the old product id with its readable name
                .containsEntry("website", "https://legacy.example")
                .containsEntry("interested_product", "Genset, UPS")
                .containsEntry("lead_source", "INDIA_MART")
                .containsEntry("product_id", "9850")
                .containsEntry("product__name", "UPS 10kVA");
        Map<String, String> opp = row(csv(files.get(root + "tables/opportunities.csv")), "id", "9820");
        assertThat(opp).containsEntry("product_value", "1234567.5").containsEntry("lead__company_name", "'=1+2 Industries, \"Pune\"")
                .containsEntry("sales_stage", "QUOTATION_SENT");
        assertThat(row(csv(files.get(root + "tables/followups.csv")), "id", "9840"))
                .containsEntry("activity_type", "OTHER").containsEntry("custom_activity_type", "Site survey");
        assertThat(row(csv(files.get(root + "tables/customers.csv")), "id", "9830"))
                .containsEntry("gst_number", "27AAAAA0000A1Z5").containsEntry("opportunity__title", "Big Deal");
        assertThat(row(csv(files.get(root + "tables/lead_products.csv")), "id", "9860"))
                .containsEntry("product__name", "UPS 10kVA").containsEntry("quantity", "3");
        assertThat(row(csv(files.get(root + "tables/employees.csv")), "id", "9800"))
                .containsEntry("password", "[withheld]").containsEntry("role__name", "ADMIN");
        Map<String, String> consent = row(csv(files.get(root + "tables/whatsapp_consent.csv")), "phone_e164", "919876543210");
        assertThat(consent).containsEntry("evidence", "[withheld]").containsEntry("status", "OPTED_IN")
                .containsEntry("lead__company_name", "'=1+2 Industries, \"Pune\"");

        // foreign keys from the PostgreSQL catalog (enforced) and the policy's logical links (not enforced)
        List<List<String>> rel = csv(files.get(root + "relationships.csv"));
        assertThat(rel).anyMatch(r -> r.subList(0, 5).equals(List.of("leads", "assigned_employee_id", "employees", "id", "yes")));
        // the legacy product link: its database foreign key wins over the policy's logical link
        assertThat(rel).anyMatch(r -> r.equals(List.of("leads", "product_id", "products", "id", "yes", "product__name")));
        assertThat(rel).anyMatch(r -> r.get(0).equals("whatsapp_consent") && r.get(1).equals("lead_id")
                && r.get(4).startsWith("no"));

        // ---- the whole job through BackupService (V20 kind, upload, SUCCEEDED) --
        Employee admin = employeeRepository.findById(9800L).orElseThrow();
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(storage.isConfigured()).thenReturn(true);
        Map<String, byte[]> uploaded = new HashMap<>();
        doAnswer(inv -> {
            uploaded.put(inv.getArgument(0, String.class), Files.readAllBytes(inv.getArgument(1, Path.class)));
            return null;
        }).when(storage).upload(anyString(), any());

        long id = backupService.startReadableExport().getId();
        long deadline = System.currentTimeMillis() + 60_000;
        DbBackup b;
        do {
            Thread.sleep(100);
            b = backupRepository.findById(id).orElseThrow();
        } while ((b.getStatus() == BackupStatus.RUNNING || backupService.isRunning()) && System.currentTimeMillis() < deadline);

        assertThat(b.getStatus()).as(b.getErrorMessage()).isEqualTo(BackupStatus.SUCCEEDED);
        assertThat(b.getKind()).isEqualTo(BackupKind.READABLE_EXPORT);
        assertThat(jdbc.queryForObject("SELECT kind FROM db_backup WHERE id = " + id, String.class)).isEqualTo("READABLE_EXPORT");
        assertThat(uploaded.get(b.getObjectKey())).hasSize(b.getSizeBytes().intValue());
        // the job's own start was logged to activity_log in between, so at least as many rows
        assertThat(b.getTotalRows()).isGreaterThanOrEqualTo(result.totalRows());

        // ---- a column nobody has reviewed still stops the export ---------------
        jdbc.execute("ALTER TABLE leads ADD COLUMN unreviewed_note TEXT");
        Path blocked = dir.resolve("pg-blocked.zip");
        Files.deleteIfExists(blocked);
        assertThatThrownBy(() -> exporter.export(blocked, "crm-readable-export-pg-2", 2))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("before anything was written")
                .hasMessageContaining("1 table(s)/column(s)")
                .hasMessageContaining("leads.unreviewed_note");
        assertThat(blocked).doesNotExist();
    }
}
