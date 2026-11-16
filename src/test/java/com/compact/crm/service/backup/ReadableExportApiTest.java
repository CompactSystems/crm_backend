package com.compact.crm.service.backup;

import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.DbBackup;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.BackupKind;
import com.compact.crm.enums.BackupStatus;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.DbBackupRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The readable data export over HTTP: real security chain, real BackupService
 * and background thread, the REAL JdbcReadableExporter and verifier reading
 * the H2 test database, and the real db_backup table. Only the bucket
 * (BackupStorage) and pg_dump (DatabaseDumper) are mocks; every uploaded ZIP is
 * captured and opened here. The PostgreSQL side (snapshot settings, migrated
 * schema, foreign keys) is covered by ReadableExportPostgresTest.
 */
@SpringBootTest(properties = {
        "crm.backup.temp-dir=target/test-readable",
        "crm.backup.retain=2",
        "crm.backup.readable-retain=2"
})
@AutoConfigureMockMvc
class ReadableExportApiTest {

    // a syntactically real bcrypt hash: it must never appear in an export
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuJ5x3H8lZgK3iMb6S0uK7yS4QwE9vWd2";
    private static final String NONCE = "secret-oauth-nonce-" + UUID.randomUUID();
    private static final String TOKEN = "k1.SECRET-ENCRYPTED-REFRESH-TOKEN-" + UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DbBackupRepository backupRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private BackupService backupService;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private DatabaseDumper dumper;
    @MockitoBean private BackupStorage storage;
    @MockitoSpyBean private ReadableExporter exporter;

    private Employee admin;
    private Employee manager;
    private Employee employee;
    private final List<Long> leadIds = new ArrayList<>();
    private final Map<String, byte[]> uploaded = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {

        backupRepository.deleteAll();
        uploaded.clear();

        admin = employee("ADMIN", 100);
        manager = employee("MANAGER", 50);
        employee = employee("EMPLOYEE", 10);

        when(storage.isConfigured()).thenReturn(true);
        doAnswer(inv -> {
            uploaded.put(inv.getArgument(0, String.class), Files.readAllBytes(inv.getArgument(1, Path.class)));
            return null;
        }).when(storage).upload(anyString(), any());
        doAnswer(inv -> {
            Files.write(inv.getArgument(0, Path.class), "PGDMP".getBytes());
            return null;
        }).when(dumper).dump(any());
        when(dumper.verify(any())).thenReturn(new PgDumpRunner.ArchiveInfo(1, java.util.Set.of(), "17", "17"));

        // business data with the awkward cases: a formula, a phone with +, a comma, a line break, Unicode
        jdbc.update("INSERT INTO leads (company_name, contact_person, phone, email, description, assigned_employee_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                "=HYPERLINK(\"http://evil.test\",\"click\")", "Rāmesh, Sharma", "+91 98765 43210", "r@lead.test",
                "line one\nline two", employee.getId(), LocalDateTime.of(2026, 11, 1, 9, 30, 15));
        leadIds.add(jdbc.queryForObject("SELECT max(id) FROM leads", Long.class));

        jdbc.update("INSERT INTO email_mailbox_account (employee_id, provider, email_address, provider_subject, "
                        + "refresh_token_encrypted, status, connected_at, updated_at) VALUES (?, 'GOOGLE', ?, 'sub-123', ?, 'CONNECTED', ?, ?)",
                admin.getId(), "admin-mailbox@company.test", TOKEN, LocalDateTime.now(), LocalDateTime.now());
        jdbc.update("INSERT INTO email_oauth_state (state_hash, employee_id, provider, code_verifier_encrypted, nonce, created_at, "
                        + "expires_at) VALUES (?, ?, 'GOOGLE', 'verifier-secret', ?, ?, ?)",
                "hash-" + UUID.randomUUID(), admin.getId(), NONCE, LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
    }

    @AfterEach
    void tearDown() {
        awaitIdle();
        backupRepository.deleteAll();
        jdbc.update("DELETE FROM email_oauth_state");
        jdbc.update("DELETE FROM email_mailbox_account");
        for (Long id : leadIds) {
            jdbc.update("DELETE FROM leads WHERE id = ?", id);
        }
        leadIds.clear();
        List<Long> ids = Stream.of(admin, manager, employee).map(Employee::getId).toList();
        activityLogRepository.deleteAll(activityLogRepository.findAll().stream()
                .filter(a -> a.getEmployee() != null && ids.contains(a.getEmployee().getId())).toList());
        employeeRepository.deleteAllById(ids);
    }

    private Employee employee(String roleName, int rank) {
        Role role = roleRepository.findByNameIgnoreCase(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).rank(rank).build()));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return employeeRepository.save(Employee.builder().name(roleName + " " + tag)
                .email("readable-" + tag + "@company.test")
                .phone("97" + String.format("%08d", Math.abs(tag.hashCode()) % 100_000_000))
                .password(HASH).role(role).isActive(true).build());
    }

    private String bearer(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
    }

    private long startReadable() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/admin/backups/readable").header("Authorization", bearer(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.kind").value("READABLE_EXPORT"))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private long startDump() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/admin/backups").header("Authorization", bearer(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("DUMP"))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private DbBackup awaitFinished(long id) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            DbBackup b = backupRepository.findById(id).orElseThrow();
            if (b.getStatus() != BackupStatus.RUNNING && !backupService.isRunning()) {
                return b;
            }
            sleep();
        }
        throw new AssertionError("job " + id + " did not finish");
    }

    private void awaitIdle() {
        long deadline = System.currentTimeMillis() + 30_000;
        while (backupService.isRunning() && System.currentTimeMillis() < deadline) {
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<ActivityAction> activity(long id) {
        return activityLogRepository.findAll().stream()
                .filter(a -> a.getModule() == ActivityModule.SYSTEM && Long.valueOf(id).equals(a.getEntityId()))
                .map(ActivityLog::getAction).toList();
    }

    private void assertNoTempFilesLeft() throws IOException {
        Path dir = Path.of("target/test-readable");
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                assertThat(files.map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith("readable-") || n.startsWith("backup-"))).isEmpty();
            }
        }
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static List<List<String>> csv(String text) throws IOException {
        assertThat(text).startsWith("﻿");
        CsvReader r = new CsvReader(new java.io.StringReader(text.substring(1)));
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

    // ---- authorization ---------------------------------------------------

    @Test
    void startingAReadableExport_requiresAnAdmin() throws Exception {

        mockMvc.perform(post("/api/admin/backups/readable")).andExpect(status().isUnauthorized());
        for (Employee e : List.of(employee, manager)) {
            mockMvc.perform(post("/api/admin/backups/readable").header("Authorization", bearer(e)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/backups?kind=READABLE_EXPORT").header("Authorization", bearer(e)))
                    .andExpect(status().isForbidden());
        }

        verify(exporter, never()).export(any(), anyString(), anyLong());
        assertThat(backupRepository.count()).isZero();
    }

    @Test
    void downloadingAReadableExport_requiresAnAdmin() throws Exception {

        DbBackup done = backupRepository.save(DbBackup.builder().kind(BackupKind.READABLE_EXPORT).status(BackupStatus.SUCCEEDED)
                .initiatedByName("x").startedAt(LocalDateTime.now()).finishedAt(LocalDateTime.now())
                .objectKey("crm-readable-export-20261116T100000Z-1.zip").sizeBytes(3L).build());

        mockMvc.perform(get("/api/admin/backups/" + done.getId() + "/download")).andExpect(status().isUnauthorized());
        for (Employee e : List.of(employee, manager)) {
            mockMvc.perform(get("/api/admin/backups/" + done.getId() + "/download").header("Authorization", bearer(e)))
                    .andExpect(status().isForbidden());
        }
        verify(storage, never()).download(anyString(), any());
    }

    // ---- the export itself -------------------------------------------------

    @Test
    void admin_createsAReadableExport_withRealData_checkedUploadedAndAudited() throws Exception {

        long id = startReadable();
        DbBackup b = awaitFinished(id);

        assertThat(b.getStatus()).as(b.getErrorMessage()).isEqualTo(BackupStatus.SUCCEEDED);
        assertThat(b.getKind()).isEqualTo(BackupKind.READABLE_EXPORT);
        assertThat(b.getObjectKey()).matches("crm-readable-export-\\d{8}T\\d{6}Z-" + id + "\\.zip");
        assertThat(b.getSha256()).hasSize(64);
        assertThat(b.getTotalRows()).isPositive();
        assertThat(b.getTableCount()).isGreaterThan(20);
        assertThat(activity(id)).containsExactly(ActivityAction.READABLE_EXPORT_STARTED, ActivityAction.READABLE_EXPORT_SUCCEEDED);
        assertNoTempFilesLeft();

        byte[] zip = uploaded.get(b.getObjectKey());
        assertThat(zip).isNotNull().hasSize(b.getSizeBytes().intValue());
        assertThat(BackupService.sha256(Files.write(Files.createTempFile("zip", ".zip"), zip))).isEqualTo(b.getSha256());

        String root = b.getObjectKey().replace(".zip", "") + "/";
        Map<String, String> files = unzip(zip);
        assertThat(files).containsKeys(root + "README.txt", root + "manifest.json", root + "relationships.csv",
                root + "tables/employees.csv", root + "tables/leads.csv", root + "tables/opportunities.csv",
                root + "tables/customers.csv", root + "tables/followups.csv", root + "tables/email_mailbox_account.csv");
        // reviewed exclusions
        assertThat(files).doesNotContainKeys(root + "tables/email_oauth_state.csv", root + "tables/db_backup.csv");
        assertThat(files.keySet()).allMatch(n -> n.startsWith(root));

        // clearly labelled as not a backup
        assertThat(files.get(root + "README.txt")).contains("THIS IS NOT A DATABASE BACKUP")
                .contains("email_oauth_state: ").contains("employees.password: password hash");
        JsonNode manifest = objectMapper.readTree(files.get(root + "manifest.json"));
        assertThat(manifest.get("restorable").asBoolean()).isFalse();
        assertThat(manifest.get("totalRows").asLong()).isEqualTo(b.getTotalRows());

        // no secret anywhere in the archive
        String everything = String.join("\n", files.values());
        assertThat(everything).doesNotContain(HASH).doesNotContain("$2a$10$")
                .doesNotContain(TOKEN).doesNotContain(NONCE).doesNotContain("verifier-secret").doesNotContain("sub-123");

        // employees: real rows, password column present but withheld
        List<List<String>> employees = csv(files.get(root + "tables/employees.csv"));
        assertThat(employees.get(0)).contains("id", "name", "email", "phone", "password", "role_id", "role__name");
        Map<String, String> adminRow = row(employees, "email", admin.getEmail());
        assertThat(adminRow).containsEntry("password", "[withheld]").containsEntry("name", admin.getName())
                .containsEntry("role__name", "ADMIN");

        // leads: exact contact details, formula neutralised, name column for the assigned employee
        List<List<String>> leads = csv(files.get(root + "tables/leads.csv"));
        Map<String, String> lead = row(leads, "id", String.valueOf(leadIds.get(0)));
        assertThat(lead).containsEntry("company_name", "'=HYPERLINK(\"http://evil.test\",\"click\")")
                .containsEntry("contact_person", "Rāmesh, Sharma")
                .containsEntry("phone", "+91 98765 43210")
                .containsEntry("description", "line one\nline two")
                .containsEntry("assigned_employee_id", String.valueOf(employee.getId()))
                .containsEntry("assigned_employee__name", employee.getName())
                .containsEntry("created_at", "2026-11-01 09:30:15")
                .containsEntry("closed_at", null);

        // mailbox: address exported, token and sign-in identifier withheld
        Map<String, String> mailbox = row(csv(files.get(root + "tables/email_mailbox_account.csv")),
                "email_address", "admin-mailbox@company.test");
        assertThat(mailbox).containsEntry("refresh_token_encrypted", "[withheld]")
                .containsEntry("provider_subject", "[withheld]")
                .containsEntry("employee__name", admin.getName());

        // relationships map lists the links
        List<List<String>> rel = csv(files.get(root + "relationships.csv"));
        assertThat(rel.get(0)).containsExactly("table", "column", "refers_to_table", "refers_to_column",
                "enforced_by_database", "name_column_in_export");
        assertThat(rel).anyMatch(r -> r.get(0).equals("leads") && r.get(1).equals("assigned_employee_id")
                && r.get(2).equals("employees") && r.get(5).equals("assigned_employee__name"));

        mockMvc.perform(get("/api/admin/backups/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("READABLE_EXPORT"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.totalRows").value(b.getTotalRows()));
    }

    @Test
    void download_streamsTheZipPrivately_andIsAudited() throws Exception {

        long id = startReadable();
        DbBackup b = awaitFinished(id);
        byte[] zip = uploaded.get(b.getObjectKey());
        doAnswer(inv -> {
            inv.getArgument(1, OutputStream.class).write(zip);
            return null;
        }).when(storage).download(eq(b.getObjectKey()), any());

        mockMvc.perform(get("/api/admin/backups/" + id + "/download").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(zip))
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", containsString(b.getObjectKey())))
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        assertThat(activity(id)).contains(ActivityAction.READABLE_EXPORT_DOWNLOADED);
    }

    @Test
    void historyIsListedPerKind() throws Exception {

        long dump = startDump();
        awaitFinished(dump);
        long readable = startReadable();
        awaitFinished(readable);

        mockMvc.perform(get("/api/admin/backups?kind=READABLE_EXPORT").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(readable));
        mockMvc.perform(get("/api/admin/backups?kind=DUMP").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(dump))
                .andExpect(jsonPath("$[0].fileName").value(containsString(".dump")));
        mockMvc.perform(get("/api/admin/backups").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ---- one job at a time -------------------------------------------------

    @Test
    void aReadableExportCannotStartWhileADumpRuns_andViceVersa() throws Exception {

        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            Files.write(inv.getArgument(0, Path.class), "PGDMP".getBytes());
            return null;
        }).when(dumper).dump(any());

        long dump = startDump();
        mockMvc.perform(post("/api/admin/backups/readable").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BACKUP_IN_PROGRESS"));
        release.countDown();
        assertThat(awaitFinished(dump).getStatus()).isEqualTo(BackupStatus.SUCCEEDED);

        CountDownLatch release2 = new CountDownLatch(1);
        doAnswer(inv -> {
            release2.await(10, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(exporter).export(any(), anyString(), anyLong());

        long readable = startReadable();
        mockMvc.perform(post("/api/admin/backups").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        release2.countDown();
        assertThat(awaitFinished(readable).getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
    }

    // ---- failures --------------------------------------------------------

    @Test
    void aFailedUpload_isFailed_removesThePartialObject_andLeavesNoTempFile() throws Exception {

        doThrow(new IOException("Backup storage rejected the upload (HTTP 413)")).when(storage).upload(anyString(), any());

        DbBackup b = awaitFinished(startReadable());

        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).contains("HTTP 413");
        assertThat(b.getObjectKey()).isNull();
        verify(storage).delete(argThat(k -> k.endsWith(".zip")));
        assertThat(activity(b.getId())).containsExactly(ActivityAction.READABLE_EXPORT_STARTED, ActivityAction.READABLE_EXPORT_FAILED);
        assertNoTempFilesLeft();

        mockMvc.perform(get("/api/admin/backups/" + b.getId() + "/download").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void anExportThatFailsItsCheck_isNeverUploaded() throws Exception {

        AtomicReference<ReadableExporter.Result> real = new AtomicReference<>();
        doAnswer(inv -> {
            ReadableExporter.Result r = (ReadableExporter.Result) inv.callRealMethod();
            real.set(r);
            // claim one more row than was written: the verifier must notice
            ReadableExporter.TableFile first = r.tables().get(0);
            List<ReadableExporter.TableFile> tables = new ArrayList<>(r.tables());
            tables.set(0, new ReadableExporter.TableFile(first.table(), first.entry(), first.header(), first.rows() + 1,
                    first.guardedCells(), first.withheldColumns()));
            return new ReadableExporter.Result(r.rootFolder(), tables, r.entries(), r.sha256ByEntry(),
                    r.relationshipCount(), r.totalRows() + 1, r.databaseVersion(), r.crmSchemaVersion());
        }).when(exporter).export(any(), anyString(), anyLong());

        DbBackup b = awaitFinished(startReadable());

        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).startsWith("The readable export failed its check");
        verify(storage, never()).upload(anyString(), any());
        assertNoTempFilesLeft();
    }

    @Test
    void anUnexpectedExporterError_neverExposesItsRawMessage() throws Exception {

        doThrow(new IllegalStateException("jdbc:postgresql://secret-host:5432/db password=hunter2"))
                .when(exporter).export(any(), anyString(), anyLong());

        DbBackup b = awaitFinished(startReadable());

        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).isEqualTo("The readable export failed unexpectedly (IllegalStateException).");
        verify(storage, never()).upload(anyString(), any());
    }

    @Test
    void withoutBackupStorage_aReadableExportReports503() throws Exception {

        when(storage.isConfigured()).thenReturn(false);

        mockMvc.perform(post("/api/admin/backups/readable").header("Authorization", bearer(admin)))
                .andExpect(status().isServiceUnavailable());
        assertThat(backupRepository.count()).isZero();
    }

    // ---- retention: separate per kind ---------------------------------------

    private DbBackup stored(BackupKind kind, int daysAgo, String key) {
        LocalDateTime t = LocalDateTime.now().minusDays(daysAgo);
        return backupRepository.save(DbBackup.builder().kind(kind).status(BackupStatus.SUCCEEDED).initiatedByName("x")
                .startedAt(t).finishedAt(t).objectKey(key).sizeBytes(1L).build());
    }

    @Test
    void readableExports_neverCountTowardOrDeleteDumps() throws Exception {

        // two stored dumps (= crm.backup.retain), old enough that age would matter if it applied to them
        DbBackup dump1 = stored(BackupKind.DUMP, 60, "crm-backup-20260901T000000Z-1.dump");
        DbBackup dump2 = stored(BackupKind.DUMP, 50, "crm-backup-20260911T000000Z-2.dump");
        reset(storage);
        when(storage.isConfigured()).thenReturn(true);

        // many readable exports, each followed by readable retention
        for (int i = 0; i < 4; i++) {
            assertThat(awaitFinished(startReadable()).getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
        }

        assertThat(backupRepository.findById(dump1.getId()).orElseThrow().getRemovedAt()).isNull();
        assertThat(backupRepository.findById(dump2.getId()).orElseThrow().getRemovedAt()).isNull();
        verify(storage, never()).delete(argThat(k -> k.endsWith(".dump")));

        // and only the newest 2 readable exports are kept (crm.backup.readable-retain=2)
        List<DbBackup> readable = backupRepository.findByKindAndStatusAndRemovedAtIsNullOrderByStartedAtDescIdDesc(
                BackupKind.READABLE_EXPORT, BackupStatus.SUCCEEDED);
        assertThat(readable).hasSize(2);
    }

    @Test
    void dumpRetention_neverCountsOrDeletesReadableExports() throws Exception {

        DbBackup r1 = stored(BackupKind.READABLE_EXPORT, 2, "crm-readable-export-20261114T000000Z-1.zip");
        DbBackup r2 = stored(BackupKind.READABLE_EXPORT, 1, "crm-readable-export-20261115T000000Z-2.zip");
        reset(storage);
        when(storage.isConfigured()).thenReturn(true);

        for (int i = 0; i < 3; i++) {
            assertThat(awaitFinished(startDump()).getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
        }

        assertThat(backupRepository.findById(r1.getId()).orElseThrow().getRemovedAt()).isNull();
        assertThat(backupRepository.findById(r2.getId()).orElseThrow().getRemovedAt()).isNull();
        verify(storage, never()).delete(argThat(k -> k.endsWith(".zip")));
        // the dump policy itself still works: 3 dumps, keep 2
        assertThat(backupRepository.findByKindAndStatusAndRemovedAtIsNullOrderByStartedAtDescIdDesc(
                BackupKind.DUMP, BackupStatus.SUCCEEDED)).hasSize(2);
    }

    @Test
    void readableExportsOlderThan30Days_areDeleted_andNeverDownloadable() throws Exception {

        DbBackup old = stored(BackupKind.READABLE_EXPORT, 31, "crm-readable-export-20261016T000000Z-7.zip");
        DbBackup fresh = stored(BackupKind.READABLE_EXPORT, 29, "crm-readable-export-20261018T000000Z-8.zip");
        DbBackup oldDump = stored(BackupKind.DUMP, 90, "crm-backup-20260818T000000Z-9.dump");

        // before cleanup: expired, so not offered and refused
        mockMvc.perform(get("/api/admin/backups/" + old.getId()).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.available").value(false));
        mockMvc.perform(get("/api/admin/backups/" + old.getId() + "/download").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("expired")));
        verify(storage, never()).download(anyString(), any());

        backupService.applyReadableRetention();

        assertThat(backupRepository.findById(old.getId()).orElseThrow().getRemovedAt()).isNotNull();
        verify(storage).delete(old.getObjectKey());
        assertThat(backupRepository.findById(fresh.getId()).orElseThrow().getRemovedAt()).isNull();
        assertThat(backupRepository.findById(oldDump.getId()).orElseThrow().getRemovedAt()).isNull();
        verify(storage, never()).delete(oldDump.getObjectKey());

        mockMvc.perform(get("/api/admin/backups/" + old.getId() + "/download").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("retention")));
    }
}
