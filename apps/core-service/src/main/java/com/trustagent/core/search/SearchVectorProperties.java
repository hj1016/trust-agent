package com.trustagent.core.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 벡터 색인 설정(하이브리드 검색). 켜면 재색인이 승인 근거 문장을 AI 서비스의 로컬 임베딩 API(e5-small, passage)로 벡터화해 같은 색인의
 * dense_vector 필드에 넣는다. Core만 색인에 쓴다. modelTag는 AI 서비스 manifest의 "모델 ID@revision 앞 12자"와 같아야 하며 다르면 재색인 실패다.
 */
@ConfigurationProperties("trust-agent.search.vector")
public record SearchVectorProperties(boolean enabled, Integer dimension, String modelTag, Integer batchSize) {

    public SearchVectorProperties {
        if (dimension == null) dimension = 384;
        if (batchSize == null || batchSize < 1 || batchSize > 64) batchSize = 32;
        if (enabled && (modelTag == null || modelTag.isBlank())) {
            throw new IllegalArgumentException("SEARCH_VECTOR_MODEL_TAG_REQUIRED: 벡터 색인을 켜면 trust-agent.search.vector.model-tag가 필요합니다.");
        }
    }

    SearchDocuments.VectorSpec spec() {
        return enabled ? new SearchDocuments.VectorSpec(modelTag, dimension) : null;
    }
}
