package com.compact.crm.service.backup;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression for the deployed export failing on 2026-11-16: the production
 * database still has columns that older entity versions created (Hibernate
 * ddl-auto=update never drops columns) and that the H2 entity schema does not
 * have. This test adds exactly those seven legacy columns to the H2 test
 * database, runs the REAL exporter and verifier, and checks that
 *  - the export succeeds and the legacy values are in the CSVs as stored,
 *  - the old leads.product_id gets a readable product__name column and a
 *    relationships.csv entry,
 *  - free text in the legacy columns is still formula-guarded,
 *  - the password stays withheld,
 *  - and ANY further unreviewed column still stops the export before a file
 *    is written.
 * The columns are removed again afterwards, so other tests are unaffected.
 */
@SpringBootTest
class ReadableExportLegacyColumnsTest {

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuJ5x3H8lZgK3iMb6S0uK7yS4QwE9vWd2";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;

    @TempDir Path dir;

    private Employee owner;
    private final List<String> cleanup = new ArrayList<>();

    @BeforeEach
    void addTheLegacyColumns() {
        Role role = roleRepository.findByNameIgnoreCase("EMPLOYEE")
                .orElseGet(() -> roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build()));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        owner = employeeRepository.save(Employee.builder().name("Legacy " + tag).email("legacy-" + tag + "@company.test")
                .phone("95" + String.format("%08d", Math.abs(tag.hashCode()) % 100_000_000))
                .password(HASH).role(role).isActive(true).build());

        // the legacy columns, with the types the older entities produced
        jdbc.execute("ALTER TABLE leads ADD COLUMN website VARCHAR(255)");
        jdbc.execute("ALTER TABLE leads ADD COLUMN interested_product VARCHAR(255)");
        jdbc.execute("ALTER TABLE leads ADD COLUMN lead_source VARCHAR(255)");
        jdbc.execute("ALTER TABLE leads ADD COLUMN product_id BIGINT");
        jdbc.execute("ALTER TABLE opportunities ADD COLUMN sales_stage VARCHAR(255)");
        jdbc.execute("ALTER TABLE followups ADD COLUMN activity_type VARCHAR(255)");
        jdbc.execute("ALTER TABLE followups ADD COLUMN custom_activity_type VARCHAR(255)");
    }

    @AfterEach
    void removeTheLegacyColumns() {
        for (String sql : cleanup) {
            jdbc.execute(sql);
        }
        for (String c : List.of("leads.website", "leads.interested_product", "leads.lead_source", "leads.product_id",
                "opportunities.sales_stage", "followups.activity_type", "followups.custom_activity_type",
                "leads.legacy_secret_note")) {
            String[] p = c.split("\\.");
            jdbc.execute("ALTER TABLE " + p[0] + " DROP COLUMN IF EXISTS " + p[1]);
        }
        employeeRepository.deleteById(owner.getId());
    }

    private JdbcReadableExporter exporter() {
        return new JdbcReadableExporter(dataSource, new BackupProperties(), ReadableExportPolicies.crm(), Clock.systemUTC());
    }

    private static Map<String, String> unzip(Path zip) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(zip)), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static Map<String, String> row(String csvText, String column, String value) throws Exception {
        assertThat(csvText).startsWith("﻿");
        CsvReader r = new CsvReader(new StringReader(csvText.substring(1)));
        List<String> header = r.next();
        List<String> rec;
        while ((rec = r.next()) != null) {
            if (value.equals(rec.get(header.indexOf(column)))) {
                Map<String, String> m = new HashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    m.put(header.get(i), rec.get(i));
                }
                return m;
            }
        }
        throw new AssertionError("no row with " + column + " = " + value);
    }

    @Test
    void theExportSucceedsWithTheLegacyColumns_andKeepsTheirValuesAndRelationships() throws Exception {

        jdbc.update("INSERT INTO products (name, is_active) VALUES (?, TRUE)", "Legacy UPS 5kVA");
        long productId = jdbc.queryForObject("SELECT max(id) FROM products", Long.class);
        jdbc.update("INSERT INTO leads (company_name, contact_person, phone, email, assigned_employee_id, website, "
                        + "interested_product, lead_source, product_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "Old Lead Co", "Old Contact", "+91 90000 11111", "old@lead.test", owner.getId(),
                "https://old-lead.example", "=Genset, UPS", "INDIA_MART", productId);
        long leadId = jdbc.queryForObject("SELECT max(id) FROM leads", Long.class);
        jdbc.update("INSERT INTO opportunities (title, lead_id, sales_stage) VALUES (?, ?, ?)",
                "Old Deal", leadId, "QUOTATION_SENT");
        long oppId = jdbc.queryForObject("SELECT max(id) FROM opportunities", Long.class);
        jdbc.update("INSERT INTO followups (lead_id, employee_id, activity_type, custom_activity_type, remarks) "
                + "VALUES (?, ?, ?, ?, ?)", leadId, owner.getId(), "OTHER", "Site survey", "old follow-up");
        long followUpId = jdbc.queryForObject("SELECT max(id) FROM followups", Long.class);
        cleanup.add("DELETE FROM followups WHERE id = " + followUpId);
        cleanup.add("DELETE FROM opportunities WHERE id = " + oppId);
        cleanup.add("DELETE FROM leads WHERE id = " + leadId);
        cleanup.add("DELETE FROM products WHERE id = " + productId);

        Path zip = dir.resolve("legacy.zip");
        ReadableExporter.Result result = exporter().export(zip, "crm-readable-export-legacy-1", 1);
        new ReadableExportVerifier().verify(zip, result);

        Map<String, String> files = unzip(zip);
        String root = "crm-readable-export-legacy-1/";

        Map<String, String> lead = row(files.get(root + "tables/leads.csv"), "id", String.valueOf(leadId));
        assertThat(lead).containsEntry("website", "https://old-lead.example")
                .containsEntry("interested_product", "'=Genset, UPS")   // free text: formula-guarded
                .containsEntry("lead_source", "INDIA_MART")
                .containsEntry("product_id", String.valueOf(productId))
                .containsEntry("product__name", "Legacy UPS 5kVA")
                .containsEntry("assigned_employee__name", owner.getName());

        assertThat(row(files.get(root + "tables/opportunities.csv"), "id", String.valueOf(oppId)))
                .containsEntry("sales_stage", "QUOTATION_SENT")
                .containsEntry("lead__company_name", "Old Lead Co");

        assertThat(row(files.get(root + "tables/followups.csv"), "id", String.valueOf(followUpId)))
                .containsEntry("activity_type", "OTHER")
                .containsEntry("custom_activity_type", "Site survey")
                .containsEntry("lead__company_name", "Old Lead Co");

        // parsed, not matched as text: the "enforced" field contains commas, so it is (correctly) quoted
        String relationships = files.get(root + "relationships.csv");
        assertThat(relationships).startsWith("﻿");
        CsvReader rel = new CsvReader(new StringReader(relationships.substring(1)));
        List<List<String>> links = new ArrayList<>();
        for (List<String> r = rel.next(); r != null; r = rel.next()) {
            links.add(r);
        }
        assertThat(links).contains(List.of("leads", "product_id", "products", "id",
                "no (logical link, no database constraint)", "product__name"));
        // and the raw file holds the properly escaped field
        assertThat(relationships).contains(
                "leads,product_id,products,id,\"no (logical link, no database constraint)\",product__name\r\n");

        // the existing sensitive-data rules are unchanged
        assertThat(row(files.get(root + "tables/employees.csv"), "id", String.valueOf(owner.getId())))
                .containsEntry("password", "[withheld]");
        assertThat(String.join("\n", files.values())).doesNotContain(HASH);
        assertThat(files.keySet()).noneMatch(n -> n.endsWith("email_oauth_state.csv") || n.endsWith("db_backup.csv"));
    }

    @Test
    void anyFurtherUnreviewedColumn_stillStopsTheExport_beforeAFileIsWritten() {

        jdbc.execute("ALTER TABLE leads ADD COLUMN legacy_secret_note VARCHAR(255)");

        Path zip = dir.resolve("unknown.zip");
        assertThatThrownBy(() -> exporter().export(zip, "crm-readable-export-legacy-2", 2))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("before anything was written")
                .hasMessageContaining("1 table(s)/column(s)")
                .hasMessageContaining("leads.legacy_secret_note");
        assertThat(zip).doesNotExist();
    }
}
