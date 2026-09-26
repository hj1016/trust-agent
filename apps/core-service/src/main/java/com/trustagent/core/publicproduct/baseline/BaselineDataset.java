package com.trustagent.core.publicproduct.baseline;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

record BaselineDataset(
        Path datasetRoot,
        String fingerprint,
        JsonNode inputFilesJson,
        Map<RecordType, List<SourceRecord>> records) {

    List<SourceRecord> records(RecordType type) {
        return records.getOrDefault(type, List.of());
    }

    enum RecordType {
        PRODUCT,
        SNAPSHOT,
        COLLECTION_ATTEMPT,
        OBSERVATION,
        PRODUCT_TERMS_VERSION,
        VERSION_EVIDENCE,
        RATE_QUOTE,
        EXTRACTION_ATTEMPT,
        CHANGE_DETECTION_RESULT
    }

    record SourceRecord(
            String relativePath,
            JsonNode json,
            BaselineDtos.BaselineRecord typedRecord,
            String sourceRecordHash) {
    }
}
