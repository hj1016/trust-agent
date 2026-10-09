package com.trustagent.core.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 색인 설정(ADR-013, TASK-016). 비밀번호는 환경변수로만 들어오며 저장소·로그에 남기지 않는다.
 * 색인 이름은 {indexPrefix}-{workspaceId}-{내용 해시 12자}, alias는 {indexPrefix}-{workspaceId}-current다.
 * analyzer는 standard 또는 nori(플러그인 필요)이며 초기 점검용 자료로 비교해 고른다(TASK-016 제안 1).
 */
@ConfigurationProperties("trust-agent.search")
public record SearchProperties(String baseUrl, String username, String password, String indexPrefix, String workspaceId,
                               String analyzer, Duration timeout) {

    public static final String ANALYZER_STANDARD = "standard";
    public static final String ANALYZER_NORI = "nori";

    public SearchProperties {
        if (indexPrefix == null || indexPrefix.isBlank()) indexPrefix = "trustagent-rule-evidence";
        if (workspaceId == null || workspaceId.isBlank()) workspaceId = "main";
        if (analyzer == null || analyzer.isBlank()) analyzer = ANALYZER_STANDARD;
        if (!ANALYZER_STANDARD.equals(analyzer) && !ANALYZER_NORI.equals(analyzer)) {
            throw new IllegalArgumentException("SEARCH_ANALYZER_INVALID: standard 또는 nori만 허용합니다: " + analyzer);
        }
        if (timeout == null) timeout = Duration.ofSeconds(10);
        if (!workspaceId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("SEARCH_WORKSPACE_ID_INVALID");
        }
    }

    public String alias() {
        return indexPrefix + "-" + workspaceId + "-current";
    }

    public String indexNamePrefix() {
        return indexPrefix + "-" + workspaceId + "-";
    }

    boolean credentialsConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
}
