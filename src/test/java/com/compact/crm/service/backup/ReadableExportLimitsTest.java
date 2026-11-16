package com.compact.crm.service.backup;

import com.compact.crm.entity.DbBackup;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.enums.BackupKind;
import com.compact.crm.enums.BackupStatus;
import com.compact.crm.repository.DbBackupRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The readable export's hard limits, on the H2 test database: the per-file
 * size limit of the backup bucket (crm.backup.readable-max-mb, 0 here so any
 * export is too large), the fail-closed export policy, the time limit, and the
 * free-disk check. In every case the job ends FAILED with a clear reason,
 * nothing is uploaded and no temporary file is left behind.
 */
@SpringBootTest(properties = {
        "crm.backup.temp-dir=target/test-readable-limits",
        "crm.backup.readable-max-mb=0"
})
class ReadableExportLimitsTest {

    @Autowired private DataSource dataSource;
    @Autowired private BackupService backupService;
    @Autowired private DbBackupRepository backupRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private com.compact.crm.repository.ActivityLogRepository activityLogRepository;

    @MockitoBean private BackupStorage storage;
    @MockitoBean private DatabaseDumper dumper;
    @MockitoBean private CurrentUserService currentUserService;

    @TempDir Path dir;

    private Employee admin;

    @BeforeEach
    void setUp() {
        backupRepository.deleteAll();
        Role role = roleRepository.findByNameIgnoreCase("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ADMIN").rank(100).build()));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        admin = employeeRepository.save(Employee.builder().name("Limits " + tag).email("limits-" + tag + "@company.test")
                .phone("96" + String.format("%08d", Math.abs(tag.hashCode()) % 100_000_000))
                .password("x").role(role).isActive(true).build());
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(storage.isConfigured()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        long deadline = System.currentTimeMillis() + 15_000;
        while (backupService.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        backupRepository.deleteAll();
        activityLogRepository.deleteAll(activityLogRepository.findAll().stream()
                .filter(a -> a.getEmployee() != null && a.getEmployee().getId().equals(admin.getId())).toList());
        employeeRepository.deleteById(admin.getId());
    }

    private DbBackup awaitFinished(long id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            DbBackup b = backupRepository.findById(id).orElseThrow();
            if (b.getStatus() != BackupStatus.RUNNING && !backupService.isRunning()) {
                return b;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job did not finish");
    }

    @Test
    void anExportLargerThanTheBucketLimit_isStoppedWhileWriting_andNothingIsStored() throws Exception {

        long id = backupService.startReadableExport().getId();
        DbBackup b = awaitFinished(id);

        assertThat(b.getKind()).isEqualTo(BackupKind.READABLE_EXPORT);
        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).contains("larger than the 0 MB limit").contains("nothing was stored");
        assertThat(b.getObjectKey()).isNull();
        assertThat(b.getSizeBytes()).isNull();
        verify(storage, never()).upload(anyString(), any());
        try (Stream<Path> files = Files.list(Path.of("target/test-readable-limits"))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith("readable-"))).isEmpty();
        }
        assertThat(backupService.get(id).isAvailable()).isFalse();
    }

    @Test
    void notEnoughFreeDisk_failsBeforeAnythingIsRead() throws Exception {

        BackupProperties props = new BackupProperties();
        props.setTempDir(dir);
        props.setMinFreeMb(Long.MAX_VALUE / (1024 * 1024));
        // the service's own properties are shared; only check the message here via a fresh row
        long id = backupRepository.save(DbBackup.builder().kind(BackupKind.READABLE_EXPORT).status(BackupStatus.RUNNING)
                .initiatedByName("x").startedAt(LocalDateTime.now()).build()).getId();
        BackupService service = new BackupService(backupRepository, employeeRepository, dumper, storage, props,
                org.mockito.Mockito.mock(com.compact.crm.service.ActivityLogService.class), currentUserService,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                org.mockito.Mockito.mock(ReadableExporter.class), new ReadableExportVerifier());

        service.runReadableJob(id, admin.getId());

        DbBackup b = backupRepository.findById(id).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(b.getErrorMessage()).startsWith("Not enough free disk space");
        verify(storage, never()).upload(anyString(), any());
    }

    // ---- the exporter on its own -------------------------------------------

    private JdbcReadableExporter exporter(ReadableExportPolicy policy, BackupProperties props) {
        return new JdbcReadableExporter(dataSource, props, policy, Clock.systemUTC());
    }

    @Test
    void aTableOrColumnWithoutAReviewedPolicy_stopsTheExport_beforeAnythingIsWritten() throws Exception {

        // the CRM policy without the employees table, and with one leads column missing
        ReadableExportPolicy.Builder b = new ReadableExportPolicy.Builder();
        for (ReadableExportPolicy.TableRule t : ReadableExportPolicies.crm().tables()) {
            if (t.name().equals("employees")) {
                continue;
            }
            ReadableExportPolicy.TableBuilder tb = b.table(t.name(), t.description());
            for (ReadableExportPolicy.ColumnRule c : t.columns().values()) {
                if (t.name().equals("leads") && c.name().equals("phone")) {
                    continue;
                }
                if (c.treatment() == ReadableExportPolicy.Treatment.WITHHOLD) {
                    tb.withhold(c.name(), c.reason());
                } else {
                    tb.include(c.name());
                }
            }
            tb.done();
        }
        for (ReadableExportPolicy.ExcludedTable x : ReadableExportPolicies.crm().excludedTables()) {
            b.exclude(x.name(), x.reason());
        }

        BackupProperties props = new BackupProperties();
        Path zip = dir.resolve("out.zip");

        assertThatThrownBy(() -> exporter(b.build(), props).export(zip, "crm-readable-export-x-1", 1))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("before anything was written")
                .hasMessageContaining("employees")
                .hasMessageContaining("leads.phone");
        assertThat(zip).doesNotExist();
    }

    @Test
    void anExportPastItsTimeLimit_isStopped() {

        BackupProperties props = new BackupProperties();
        props.setReadableTimeout(Duration.ofNanos(1));

        assertThatThrownBy(() -> exporter(ReadableExportPolicies.crm(), props).export(dir.resolve("slow.zip"), "crm-readable-export-x-2", 2))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("took longer than");
    }

    @Test
    void anExportWithinTheLimits_passesTheIndependentCheck() throws Exception {

        BackupProperties props = new BackupProperties();
        Path zip = dir.resolve("ok.zip");

        ReadableExporter.Result result = exporter(ReadableExportPolicies.crm(), props).export(zip, "crm-readable-export-x-3", 3);
        new ReadableExportVerifier().verify(zip, result);

        // a truncated copy is rejected
        byte[] bytes = Files.readAllBytes(zip);
        Path cut = Files.write(dir.resolve("cut.zip"), java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertThatThrownBy(() -> new ReadableExportVerifier().verify(cut, result))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("failed its check");
    }
}
