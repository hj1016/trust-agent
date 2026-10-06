package com.trustagent.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** TASK-008 AC-01(사례 K): 토큰 설정이 없는 환경에서는 어떤 토큰으로도 Tool을 호출할 수 없다(누락은 허용이 아니다). */
@SpringBootTest(classes = TrustAgentCoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolApiMissingTokenIntegrationTest {

    private static final String POSTGRES_IMAGE =
            "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("management.server.port", () -> 0);
        registry.add("trust-agent.tool-api.service-token", () -> "");
    }

    @Value("${local.server.port}")
    private int port;

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void everyToolCallIsRefusedWhenNoTokenIsConfigured() throws Exception {
        for (String token : new String[] {null, "", "any-" + UUID.randomUUID()}) {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/tools/applicable_checklist"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"familyId\":\"SIN-PREPAYMENT-FEE\"}"));
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(), response.body());
            assertTrue(response.body().contains("UNAUTHENTICATED"));
        }
    }
}
