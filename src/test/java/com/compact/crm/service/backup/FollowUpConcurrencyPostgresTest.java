package com.compact.crm.service.backup;

import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.security.UserPrincipal;
import com.compact.crm.service.FollowUpService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency of follow-up creation and of the terminal-stage cancellation on a
 * REAL PostgreSQL (the H2 suite cannot prove row-lock behaviour). Real threads,
 * real committed transactions.
 *
 *  - identical follow-ups submitted at the same moment end up as ONE row;
 *  - distinct follow-ups submitted at the same moment are all kept;
 *  - a create racing the cancellation that closes its opportunity, and a
 *    completion (which creates the next follow-up) racing it, neither deadlock
 *    nor lose a row, and a cancelled count of 1 always means the row is CANCELLED.
 *
 * DESTRUCTIVE - drops the public schema of CRM_TEST_PG_URL; guarded exactly like
 * BackupPostgresRoundTripTest (DisposableDatabaseGuard, docs/backup.md): a fresh
 * local *_restore_test database, a non-superuser owner, CRM_TEST_PG_CONFIRM_DROP.
 * It lives in this package only because the guard is package-private.
 * This test was written without a PostgreSQL available to run it; run it
 * before relying on the locking.
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
})
class FollowUpConcurrencyPostgresTest {

    private static final int THREADS = 8;

    @BeforeAll
    static void freshBaseline() throws Exception {

        // Refuses (before connecting or changing anything) unless the target is a
        // dedicated local *_restore_test database owned by a non-superuser role -
        // see docs/backup.md. The database must be newly created: it is dropped.
        PostgresTarget target = DisposableDatabaseGuard.checkConfiguration(System.getenv());

        DriverManagerDataSource ds = new DriverManagerDataSource(
                System.getenv("CRM_TEST_PG_URL"),
                System.getenv("CRM_TEST_PG_USER"),
                System.getenv().getOrDefault("CRM_TEST_PG_PASSWORD", ""));

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            DisposableDatabaseGuard.checkConnection(c, target, DisposableDatabaseGuard.PublicSchema.MUST_BE_EMPTY);
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FollowUpService followUpService;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private EmployeeRepository employeeRepository;

    private Employee admin;
    private long seq = 9000;

    @BeforeEach
    void data() {

        jdbc.execute("INSERT INTO employees (id, name, email, phone, password, role_id) "
                + "SELECT 9801, 'PG Admin', 'pgadmin@pg.test', '9123400801', 'x', id FROM roles WHERE name = 'ADMIN' "
                + "ON CONFLICT DO NOTHING");
        jdbc.execute("INSERT INTO activity_types (id, name) VALUES (9890, 'PG Concurrent Call') ON CONFLICT DO NOTHING");

        admin = employeeRepository.findById(9801L).orElseThrow();
    }

    private void authenticate() {
        UserPrincipal principal = new UserPrincipal(admin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    // A fresh lead + IN PROGRESS opportunity.
    private long opportunity() {

        long n = ++seq;
        Long stage = jdbc.queryForObject("SELECT id FROM sales_stages WHERE stage_type = 'IN_PROGRESS'", Long.class);

        jdbc.update("INSERT INTO leads (id, company_name, contact_person, phone, assigned_employee_id) "
                + "VALUES (?, ?, 'P', ?, 9801)", n, "PG Race Co " + n, "98" + (10000000L + n));
        jdbc.update("INSERT INTO opportunities (id, title, product_value, sales_stage_id, lead_id) "
                + "VALUES (?, ?, 1, ?, ?)", n, "PG Race " + n, stage, n);

        return n;
    }

    private FollowUpRequest request(long opportunityId, LocalDateTime when, String remarks) {

        FollowUpRequest r = new FollowUpRequest();
        r.setOpportunityId(opportunityId);
        r.setEmployeeId(admin.getId());
        r.setActivityTypeId(9890L);
        r.setStatus(FollowUpStatus.PENDING);
        r.setScheduledDate(when);
        r.setRemarks(remarks);
        return r;
    }

    // Runs the tasks on separate threads, released together; returns their results
    // (an exception - including a deadlock - fails the test).
    private <T> List<T> together(List<Callable<T>> tasks) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();

        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                authenticate();
                ready.countDown();
                go.await();
                return task.call();
            }));
        }

        ready.await();
        go.countDown();

        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }

        pool.shutdown();
        return results;
    }

    @Test
    void identicalFollowUpsSubmittedAtTheSameMoment_endUpAsOneRow() throws Exception {

        long opp = opportunity();
        LocalDateTime when = LocalDateTime.now().plusDays(3).withNano(0);

        List<Callable<FollowUp>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> followUpService.createFollowUp(request(opp, when, "Send the brochure")));
        }

        List<FollowUp> results = together(tasks);

        assertThat(followUpRepository.findByOpportunityId(opp)).hasSize(1);
        assertThat(results).extracting(FollowUp::getId).containsOnly(results.get(0).getId());
    }

    @Test
    void distinctFollowUpsSubmittedAtTheSameMoment_areAllKept() throws Exception {

        long opp = opportunity();
        LocalDateTime when = LocalDateTime.now().plusDays(3).withNano(0);

        List<Callable<FollowUp>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String remarks = "Task number " + i;
            tasks.add(() -> followUpService.createFollowUp(request(opp, when, remarks)));
        }

        together(tasks);

        assertThat(followUpRepository.findByOpportunityId(opp)).hasSize(THREADS);
    }

    @Test
    void aCreateRacingTheClosingCancellation_neitherDeadlocksNorLosesTheRow() throws Exception {

        for (int round = 0; round < 25; round++) {

            long oppId = opportunity();
            Opportunity opp = opportunityRepository.findById(oppId).orElseThrow();
            LocalDateTime when = LocalDateTime.now().plusDays(3).withNano(0);

            List<Callable<Object>> tasks = List.of(
                    () -> followUpService.createFollowUp(request(oppId, when, "Race " + oppId)),
                    () -> followUpService.cancelOutstandingForClosedOpportunity(opp, "WON"));

            List<Object> results = together(tasks);

            List<FollowUp> rows = followUpRepository.findByOpportunityId(oppId);
            assertThat(rows).hasSize(1);

            int cancelled = (Integer) results.get(1);

            // Cancelling saw the row => it is cancelled. (When the create came after the
            // cancellation, it is an ordinary new follow-up on a closed opportunity.)
            if (cancelled == 1) {
                assertThat(rows.get(0).getStatus()).isEqualTo(FollowUpStatus.CANCELLED);
            }
        }
    }

    @Test
    void completingAFollowUpRacingTheClosingCancellation_doesNotDeadlock() throws Exception {

        for (int round = 0; round < 25; round++) {

            long oppId = opportunity();
            Opportunity opp = opportunityRepository.findById(oppId).orElseThrow();
            LocalDateTime when = LocalDateTime.now().plusDays(3).withNano(0);

            authenticate();
            FollowUp current = followUpService.createFollowUp(request(oppId, when, "Initial call " + oppId));

            FollowUpRequest completion = request(oppId, when, "Initial call " + oppId);
            completion.setStatus(FollowUpStatus.COMPLETED);
            completion.setCompletedDate(LocalDateTime.now());
            completion.setNextActionDescription("Send the quotation");
            completion.setNextFollowUpDate(LocalDateTime.now().plusDays(6).withNano(0));
            completion.setNextActionActivityTypeId(9890L);

            // either order is fine; what matters is that both finish (a deadlock would
            // abort one of them with an exception, failing the test)
            together(List.<Callable<Object>>of(
                    () -> followUpService.updateFollowUp(current.getId(), completion),
                    () -> followUpService.cancelOutstandingForClosedOpportunity(opp, "WON")));

            assertThat(followUpRepository.findByOpportunityId(oppId)).isNotEmpty();
        }
    }
}
