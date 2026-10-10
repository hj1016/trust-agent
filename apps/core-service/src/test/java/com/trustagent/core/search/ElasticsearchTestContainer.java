package com.trustagent.core.search;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 Elasticsearch(ADR-013 결정 7과 같은 구성: 보안 활성, HTTP TLS 없음, 비밀번호는 실행 중 생성).
 * compose와 같은 이미지 digest를 쓰고, 기동 뒤 재색인 역할·사용자와 읽기 전용 역할·사용자를 만든다.
 */
final class ElasticsearchTestContainer {

    static final String IMAGE = "docker.elastic.co/elasticsearch/elasticsearch@sha256:bfb2108076735fbfc5dcd5d49f12f5aa93fab9759034c55f7da3b4345606c903";
    static final String ELASTIC_PASSWORD = "test-elastic-" + UUID.randomUUID();
    static final String REINDEX_USER = "trustagent_reindex";
    static final String REINDEX_PASSWORD = "test-reindex-" + UUID.randomUUID();
    static final String SEARCH_USER = "trustagent_search";
    static final String SEARCH_PASSWORD = "test-search-" + UUID.randomUUID();

    /**
     * 분석기 비교(TASK-016 제안 1): -PsearchAnalyzer=nori면 infra/docker/search/Dockerfile.nori(같은 digest + 버전 고정 플러그인, sha512 대조)로
     * 테스트 이미지를 만든다. 기동 뒤 플러그인과 ES 버전이 8.19.6인지 확인한다.
     */
    static final String ANALYZER = System.getProperty("trustAgent.searchAnalyzer", "standard");
    static final String EXPECTED_VERSION = "8.19.6";

    static final GenericContainer<?> CONTAINER = (SearchProperties.ANALYZER_NORI.equals(ANALYZER)
            ? new GenericContainer<>(new org.testcontainers.images.builder.ImageFromDockerfile("trustagent-es-nori-test", false)
                    .withDockerfile(java.nio.file.Path.of(System.getProperty("trustAgent.repositoryRoot", "."), "infra/docker/search/Dockerfile.nori")))
            : new GenericContainer<>(DockerImageName.parse(IMAGE)))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "true")
            .withEnv("xpack.security.http.ssl.enabled", "false")
            .withEnv("ELASTIC_PASSWORD", ELASTIC_PASSWORD)
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).withBasicCredentials("elastic", ELASTIC_PASSWORD)
                    .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    private ElasticsearchTestContainer() {
    }

    static String baseUrl() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(9200);
    }

    /** 컨테이너를 띄우고 역할·사용자를 만든다. 여러 테스트 클래스가 불러도 한 번만 실행된다. */
    static synchronized void start() {
        if (CONTAINER.isRunning()) return;
        CONTAINER.start();
        String indices = "trustagent-rule-evidence-*";
        superuser("PUT", "/_security/role/trustagent_reindex_role",
                "{\"indices\":[{\"names\":[\"" + indices + "\"],\"privileges\":[\"create_index\",\"delete_index\",\"manage\",\"write\",\"read\",\"view_index_metadata\"]}]}");
        superuser("PUT", "/_security/role/trustagent_search_role",
                "{\"indices\":[{\"names\":[\"" + indices + "\"],\"privileges\":[\"read\",\"view_index_metadata\"]}]}");
        superuser("PUT", "/_security/user/" + REINDEX_USER,
                "{\"password\":\"" + REINDEX_PASSWORD + "\",\"roles\":[\"trustagent_reindex_role\"]}");
        verifyVersions();
        superuser("PUT", "/_security/user/" + SEARCH_USER,
                "{\"password\":\"" + SEARCH_PASSWORD + "\",\"roles\":[\"trustagent_search_role\"]}");
    }

    /** ES 버전과(nori면) 플러그인 버전을 확인한다. 다르면 기동 실패로 끝낸다. */
    static String verifyVersions() {
        String root = superuserGet("/");
        if (!root.contains("\"number\" : \"" + EXPECTED_VERSION + "\"") && !root.contains("\"number\":\"" + EXPECTED_VERSION + "\"")) {
            throw new IllegalStateException("Elasticsearch 버전이 " + EXPECTED_VERSION + "이 아닙니다: " + root);
        }
        String plugins = superuserGet("/_cat/plugins?h=component,version");
        if (SearchProperties.ANALYZER_NORI.equals(ANALYZER) && !plugins.contains("analysis-nori " + EXPECTED_VERSION)) {
            throw new IllegalStateException("analysis-nori " + EXPECTED_VERSION + " 플러그인이 없습니다: " + plugins);
        }
        return plugins.trim();
    }

    private static String superuserGet(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("elastic:" + ELASTIC_PASSWORD).getBytes(StandardCharsets.UTF_8)))
                    .GET().build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception exception) {
            throw new IllegalStateException("ES 조회 실패: " + path, exception);
        }
    }

    private static void superuser(String method, String path, String body) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("elastic:" + ELASTIC_PASSWORD).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("ES 사용자 준비 실패 " + path + " " + response.statusCode());
            }
        } catch (java.io.IOException | InterruptedException exception) {
            throw new IllegalStateException("ES 사용자 준비 실패 " + path, exception);
        }
    }
}
