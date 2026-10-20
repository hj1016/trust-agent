package com.trustagent.core.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Core → AI 서비스 준비안 요청(내부망). 수신 토큰과 grant ID를 넘기고 응답 상태·본문을 그대로 돌려준다.
 * 연결 실패 502, 시간 초과 504. 자동 재시도는 없다(grant는 만료로 소멸).
 */
@Component
public class AiServiceClient {

    public record Response(int status, String body) {}

    private final AiServiceProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public AiServiceClient(AiServiceProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    /** Core → AI 서비스 추가 질문(근거 검색 + RAG). grant ID와 수신 토큰을 넘긴다. 응답은 Core가 다시 확인한 뒤에만 브라우저로 간다. */
    public Response answer(String question, String familyId, String businessDate, String consultationId, String grantId, String traceId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("question", question);
        body.put("familyId", familyId);
        body.put("businessDate", businessDate);
        body.put("consultationId", consultationId);
        body.put("grantId", grantId);
        return post("/api/v1/ai/answers", body, traceId);
    }

    public Response prepare(String applicationId, String businessDate, String consultationId, String grantId, String traceId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("applicationId", applicationId);
        body.put("businessDate", businessDate);
        body.put("consultationId", consultationId);
        body.put("grantId", grantId);
        return post("/api/v1/ai/consultation-preparations", body, traceId);
    }

    private Response post(String path, ObjectNode body, String traceId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.baseUrl().replaceAll("/+$", "") + path))
                .timeout(properties.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Trace-Id", traceId == null ? "unavailable" : traceId)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (properties.tokenConfigured()) {
            request.header("Authorization", "Bearer " + properties.inboundToken());
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new AiServiceException("AI_SERVICE_TIMEOUT", 504, "AI 서비스 응답이 시간 안에 오지 않았습니다.", exception);
        } catch (IOException exception) {
            throw new AiServiceException("AI_SERVICE_UNAVAILABLE", 502, "AI 서비스에 연결하지 못했습니다.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("AI_SERVICE_UNAVAILABLE", 502, "AI 서비스 호출이 중단됐습니다.", exception);
        }
    }
}
