package com.trustagent.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class PublicProductSchemaIntegrationTest {

    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final String HASH = "sha256:" + "a".repeat(64);
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void migrateSchema() throws SQLException {
        POSTGRES.start();
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(true)
                .load();
        assertEquals(5, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);

        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE trust_agent_runtime_test LOGIN PASSWORD 'runtime-password'");
            statement.execute("GRANT trust_agent_runtime TO trust_agent_runtime_test");
            statement.execute("CREATE ROLE trust_agent_maintenance_test LOGIN PASSWORD 'maintenance-password'");
            statement.execute("GRANT trust_agent_maintenance TO trust_agent_maintenance_test");
            statement.execute("CREATE ROLE trust_agent_migration_test LOGIN PASSWORD 'migration-password'");
            statement.execute("GRANT trust_agent_migration TO trust_agent_migration_test");
            statement.execute("CREATE ROLE trust_agent_importer_test LOGIN PASSWORD 'importer-password'");
            statement.execute("GRANT trust_agent_importer TO trust_agent_importer_test");
            statement.execute("""
                    INSERT INTO public_product (
                        product_key, dataset_class, synthetic, display_name,
                        source_marker, source_url, source_record_hash
                    ) VALUES (
                        'kb-seller-loan', 'PUBLIC_KB', false, 'KB 셀러론',
                        'KB셀러론', 'https://obank.kbstar.com/example', '%s'
                    )
                    """.formatted(HASH));
            statement.execute("""
                    INSERT INTO baseline_import_run (
                        baseline_import_run_id, baseline_fingerprint, started_at,
                        completed_at, status, input_files, imported_counts, error_code
                    ) VALUES (
                        'baseline:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '%s',
                        '2026-09-21T05:11:37Z', '2026-09-21T05:11:37Z',
                        'SUCCEEDED', '[]'::jsonb, '{}'::jsonb, null
                    )
                    """.formatted(HASH));
        }
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void migrationCreatesTwentyFiveApplicationTablesAndVersionFour() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                    SELECT count(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name <> 'flyway_schema_history'
                    """)) {
                assertTrue(result.next());
                assertEquals(25, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = true AND version IS NOT NULL
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)) {
                assertTrue(result.next());
                assertEquals("5", result.getString(1));
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT count(*)
                    FROM trust_agent_protected_tables() expected
                    CROSS JOIN LATERAL (
                        VALUES (expected.guard_trigger_name), (expected.truncate_trigger_name)
                    ) expected_trigger(trigger_name)
                    JOIN pg_class relation ON relation.relname = expected.protected_table_name
                    JOIN pg_namespace namespace
                      ON namespace.oid = relation.relnamespace AND namespace.nspname = 'public'
                    JOIN pg_trigger actual
                      ON actual.tgrelid = relation.oid
                     AND actual.tgname = expected_trigger.trigger_name
                     AND NOT actual.tgisinternal
                    WHERE actual.tgenabled = 'O'
                    """)) {
                assertTrue(result.next());
                assertEquals(50, result.getInt(1));
            }

            SQLException missingPrimaryKey = assertThrows(
                    SQLException.class,
                    () -> statement.executeQuery(
                            "select trust_agent_primary_key('missing', '{}'::jsonb)"));
            assertEquals("55000", missingPrimaryKey.getSQLState());
        }
    }

    @Test
    void runtimeRoleCannotUpdateDeleteOrCreateTablesEvenWithMaintenanceSettings() throws SQLException {
        try (Connection connection = roleConnection("trust_agent_runtime_test", "runtime-password");
                Statement statement = connection.createStatement()) {
            SQLException updateError = assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("""
                            UPDATE public_product
                            SET display_name = '변조'
                            WHERE product_key = 'kb-seller-loan'
                            """));
            assertEquals("42501", updateError.getSQLState());

            statement.execute("SELECT set_config('trust_agent.maintenance_ticket', 'TICKET-1', false)");
            statement.execute("SELECT set_config('trust_agent.maintenance_reason', '권한 위조', false)");
            statement.execute("SELECT set_config('trust_agent.maintenance_actor', 'runtime', false)");
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("""
                            UPDATE public_product
                            SET display_name = '변조'
                            WHERE product_key = 'kb-seller-loan'
                            """));
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("""
                            DELETE FROM public_product
                            WHERE product_key = 'kb-seller-loan'
                            """));
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE forbidden(id int)"));
        }
    }

    @Test
    void roleMembershipKeepsMaintenanceAndAuditOwnerBoundariesSeparate() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT
                            pg_has_role('trust_agent_runtime', 'trust_agent_maintenance', 'member'),
                            pg_has_role('trust_agent_migration', 'trust_agent_maintenance', 'member'),
                            pg_has_role('trust_agent_runtime', 'trust_agent_audit_owner', 'member'),
                            pg_has_role('trust_agent_migration', 'trust_agent_audit_owner', 'member'),
                            pg_has_role('trust_agent_maintenance', 'trust_agent_audit_owner', 'member'),
                            pg_has_role('trust_agent_importer', 'trust_agent_maintenance', 'member'),
                            pg_has_role('trust_agent_importer', 'trust_agent_audit_owner', 'member'),
                            (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'trust_agent_audit_owner'),
                            (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'trust_agent_importer')
                        """)) {
            assertTrue(result.next());
            assertEquals(false, result.getBoolean(1));
            assertEquals(false, result.getBoolean(2));
            assertEquals(false, result.getBoolean(3));
            assertEquals(false, result.getBoolean(4));
            assertEquals(false, result.getBoolean(5));
            assertEquals(false, result.getBoolean(6));
            assertEquals(false, result.getBoolean(7));
            assertEquals(false, result.getBoolean(8));
            assertEquals(false, result.getBoolean(9));
        }
    }

    @Test
    void importerRoleCanOnlyReadAndInsertBaselineRows() throws SQLException {
        try (Connection connection = roleConnection("trust_agent_importer_test", "importer-password");
                Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("select count(*) from public_product")) {
                assertTrue(result.next());
            }
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO public_product (
                        product_key, dataset_class, synthetic, display_name,
                        source_marker, source_url, source_record_hash
                    ) VALUES (
                        'importer-permission-product', 'PUBLIC_KB', false, '권한 검증 상품',
                        '권한검증', 'https://obank.kbstar.com/importer-permission', '%s'
                    )
                    """.formatted(HASH)));
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("""
                            UPDATE public_product SET display_name = '변조'
                            WHERE product_key = 'importer-permission-product'
                            """));
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("""
                            DELETE FROM public_product
                            WHERE product_key = 'importer-permission-product'
                            """));
            assertThrows(SQLException.class, () -> statement.execute("TRUNCATE public_product CASCADE"));
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE importer_forbidden(id int)"));
            assertThrows(SQLException.class, () -> statement.executeQuery("select * from maintenance_change_audit"));
        }
    }

    @Test
    void observedStateQueryIndexesAreCreated() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select count(*) from pg_indexes
                        where schemaname = 'public' and indexname in (
                          'public_observation_product_time_idx',
                          'extraction_attempt_observation_time_idx',
                          'extraction_attempt_product_success_time_idx',
                          'collection_attempt_product_time_idx'
                        )
                        """)) {
            assertTrue(result.next());
            assertEquals(4, result.getInt(1));
        }
    }

    @Test
    void maintenanceRequiresAllSettingsAndAutomaticallyAuditsOldAndNewRows() throws SQLException {
        try (Connection connection = roleConnection("trust_agent_maintenance_test", "maintenance-password")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                assertThrows(
                        SQLException.class,
                        () -> statement.executeUpdate("""
                                UPDATE public_product
                                SET display_name = '정비된 이름'
                                WHERE product_key = 'kb-seller-loan'
                                """));
                connection.rollback();

                statement.execute("SELECT set_config('trust_agent.maintenance_ticket', 'OPS-2026-001', true)");
                statement.execute("SELECT set_config('trust_agent.maintenance_reason', '작업자 누락', true)");
                assertThrows(
                        SQLException.class,
                        () -> statement.executeUpdate("""
                                UPDATE public_product
                                SET display_name = '정비된 이름'
                                WHERE product_key = 'kb-seller-loan'
                                """));
                connection.rollback();

                statement.execute("SELECT set_config('trust_agent.maintenance_ticket', 'OPS-2026-001', true)");
                statement.execute("SELECT set_config('trust_agent.maintenance_reason', 'baseline 정정 검증', true)");
                statement.execute("SELECT set_config('trust_agent.maintenance_actor', 'operator-1', true)");
                assertEquals(1, statement.executeUpdate("""
                        UPDATE public_product
                        SET display_name = '정비된 이름'
                        WHERE product_key = 'kb-seller-loan'
                        """));
                connection.commit();
            }
        }

        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT operation, actor_id, ticket_id, reason,
                               primary_key, old_record, new_record,
                               old_record_hash, new_record_hash
                        FROM maintenance_change_audit
                        """)) {
            assertTrue(result.next());
            assertEquals("UPDATE", result.getString("operation"));
            assertEquals("operator-1", result.getString("actor_id"));
            assertEquals("OPS-2026-001", result.getString("ticket_id"));
            assertEquals("baseline 정정 검증", result.getString("reason"));
            assertTrue(result.getString("primary_key").contains("kb-seller-loan"));
            assertTrue(result.getString("old_record").contains("KB 셀러론"));
            assertTrue(result.getString("new_record").contains("정비된 이름"));
            assertNotNull(result.getString("old_record_hash"));
            assertNotNull(result.getString("new_record_hash"));
        }
    }

    @Test
    void maintenanceRoleCannotAlterItsOwnAuditTrail() throws SQLException {
        try (Connection connection = roleConnection("trust_agent_maintenance_test", "maintenance-password");
                Statement statement = connection.createStatement()) {
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("""
                            UPDATE maintenance_change_audit
                            SET reason = '숨김'
                            WHERE maintenance_change_audit_id = 1
                            """));
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("""
                            DELETE FROM maintenance_change_audit
                            WHERE maintenance_change_audit_id = 1
                            """));
        }
    }

    @Test
    void maintenanceAuditJsonAndHashesAreStableAcrossSessionTimezones() throws SQLException {
        performNoOpMaintenanceUpdate("Asia/Seoul", "OPS-TZ-SEOUL");
        performNoOpMaintenanceUpdate("America/New_York", "OPS-TZ-NEW-YORK");

        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT old_record::text, new_record::text, old_record_hash, new_record_hash
                        FROM maintenance_change_audit
                        WHERE table_name = 'baseline_import_run'
                        ORDER BY maintenance_change_audit_id
                        """)) {
            assertTrue(result.next());
            String firstOldRecord = result.getString(1);
            String firstOldHash = result.getString(3);
            assertTrue(firstOldRecord.contains("2026-09-21T05:11:37+00:00"));
            assertEquals(firstOldRecord, result.getString(2));
            assertEquals(firstOldHash, result.getString(4));

            assertTrue(result.next());
            assertEquals(firstOldRecord, result.getString(1));
            assertEquals(firstOldRecord, result.getString(2));
            assertEquals(firstOldHash, result.getString(3));
            assertEquals(firstOldHash, result.getString(4));
            assertEquals(false, result.next());
        }
    }

    @Test
    void migrationRoleCannotDisableOrDropAppendOnlyTriggers() throws SQLException {
        try (Connection connection = roleConnection("trust_agent_migration_test", "migration-password");
                Statement statement = connection.createStatement()) {
            SQLException disableError = assertThrows(
                    SQLException.class,
                    () -> statement.execute("""
                            ALTER TABLE public_product
                            DISABLE TRIGGER guard_public_product_append_only
                            """));
            assertEquals("42501", disableError.getSQLState());

            SQLException dynamicDisableError = assertThrows(
                    SQLException.class,
                    () -> statement.execute("""
                            DO $block$
                            BEGIN
                                EXECUTE 'ALTER TABLE public_product DIS'
                                    || 'ABLE TRI'
                                    || 'GGER guard_public_product_append_only';
                            END
                            $block$
                            """));
            assertEquals("42501", dynamicDisableError.getSQLState());

            SQLException dropError = assertThrows(
                    SQLException.class,
                    () -> statement.execute("""
                            DROP TRIGGER guard_public_product_append_only ON public_product
                            """));
            assertEquals("42501", dropError.getSQLState());

            SQLException truncateError = assertThrows(
                    SQLException.class,
                    () -> statement.execute("TRUNCATE TABLE public_product CASCADE"));
            assertEquals("42501", truncateError.getSQLState());
        }

        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT tgenabled
                        FROM pg_trigger
                        WHERE tgname = 'guard_public_product_append_only'
                        """)) {
            assertTrue(result.next());
            assertEquals("O", result.getString(1));
        }
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT count(*)
                        FROM public_product
                        WHERE product_key = 'kb-seller-loan'
                        """)) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    private static void performNoOpMaintenanceUpdate(String timezone, String ticket)
            throws SQLException {
        try (Connection connection = roleConnection("trust_agent_maintenance_test", "maintenance-password")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE '" + timezone + "'");
                statement.execute("SELECT set_config('trust_agent.maintenance_ticket', '" + ticket + "', true)");
                statement.execute("SELECT set_config('trust_agent.maintenance_reason', 'timezone 검증', true)");
                statement.execute("SELECT set_config('trust_agent.maintenance_actor', 'operator-1', true)");
                assertEquals(1, statement.executeUpdate("""
                        UPDATE baseline_import_run
                        SET started_at = started_at
                        WHERE baseline_import_run_id = 'baseline:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                        """));
                connection.commit();
            }
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection roleConnection(String username, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), properties);
    }
}
