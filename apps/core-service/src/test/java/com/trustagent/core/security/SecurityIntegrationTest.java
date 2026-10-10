package com.trustagent.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.control.ControlDataSourceConfiguration;
import com.trustagent.core.support.SessionClient;
import com.trustagent.core.support.TestUsers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TASK-017a AC-01~06, 07(일부: 서비스 토큰 경로 공존), 11, 13. 제어 DB는 업무 DB와 **다른 데이터베이스**(같은 컨테이너)다.
 * 합성 사용자 3명은 demo-users runner가 환경변수(실행 중 생성한 임시 값)로 적재한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityIntegrationTest {

    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    private static final String CONTROL_DB = "trust_agent_control_test";
    private static final String STAFF_PASSWORD = "demo-" + UUID.randomUUID();
    private static final String REVIEWER_PASSWORD = "demo-" + UUID.randomUUID();
    private static final String BOTH_PASSWORD = "demo-" + UUID.randomUUID();
    private static final String TOOL_TOKEN = "test-tool-" + UUID.randomUUID();
    private static final String RECORD_TOKEN = "test-record-" + UUID.randomUUID();

    static {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + CONTROL_DB);
        } catch (SQLException exception) {
            throw new IllegalStateException("제어 DB 생성 실패", exception);
        }
    }

    private static String controlJdbcUrl() {
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + CONTROL_DB);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("management.server.port", () -> 0);
        registry.add("trust-agent.control-datasource.url", SecurityIntegrationTest::controlJdbcUrl);
        registry.add("trust-agent.control-datasource.username", POSTGRES::getUsername);
        registry.add("trust-agent.control-datasource.password", POSTGRES::getPassword);
        registry.add("trust-agent.control-datasource.flyway-enabled", () -> true);
        registry.add("trust-agent.demo-users.enabled", () -> true);
        registry.add(DemoUsers.environmentName(DemoUsers.STAFF_USER), () -> STAFF_PASSWORD);
        registry.add(DemoUsers.environmentName(DemoUsers.REVIEWER_USER), () -> REVIEWER_PASSWORD);
        registry.add(DemoUsers.environmentName(DemoUsers.BOTH_USER), () -> BOTH_PASSWORD);
        registry.add("trust-agent.tool-api.service-token", () -> TOOL_TOKEN);
        registry.add("trust-agent.preparation-record.service-token", () -> RECORD_TOKEN);
    }

    @Autowired private DataSource businessDataSource;
    @Autowired @Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) private JdbcClient control;
    @Autowired private ObjectMapper mapper;
    @Value("${local.server.port}") private int port;
    @Value("${local.management.port}") private int managementPort;

    // ---- AC-01: 비인증은 401 JSON, 리다이렉트 없음 ----

    @Test
    void unauthenticatedApiRequestsReturn401ProblemWithoutRedirect() throws Exception {
        SessionClient anonymous = SessionClient.anonymous(port);
        for (String path : List.of("/api/v1/session", "/api/v1/internal-policy/checklists/SIN-PREPAYMENT-FEE/applicable?businessDate=2026-10-06",
                "/api/v1/public-products/kb-seller-loan/observed-state", "/api/v1/reviews/proposals")) {
            HttpResponse<String> response = anonymous.get(path);
            assertEquals(401, response.statusCode(), path);
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"), path);
            assertTrue(response.headers().firstValue("Location").isEmpty(), path);
            assertEquals("UNAUTHENTICATED", mapper.readTree(response.body()).get("code").stringValue());
        }
        assertTrue(count("security_event where event_type = 'UNAUTHENTICATED'") >= 4);
    }

    // ---- AC-02: 로그인, 쿠키 속성, 세션 ID 재발급 ----

    @Test
    void loginIssuesSessionWithProtectedCookieAndRecordsEvent() throws Exception {
        SessionClient staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        HttpResponse<String> login = staff.loginResponse();
        assertEquals(200, login.statusCode());
        JsonNode body = mapper.readTree(login.body());
        assertEquals(DemoUsers.STAFF_USER, body.get("principal").stringValue());
        assertEquals("STAFF", body.get("activeRole").stringValue());
        assertEquals("main", body.get("workspaceId").stringValue());
        String setCookie = login.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.toLowerCase().contains("samesite=lax"), setCookie);
        assertFalse(login.body().contains(STAFF_PASSWORD));

        String firstSession = staff.sessionId().orElseThrow();
        HttpResponse<String> relogin = staff.relogin(DemoUsers.STAFF_USER, STAFF_PASSWORD);
        assertEquals(200, relogin.statusCode());
        assertNotEquals(firstSession, staff.sessionId().orElseThrow(), "로그인마다 세션 ID가 바뀐다(세션 고정 방지)");

        assertTrue(count("security_event where event_type = 'LOGIN_SUCCESS' and principal = '" + DemoUsers.STAFF_USER + "'") >= 2);
        assertEquals(200, staff.get("/api/v1/session").statusCode());
    }

    @Test
    void loginFailureIs401AndRecordedWithoutPassword() throws Exception {
        SessionClient anonymous = SessionClient.anonymous(port);
        anonymous.get("/api/v1/session");
        HttpResponse<String> failed = anonymous.postForm("/login", "username=" + DemoUsers.STAFF_USER + "&password=wrong-" + UUID.randomUUID());
        assertEquals(401, failed.statusCode());
        assertEquals("LOGIN_FAILED", mapper.readTree(failed.body()).get("code").stringValue());
        assertEquals(401, anonymous.get("/api/v1/session").statusCode());
        assertTrue(count("security_event where event_type = 'LOGIN_FAILURE' and principal = '" + DemoUsers.STAFF_USER + "'") >= 1);
        assertEquals(0, count("security_event where detail like '%wrong-%'"));
    }

    // ---- AC-03, 04: 역할 보유와 활성 역할 ----

    @Test
    void staffCannotReadReviewerPathAndReviewerCan() throws Exception {
        SessionClient staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        HttpResponse<String> denied = staff.get("/api/v1/reviews/proposals");
        assertEquals(403, denied.statusCode());
        assertEquals("FORBIDDEN", mapper.readTree(denied.body()).get("code").stringValue());
        assertTrue(count("security_event where event_type = 'ACCESS_DENIED' and principal = '" + DemoUsers.STAFF_USER + "' and path = '/api/v1/reviews/proposals'") >= 1);

        SessionClient reviewer = SessionClient.login(port, DemoUsers.REVIEWER_USER, REVIEWER_PASSWORD);
        HttpResponse<String> allowed = reviewer.get("/api/v1/reviews/proposals");
        assertEquals(200, allowed.statusCode());
        assertTrue(mapper.readTree(allowed.body()).get("proposals").isArray());
        int applicable = reviewer.get("/api/v1/internal-policy/checklists/SIN-PREPAYMENT-FEE/applicable?businessDate=2026-10-06").statusCode();
        assertTrue(applicable != 401 && applicable != 403, "조회 경로는 STAFF 또는 REVIEWER 활성이면 인증·권한을 통과한다(자료 미적재라 404): " + applicable);
        HttpResponse<String> notHeld = reviewer.postJson("/api/v1/session/active-role", "{\"role\":\"STAFF\"}");
        assertEquals(403, notHeld.statusCode());
        assertEquals("ROLE_NOT_HELD", mapper.readTree(notHeld.body()).get("code").stringValue());
    }

    @Test
    void bothRolesUserSwitchesActiveRoleWithoutReloginAndSwitchIsRecorded() throws Exception {
        SessionClient both = SessionClient.login(port, DemoUsers.BOTH_USER, BOTH_PASSWORD);
        JsonNode session = mapper.readTree(both.get("/api/v1/session").body());
        assertEquals(List.of("REVIEWER", "STAFF"), List.of(session.get("roles").get(0).stringValue(), session.get("roles").get(1).stringValue()));
        assertEquals("STAFF", session.get("activeRole").stringValue(), "겸임의 기본 활성 역할은 STAFF");
        assertEquals(403, both.get("/api/v1/reviews/proposals").statusCode(), "보유해도 활성 역할이 아니면 거부");

        HttpResponse<String> switched = both.postJson("/api/v1/session/active-role", "{\"role\":\"REVIEWER\"}");
        assertEquals(200, switched.statusCode());
        assertEquals("REVIEWER", mapper.readTree(switched.body()).get("activeRole").stringValue());
        assertEquals(200, both.get("/api/v1/reviews/proposals").statusCode());
        assertEquals("REVIEWER", mapper.readTree(both.get("/api/v1/session").body()).get("activeRole").stringValue());
        assertEquals(400, both.postJson("/api/v1/session/active-role", "{\"role\":\"ADMIN\"}").statusCode());
        assertEquals(1, count("security_event where event_type = 'ROLE_SWITCH' and principal = '" + DemoUsers.BOTH_USER + "' and active_role = 'REVIEWER'"));
    }

    @Test
    void roleSwitchIsNotAppliedWhenSecurityEventCannotBeRecorded() throws Exception {
        // 제어 DB의 security_event 표를 잠시 다른 이름으로 바꿔 기록 실패를 주입한다. 전환은 완료되지 않고 이전 활성 역할이 유지돼야 한다.
        SessionClient both = SessionClient.login(port, DemoUsers.BOTH_USER, BOTH_PASSWORD);
        assertEquals("STAFF", mapper.readTree(both.get("/api/v1/session").body()).get("activeRole").stringValue());
        control.sql("alter table security_event rename to security_event_unavailable").update();
        try {
            HttpResponse<String> failed = both.postJson("/api/v1/session/active-role", "{\"role\":\"REVIEWER\"}");
            assertEquals(503, failed.statusCode());
            assertEquals("SECURITY_EVENT_WRITE_FAILED", mapper.readTree(failed.body()).get("code").stringValue());
            assertEquals("STAFF", mapper.readTree(both.get("/api/v1/session").body()).get("activeRole").stringValue(), "기록 없이 역할이 바뀌지 않는다");
            assertEquals(403, both.get("/api/v1/reviews/proposals").statusCode());
        } finally {
            control.sql("alter table security_event_unavailable rename to security_event").update();
        }
        HttpResponse<String> switched = both.postJson("/api/v1/session/active-role", "{\"role\":\"REVIEWER\"}");
        assertEquals(200, switched.statusCode());
        assertEquals(200, both.get("/api/v1/reviews/proposals").statusCode());
        assertEquals(1, count("security_event where event_type = 'ROLE_SWITCH' and principal = '" + DemoUsers.BOTH_USER + "' and active_role = 'REVIEWER' and path = '/api/v1/session/active-role'") >= 1 ? 1 : 0);
    }

    // ---- AC-06: 본문의 actor 문자열은 쓰이지 않는다 ----

    @Test
    void bodyActorFieldsAreIgnored() throws Exception {
        SessionClient staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        HttpResponse<String> response = staff.postJson("/api/v1/session/active-role", "{\"role\":\"STAFF\",\"principal\":\"SYN-REVIEWER-01\",\"roles\":[\"REVIEWER\"]}");
        assertEquals(200, response.statusCode());
        assertEquals(DemoUsers.STAFF_USER, mapper.readTree(response.body()).get("principal").stringValue());
        assertEquals(403, staff.get("/api/v1/reviews/proposals").statusCode());
    }

    // ---- CSRF ----

    @Test
    void stateChangingRequestWithoutCsrfTokenIsRejectedAndRecorded() throws Exception {
        SessionClient staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        HttpResponse<String> rejected = staff.postJsonWithoutCsrf("/api/v1/session/active-role", "{\"role\":\"STAFF\"}");
        assertEquals(403, rejected.statusCode());
        assertEquals("CSRF_REJECTED", mapper.readTree(rejected.body()).get("code").stringValue());
        assertTrue(count("security_event where event_type = 'CSRF_REJECTED' and principal = '" + DemoUsers.STAFF_USER + "'") >= 1);
    }

    // ---- 로그아웃 ----

    @Test
    void logoutInvalidatesSession() throws Exception {
        SessionClient staff = SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        assertEquals(204, staff.logout().statusCode());
        assertEquals(401, staff.get("/api/v1/session").statusCode());
        assertTrue(count("security_event where event_type = 'LOGOUT' and principal = '" + DemoUsers.STAFF_USER + "'") >= 1);
    }

    // ---- AC-07(공존), 11: 서비스 토큰 경로는 세션·CSRF 없이 기존대로 ----

    @Test
    void serviceTokenPathsIgnoreSessionsAndNeverRedirect() throws Exception {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> missing = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tools/applicable_checklist"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"familyId\":\"SIN-PREPAYMENT-FEE\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, missing.statusCode());
        assertTrue(missing.headers().firstValue("Location").isEmpty());
        assertTrue(missing.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.startsWith("JSESSIONID=")));
        assertEquals("UNAUTHENTICATED", mapper.readTree(missing.body()).get("code").stringValue());

        HttpResponse<String> withToken = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tools/applicable_checklist"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + TOOL_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{\"familyId\":\"SIN-PREPAYMENT-FEE\",\"businessDate\":\"2026-10-06\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertNotEquals(401, withToken.statusCode());
        assertNotEquals(403, withToken.statusCode());
        assertTrue(withToken.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.startsWith("JSESSIONID=")), "토큰 경로는 세션을 만들지 않는다");

        HttpResponse<String> record = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/consultation-preparations"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, record.statusCode());
        assertTrue(record.headers().firstValue("Location").isEmpty());
    }

    @Test
    void loggedInSessionCannotSubstituteServiceTokenOnToolAndRecordPaths() throws Exception {
        // permitAll은 Spring Security 단계의 허용일 뿐이다. 토큰 필터(@Order HIGHEST_PRECEDENCE+20·21)는 보안 체인(-100)보다 먼저 실행되어 토큰 없는 호출을 거부한다.
        SessionClient reviewer = SessionClient.login(port, DemoUsers.REVIEWER_USER, REVIEWER_PASSWORD);
        HttpResponse<String> tool = reviewer.postJsonWithoutCsrf("/api/v1/tools/applicable_checklist", "{\"familyId\":\"SIN-PREPAYMENT-FEE\"}");
        assertEquals(401, tool.statusCode());
        assertEquals("UNAUTHENTICATED", mapper.readTree(tool.body()).get("code").stringValue());
        HttpResponse<String> record = reviewer.postJsonWithoutCsrf("/api/v1/consultation-preparations", "{}");
        assertEquals(401, record.statusCode());
        HttpResponse<String> unknownTool = reviewer.postJsonWithoutCsrf("/api/v1/tools/approve_proposal", "{}");
        assertEquals(401, unknownTool.statusCode(), "allowlist 밖 이름도 토큰 검사가 먼저다");
    }

    @Test
    void managementHealthNeedsNoLogin() throws Exception {
        HttpResponse<String> health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/liveness")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertNotEquals(401, health.statusCode());
        assertNotEquals(403, health.statusCode());
    }

    // ---- AC-13, 제어 DB 분리 ----

    @Test
    void controlTablesLiveOnlyInControlDatabaseAndDemoUsersHaveRoles() throws Exception {
        JdbcClient business = JdbcClient.create(businessDataSource);
        assertEquals(0, business.sql("select count(*) from information_schema.tables where table_name in ('app_user', 'security_event')").query(Integer.class).single());
        assertEquals(2, control.sql("select count(*) from information_schema.tables where table_name in ('app_user', 'security_event')").query(Integer.class).single());
        assertEquals(2, control.sql("select count(*) from flyway_control_schema_history where success").query(Integer.class).single()); // V1 인증, V2 grant
        assertEquals(3, control.sql("select count(*) from app_user where synthetic").query(Integer.class).single());
        assertEquals(List.of("REVIEWER", "STAFF"), control.sql("select role from app_user_role where user_id = :id order by role").param("id", DemoUsers.BOTH_USER).query(String.class).list());
        assertTrue(control.sql("select password_hash from app_user where user_id = :id").param("id", DemoUsers.STAFF_USER).query(String.class).single().startsWith("{bcrypt}"));
        assertEquals(0, control.sql("select count(*) from app_user where password_hash like :p").param("p", "%" + STAFF_PASSWORD + "%").query(Integer.class).single());
    }

    @Test
    void securityEventTableIsAppendOnly() throws Exception {
        SessionClient.login(port, DemoUsers.STAFF_USER, STAFF_PASSWORD);
        org.springframework.dao.DataAccessException error = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> control.sql("delete from security_event").update());
        assertTrue(String.valueOf(error.getMostSpecificCause().getMessage()).contains("append-only"), error.getMostSpecificCause().getMessage());
    }

    private int count(String tableAndFilter) {
        return control.sql("select count(*) from " + tableAndFilter).query(Integer.class).single();
    }
}
