package com.trustagent.core.config;

import com.trustagent.core.preparation.PreparationRecordProperties;
import com.trustagent.core.tool.ToolApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.Configuration;

/**
 * 읽기 토큰(Tool API)과 기록 토큰(준비안 기록 경로)이 같은 값이면 모든 profile에서 기동을 거부한다(ADR-012 토큰 분리).
 * 두 토큰이 같으면 읽기 권한만 가진 호출자가 기록까지 할 수 있어 분리 결정이 설정 실수로 무력화된다. 오류 메시지에 토큰 값은 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class ServiceTokenSeparationConfiguration {

    static final String ERROR_CODE = "SERVICE_TOKEN_NOT_SEPARATED";

    public ServiceTokenSeparationConfiguration(ToolApiProperties toolApi, PreparationRecordProperties preparationRecord) {
        validate(toolApi == null ? null : toolApi.serviceToken(), preparationRecord == null ? null : preparationRecord.serviceToken());
    }

    /** 둘 다 설정돼 있고 값이 같으면 거부. 비어 있는 경우는 각 경로의 "비어 있으면 모두 거부"와 production 필수 설정 검사가 다룬다. */
    static void validate(String toolToken, String recordToken) {
        if (toolToken == null || toolToken.isBlank() || recordToken == null || recordToken.isBlank()) {
            return;
        }
        if (MessageDigest.isEqual(toolToken.getBytes(StandardCharsets.UTF_8), recordToken.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException(ERROR_CODE
                    + ": 읽기 토큰(TRUST_AGENT_TOOL_SERVICE_TOKEN)과 기록 토큰(TRUST_AGENT_PREPARATION_RECORD_TOKEN)이 같은 값입니다. "
                    + "두 토큰은 서로 다른 값이어야 합니다.");
        }
    }
}
