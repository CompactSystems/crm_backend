package com.compact.crm.service.backup;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every table and column Hibernate builds from the CRM entities (the H2 test
 * schema) must have a reviewed decision in ReadableExportPolicies - otherwise
 * the readable export refuses to run in production. A new entity field fails
 * this test until someone decides whether it is exported or withheld. The
 * migration-only parts of the schema (Flyway's history, legacy columns) are
 * covered by ReadableExportPostgresTest against a real migrated PostgreSQL.
 */
@SpringBootTest
class ReadableExportPolicyCoverageTest {

    @Autowired private DataSource dataSource;

    @Test
    void everyEntityTableAndColumn_hasAReviewedExportPolicy() throws Exception {

        ReadableExportPolicy policy = ReadableExportPolicies.crm();
        JdbcReadableExporter exporter = new JdbcReadableExporter(dataSource, new BackupProperties(), policy, Clock.systemUTC());

        Map<String, List<String>> schema = new TreeMap<>();
        try (Connection c = dataSource.getConnection()) {
            exporter.discover(c.getMetaData(), c.getSchema())
                    .forEach((name, t) -> schema.put(name, t.columns().stream().map(JdbcReadableExporter.Column::name).toList()));
        }

        assertThat(schema).containsKeys("employees", "leads", "opportunities", "customers", "followups");
        assertThat(policy.unreviewed(schema))
                .as("tables/columns without a reviewed export policy; full schema: %s", schema)
                .isEmpty();
    }
}
