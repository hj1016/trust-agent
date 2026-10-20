package com.trustagent.core.search;

import com.trustagent.core.ai.AiServiceProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Core → AI 서비스 로컬 임베딩 API(내부, 수신 토큰). 응답 모델 표식·차원·개수를 Core 설정과 대조하고, 어긋나면 재색인을 멈춘다. */
class AiPassageEmbedder implements PassageEmbedder {

    private final AiServiceProperties ai;
    private final SearchVectorProperties vector;
    private final ObjectMapper mapper;
    private final HttpClient http;

    AiPassageEmbedder(AiServiceProperties ai, SearchVectorProperties vector, ObjectMapper mapper) {
        this.ai = ai;
        this.vector = vector;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(ai.timeout()).build();
    }

    @Override
    public Embeddings embed(List<String> passages) {
        List<float[]> vectors = new ArrayList<>();
        for (int start = 0; start < passages.size(); start += vector.batchSize()) {
            List<String> batch = passages.subList(start, Math.min(passages.size(), start + vector.batchSize()));
            JsonNode body = call(batch);
            if (!vector.modelTag().equals(body.path("model").stringValue()) || body.path("dimension").asInt() != vector.dimension()) {
                throw new SearchIndexException("SEARCH_EMBEDDING_MODEL_MISMATCH", "AI 서비스 임베딩 모델·차원이 설정과 다릅니다.");
            }
            JsonNode rows = body.path("vectors");
            if (!rows.isArray() || rows.size() != batch.size()) {
                throw new SearchIndexException("SEARCH_EMBEDDING_INVALID", "임베딩 개수가 요청과 다릅니다.");
            }
            for (JsonNode row : rows) {
                vectors.add(toVector(row));
            }
        }
        return new Embeddings(vector.modelTag(), vector.dimension(), vectors);
    }

    private float[] toVector(JsonNode row) {
        if (!row.isArray() || row.size() != vector.dimension()) {
            throw new SearchIndexException("SEARCH_EMBEDDING_INVALID", "임베딩 차원이 설정과 다릅니다.");
        }
        float[] values = new float[row.size()];
        double norm = 0;
        for (int i = 0; i < row.size(); i++) {
            double value = row.get(i).asDouble(Double.NaN);
            if (!Double.isFinite(value)) throw new SearchIndexException("SEARCH_EMBEDDING_INVALID", "임베딩 값이 숫자가 아닙니다.");
            values[i] = (float) value;
            norm += value * value;
        }
        if (Math.abs(norm - 1.0) > 1e-3) throw new SearchIndexException("SEARCH_EMBEDDING_INVALID", "임베딩이 정규화되지 않았습니다.");
        return values;
    }

    private JsonNode call(List<String> batch) {
        ObjectNode request = mapper.createObjectNode();
        request.put("kind", "passage");
        batch.forEach(request.putArray("texts")::add);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ai.baseUrl().replaceAll("/+$", "") + "/api/v1/ai/embeddings"))
                .timeout(ai.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request), StandardCharsets.UTF_8));
        if (ai.tokenConfigured()) builder.header("Authorization", "Bearer " + ai.inboundToken());
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new SearchIndexException("SEARCH_EMBEDDING_FAILED", "AI 서비스 임베딩 실패(HTTP " + response.statusCode() + ")");
            }
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new SearchIndexException("SEARCH_EMBEDDING_FAILED", "AI 서비스 임베딩 API에 연결하지 못했습니다.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchIndexException("SEARCH_EMBEDDING_FAILED", "AI 서비스 임베딩 호출이 중단됐습니다.");
        }
    }
}
