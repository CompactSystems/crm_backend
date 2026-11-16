package com.compact.crm.service.backup;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration V20 (db_backup.kind / total_rows) on a real PostgreSQL that already
 * has backup history from V14_2: every existing row becomes kind = 'DUMP' with
 * all its other values unchanged, the kind is constrained, the one-running
 * index still covers both kinds, and re-running V20 changes nothing.
 *
 * DESTRUCTIVE - drops the public schema of CRM_TEST_PG_URL; guarded exactly
 * like BackupPostgresRoundTripTest (see DisposableDatabaseGuard).
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
class DbBackupKindMigrationPostgresTest {

    @Test
    void existingBackupsBecomeDumps_unchanged_andTheMigrationIsRepeatable() throws Exception {

        PostgresTarget target = DisposableDatabaseGuard.checkConfiguration(System.getenv());
        DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("CRM_TEST_PG_URL"),
                System.getenv("CRM_TEST_PG_USER"), System.getenv().getOrDefault("CRM_TEST_PG_PASSWORD", ""));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            DisposableDatabaseGuard.checkConnection(c, target, DisposableDatabaseGuard.PublicSchema.MUST_BE_EMPTY);
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").baselineVersion("1")
                .baselineOnMigrate(true).target(MigrationVersion.fromVersion("19")).load().migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("INSERT INTO db_backup (status, initiated_by_name, started_at, finished_at, size_bytes, sha256, "
                + "object_key, table_count) VALUES ('SUCCEEDED', 'Asha', TIMESTAMP '2026-11-10 10:00:00', "
                + "TIMESTAMP '2026-11-10 10:01:00', 1234, repeat('a', 64), 'crm-backup-20261110T100000Z-1.dump', 30)");
        jdbc.execute("INSERT INTO db_backup (status, initiated_by_name, started_at, error_message) "
                + "VALUES ('FAILED', 'Asha', TIMESTAMP '2026-11-11 10:00:00', 'boom')");
        String before = jdbc.queryForObject("SELECT string_agg(id || '|' || status || '|' || coalesce(object_key, '-') || '|' "
                + "|| coalesce(size_bytes::text, '-') || '|' || started_at, ';' ORDER BY id) FROM db_backup", String.class);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").baselineVersion("1")
                .baselineOnMigrate(true).load().migrate();

        assertThat(jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history WHERE version = '20' AND success",
                String.class)).isEqualTo("20");
        assertThat(jdbc.queryForList("SELECT kind FROM db_backup ORDER BY id", String.class)).containsExactly("DUMP", "DUMP");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM db_backup WHERE total_rows IS NOT NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT string_agg(id || '|' || status || '|' || coalesce(object_key, '-') || '|' "
                + "|| coalesce(size_bytes::text, '-') || '|' || started_at, ';' ORDER BY id) FROM db_backup", String.class))
                .isEqualTo(before);

        // only the two kinds are allowed
        assertThatThrownBy(() -> jdbc.execute("INSERT INTO db_backup (kind, status, initiated_by_name, started_at) "
                + "VALUES ('SOMETHING', 'FAILED', 'x', now())")).hasMessageContaining("chk_db_backup_kind");

        // one RUNNING job of ANY kind
        jdbc.execute("INSERT INTO db_backup (kind, status, initiated_by_name, started_at) VALUES ('DUMP', 'RUNNING', 'x', now())");
        assertThatThrownBy(() -> jdbc.execute("INSERT INTO db_backup (kind, status, initiated_by_name, started_at) "
                + "VALUES ('READABLE_EXPORT', 'RUNNING', 'x', now())")).hasMessageContaining("uq_db_backup_one_running");

        // re-running the script is a no-op
        String v20 = new String(new ClassPathResource("db/migration/V20__db_backup_kind.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(v20); // the whole file at once (its DO block contains semicolons)
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'chk_db_backup_kind'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT kind FROM db_backup WHERE status <> 'RUNNING' ORDER BY id", String.class))
                .containsExactly("DUMP", "DUMP");
    }
}
