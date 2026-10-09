package com.trustagent.core.search;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Elasticsearch REST 호출(표준 HttpClient, basic 인증). Core 재색인 전용이며 자격증명은 설정에서만 받는다.
 * 2xx가 아니면 SearchIndexException(코드, HTTP 상태)로 바꾼다. 404는 존재 확인 메서드에서만 정상 값이다.
 */
public class ElasticsearchClient {

    public record Response(int status, String body) {
        boolean success() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http;
    private final String baseUrl;
    private final String authorization;
    private final Duration timeout;
    private final ObjectMapper mapper;

    public ElasticsearchClient(String baseUrl, String username, String password, Duration timeout, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.authorization = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.timeout = timeout;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public Response exchange(String method, String path, String body, String contentType) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (IOException exception) {
            throw new SearchIndexException("SEARCH_UNAVAILABLE", "Elasticsearch에 연결하지 못했습니다: " + exception.getClass().getSimpleName(), 0, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchIndexException("SEARCH_INTERRUPTED", "Elasticsearch 호출이 중단됐습니다", 0, exception);
        }
    }

    private Response require(Response response, String code) {
        if (!response.success()) {
            throw new SearchIndexException(code, "Elasticsearch 응답 " + response.status() + ": " + truncate(response.body()), response.status(), null);
        }
        return response;
    }

    private JsonNode json(Response response) {
        if (response.body() == null || response.body().isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(response.body());
    }

    public boolean indexExists(String index) {
        Response response = exchange("HEAD", "/" + index, null, null);
        if (response.status() == 404) return false;
        require(response, "SEARCH_INDEX_EXISTS_FAILED");
        return true;
    }

    public void createIndex(String index, JsonNode definition) {
        require(exchange("PUT", "/" + index, mapper.writeValueAsString(definition), "application/json"), "SEARCH_INDEX_CREATE_FAILED");
    }

    public void deleteIndex(String index) {
        Response response = exchange("DELETE", "/" + index, null, null);
        if (response.status() != 404) require(response, "SEARCH_INDEX_DELETE_FAILED");
    }

    /** _bulk 색인. 문서 ID는 docs[].get("rule_version_id"). 부분 실패도 예외다. */
    public void bulkIndex(String index, List<ObjectNode> docs) {
        if (docs.isEmpty()) return;
        StringBuilder body = new StringBuilder();
        for (ObjectNode doc : docs) {
            ObjectNode action = mapper.createObjectNode();
            action.putObject("index").put("_index", index).put("_id", doc.get("rule_version_id").stringValue());
            body.append(mapper.writeValueAsString(action)).append('\n').append(mapper.writeValueAsString(doc)).append('\n');
        }
        JsonNode result = json(require(exchange("POST", "/_bulk?refresh=false", body.toString(), "application/x-ndjson"), "SEARCH_BULK_FAILED"));
        if (result.path("errors").asBoolean(false)) {
            throw new SearchIndexException("SEARCH_BULK_PARTIAL_FAILURE", "일부 문서 색인 실패: " + truncate(result.toString()), 200, null);
        }
    }

    public void refresh(String index) {
        require(exchange("POST", "/" + index + "/_refresh", null, null), "SEARCH_REFRESH_FAILED");
    }

    public long count(String index) {
        return json(require(exchange("GET", "/" + index + "/_count", null, null), "SEARCH_COUNT_FAILED")).path("count").asLong();
    }

    public JsonNode mappingMeta(String index) {
        JsonNode mapping = json(require(exchange("GET", "/" + index + "/_mapping", null, null), "SEARCH_MAPPING_FAILED"));
        return mapping.path(index).path("mappings").path("_meta");
    }

    public Optional<String> aliasTarget(String alias) {
        Response response = exchange("GET", "/_alias/" + alias, null, null);
        if (response.status() == 404) return Optional.empty();
        JsonNode body = json(require(response, "SEARCH_ALIAS_FAILED"));
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(names::add);
        if (names.size() > 1) {
            throw new SearchIndexException("SEARCH_ALIAS_AMBIGUOUS", "alias가 색인 여러 개를 가리킵니다: " + names, 200, null);
        }
        return names.stream().findFirst();
    }

    public void swapAlias(String alias, String newIndex, Optional<String> oldIndex) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode actions = body.putArray("actions");
        oldIndex.ifPresent(old -> actions.addObject().putObject("remove").put("index", old).put("alias", alias));
        actions.addObject().putObject("add").put("index", newIndex).put("alias", alias);
        require(exchange("POST", "/_aliases", mapper.writeValueAsString(body), "application/json"), "SEARCH_ALIAS_SWAP_FAILED");
    }

    /** 접두사로 색인 이름을 찾는다. 색인 권한(view_index_metadata)만 필요하도록 _cat 대신 _settings를 쓴다. */
    public List<String> indicesWithPrefix(String prefix) {
        Response response = exchange("GET", "/" + prefix + "*/_settings?expand_wildcards=open&filter_path=*.settings.index.provided_name", null, null);
        if (response.status() == 404) return List.of();
        JsonNode body = json(require(response, "SEARCH_LIST_FAILED"));
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(names::add);
        names.sort(String::compareTo);
        return names;
    }

    public JsonNode search(String index, JsonNode query) {
        return json(require(exchange("POST", "/" + index + "/_search", mapper.writeValueAsString(query), "application/json"), "SEARCH_QUERY_FAILED"));
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }
}
