package com.trustagent.core.search;

import java.util.List;

/** 근거 문장(passage) 임베딩. 구현은 AI 서비스의 로컬 모델 호출이며, 실패는 SearchIndexException으로 알린다(기존 색인 유지). */
public interface PassageEmbedder {

    record Embeddings(String model, int dimension, List<float[]> vectors) {}

    Embeddings embed(List<String> passages);
}
