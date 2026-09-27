package com.trustagent.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class SyntheticInternalSchemaIntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final String HASH = "sha256:" + "b".repeat(64);
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void migrate() throws SQLException {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(true).load().migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO internal_notice_version VALUES ('SIN-X-V1','SYNTHETIC_INTERNAL',true,'합성 테스트 데이터이며 실제 내부자료가 아닙니다.','SIN-X',1,'합성','ISSUED','2026-09-01','2026-09-01',null,null,'" + HASH + "')");
            statement.execute("INSERT INTO approved_checklist_version VALUES ('approved-checklist:11111111111111111111111111111111','SYNTHETIC_INTERNAL','SIN-X','SIN-X-V1','2026-09-01T00:00:00Z','" + HASH + "')");
            statement.execute("INSERT INTO approved_checklist_schedule_revision VALUES ('checklist-schedule:11111111111111111111111111111111','SYNTHETIC_INTERNAL','SIN-X',null,'2026-09-01T00:00:00Z','" + HASH + "')");

            statement.execute("INSERT INTO internal_notice_version VALUES ('SIN-RANGE-V1','SYNTHETIC_INTERNAL',true,'합성 테스트 데이터이며 실제 내부자료가 아닙니다.','SIN-RANGE',1,'합성','ISSUED','2026-09-01','2026-09-01',null,null,'" + HASH + "')");
            statement.execute("INSERT INTO approved_checklist_version VALUES ('approved-checklist:55555555555555555555555555555555','SYNTHETIC_INTERNAL','SIN-RANGE','SIN-RANGE-V1','2026-09-01T00:00:00Z','" + HASH + "')");
        }
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void btreeGistAndExclusionConstraintAreMandatory() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("select count(*) from pg_extension where extname='btree_gist'")) {
                assertTrue(result.next()); assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery("select count(*) from pg_constraint where conrelid='approved_checklist_schedule_entry'::regclass and contype='x'")) {
                assertTrue(result.next()); assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void syntheticImporterRoleHasOnlyRequiredPrivilegesAndNoElevatedMembership() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select
                          has_table_privilege('trust_agent_synthetic_importer','internal_notice_version','SELECT'),
                          has_table_privilege('trust_agent_synthetic_importer','internal_notice_version','INSERT'),
                          has_table_privilege('trust_agent_synthetic_importer','internal_notice_version','UPDATE'),
                          has_table_privilege('trust_agent_synthetic_importer','internal_notice_version','DELETE'),
                          pg_has_role('trust_agent_synthetic_importer','trust_agent_maintenance','member'),
                          pg_has_role('trust_agent_synthetic_importer','trust_agent_audit_owner','member')
                        """)) {
            assertTrue(result.next());
            assertEquals(true, result.getBoolean(1));
            assertEquals(true, result.getBoolean(2));
            assertEquals(false, result.getBoolean(3));
            assertEquals(false, result.getBoolean(4));
            assertEquals(false, result.getBoolean(5));
            assertEquals(false, result.getBoolean(6));
        }
    }

    @Test
    void adjacentHalfOpenRangesAreAllowedButOneDayOverlapIsRejected() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO approved_checklist_schedule_revision VALUES ('checklist-schedule:55555555555555555555555555555555','SYNTHETIC_INTERNAL','SIN-RANGE',null,'2026-09-01T00:00:00Z','" + HASH + "')");
            statement.execute("INSERT INTO approved_checklist_schedule_entry VALUES ('checklist-schedule:55555555555555555555555555555555','SIN-RANGE',0,'approved-checklist:55555555555555555555555555555555','2026-09-01','2026-10-01','" + HASH + "')");
            statement.execute("INSERT INTO approved_checklist_schedule_entry VALUES ('checklist-schedule:55555555555555555555555555555555','SIN-RANGE',1,'approved-checklist:55555555555555555555555555555555','2026-10-01','2026-11-01','" + HASH + "')");
            SQLException overlap = assertThrows(SQLException.class, () -> statement.execute("INSERT INTO approved_checklist_schedule_entry VALUES ('checklist-schedule:55555555555555555555555555555555','SIN-RANGE',2,'approved-checklist:55555555555555555555555555555555','2026-09-30','2026-10-02','" + HASH + "')"));
            assertEquals("23P01", overlap.getSQLState());
        }
    }

    @Test
    void oneRootRevisionPerFamilyIsEnforced() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            SQLException duplicate = assertThrows(SQLException.class, () -> statement.execute("INSERT INTO approved_checklist_schedule_revision VALUES ('checklist-schedule:22222222222222222222222222222222','SYNTHETIC_INTERNAL','SIN-X',null,'2026-09-02T00:00:00Z','" + HASH + "')"));
            assertEquals("23505", duplicate.getSQLState());
        }
    }

    @Test
    void supersessionCannotCrossPolicyFamilies() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            SQLException mismatch = assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO approved_checklist_schedule_revision VALUES ('checklist-schedule:77777777777777777777777777777777','SYNTHETIC_INTERNAL','SIN-OTHER','checklist-schedule:11111111111111111111111111111111','2026-10-01T00:00:00Z','" + HASH + "')"));
            assertEquals("23503", mismatch.getSQLState());
        }
    }

    @Test
    void invalidNoticeEffectiveIntervalIsRejected() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            SQLException invalid = assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO internal_notice_version VALUES ('SIN-BAD-V1','SYNTHETIC_INTERNAL',true,'합성 테스트 데이터이며 실제 내부자료가 아닙니다.','SIN-BAD',1,'합성','ISSUED','2026-09-01','2026-10-01','2026-10-01',null,'" + HASH + "')"));
            assertEquals("23514", invalid.getSQLState());
        }
    }

    @Test
    void concurrentSupersessionAllowsExactlyOneTransaction() throws Exception {
        String parent = "checklist-schedule:11111111111111111111111111111111";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> insertSupersession("checklist-schedule:33333333333333333333333333333333", parent, ready, start));
            Future<Boolean> second = executor.submit(() -> insertSupersession("checklist-schedule:44444444444444444444444444444444", parent, ready, start));
            ready.await(); start.countDown();
            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes);
        }
    }

    private static boolean insertSupersession(String id, String parent, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false); ready.countDown(); start.await();
            try {
                statement.execute("INSERT INTO approved_checklist_schedule_revision VALUES ('" + id + "','SYNTHETIC_INTERNAL','SIN-X','" + parent + "','2026-10-01T00:00:00Z','" + HASH + "')");
                connection.commit(); return true;
            } catch (SQLException error) {
                connection.rollback();
                if (!"23505".equals(error.getSQLState())) throw error;
                return false;
            }
        }
    }

    private static Connection connection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", POSTGRES.getUsername()); properties.setProperty("password", POSTGRES.getPassword());
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), properties);
    }
}
