package com.trustagent.core.publicproduct.baseline;

import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.CHANGE_DETECTION_RESULT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.COLLECTION_ATTEMPT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.EXTRACTION_ATTEMPT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.OBSERVATION;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.PRODUCT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.PRODUCT_TERMS_VERSION;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.RATE_QUOTE;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.SNAPSHOT;
import static com.trustagent.core.publicproduct.baseline.BaselineDataset.RecordType.VERSION_EVIDENCE;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class BaselineDatasetLoader {

    private static final Pattern HASH = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final Pattern RUN_ID = Pattern.compile("^run:[a-f0-9]{32}$");
    private static final Map<BaselineDataset.RecordType, DirectoryRule> DIRECTORY_RULES = Map.of(
            SNAPSHOT, new DirectoryRule("public/kb/manifests", ".manifest.json"),
            COLLECTION_ATTEMPT, new DirectoryRule("derived/public-kb/collection-attempts", ".collection-attempt.json"),
            OBSERVATION, new DirectoryRule("public/kb/observations", ".observation.json"),
            PRODUCT_TERMS_VERSION, new DirectoryRule("public/kb/product-terms-versions", ".json"),
            VERSION_EVIDENCE, new DirectoryRule("public/kb/version-evidence", ".version-evidence.json"),
            RATE_QUOTE, new DirectoryRule("public/kb/rate-quotes", ".rate-quote.json"),
            EXTRACTION_ATTEMPT, new DirectoryRule("derived/public-kb/extraction-attempts", ".extraction-attempt.json"),
            CHANGE_DETECTION_RESULT, new DirectoryRule("derived/public-kb/change-detection-results", ".change-detection.json"));

    private final ObjectMapper objectMapper;
    private final CanonicalJsonHasher canonicalJsonHasher;

    BaselineDatasetLoader(ObjectMapper objectMapper, CanonicalJsonHasher canonicalJsonHasher) {
        this.objectMapper = objectMapper;
        this.canonicalJsonHasher = canonicalJsonHasher;
    }

    BaselineDataset load(Path inputRoot) {
        Path datasetRoot = resolveDatasetRoot(inputRoot);
        Path catalogPath = checkedFile(datasetRoot, "public/kb/catalog/product-catalog.json");
        Map<BaselineDataset.RecordType, List<Path>> pathsByType = new EnumMap<>(BaselineDataset.RecordType.class);
        Set<Path> allowedJson = new HashSet<>();
        allowedJson.add(catalogPath);
        for (Map.Entry<BaselineDataset.RecordType, DirectoryRule> entry : DIRECTORY_RULES.entrySet()) {
            Path directory = checkedDirectory(datasetRoot, entry.getValue().relativeDirectory());
            List<Path> paths = jsonFiles(directory);
            for (Path path : paths) {
                if (!path.getFileName().toString().endsWith(entry.getValue().suffix())) {
                    throw invalid("허용되지 않은 baseline JSON 파일입니다: " + displayPath(datasetRoot, path));
                }
                allowedJson.add(path);
            }
            if (paths.isEmpty()) {
                throw invalid("필수 baseline 디렉터리가 비어 있습니다: " + entry.getValue().relativeDirectory());
            }
            pathsByType.put(entry.getKey(), paths);
        }

        rejectUnexpectedInputFiles(datasetRoot, allowedJson);
        List<InputFile> inputFiles = allowedJson.stream()
                .map(path -> inputFile(datasetRoot, path))
                .sorted((left, right) -> left.path().compareTo(right.path()))
                .toList();
        ArrayNode inventoryJson = objectMapper.createArrayNode();
        inputFiles.forEach(file -> {
            ObjectNode item = inventoryJson.addObject();
            item.put("path", file.path());
            item.put("sha256", file.sha256());
        });
        String fingerprint = canonicalJsonHasher.canonicalize(inventoryJson).sha256();
        try {
            Map<BaselineDataset.RecordType, List<BaselineDataset.SourceRecord>> records =
                    new EnumMap<>(BaselineDataset.RecordType.class);
            JsonNode catalog = readJson(catalogPath);
            records.put(PRODUCT, loadProducts(datasetRoot, catalogPath, catalog));
            for (Map.Entry<BaselineDataset.RecordType, List<Path>> entry : pathsByType.entrySet()) {
                List<BaselineDataset.SourceRecord> typeRecords = new ArrayList<>();
                for (Path path : entry.getValue()) {
                    JsonNode json = readJson(path);
                    validateRecord(entry.getKey(), json, displayPath(datasetRoot, path));
                    typeRecords.add(sourceRecord(datasetRoot, path, json, entry.getKey()));
                }
                records.put(entry.getKey(), List.copyOf(typeRecords));
            }
            validateReferences(records);
            return new BaselineDataset(datasetRoot, fingerprint, inventoryJson, Map.copyOf(records));
        } catch (BaselineImportException exception) {
            throw exception.withAuditInput(fingerprint, inventoryJson);
        }
    }

    private Path resolveDatasetRoot(Path inputRoot) {
        try {
            Path root = inputRoot.toRealPath();
            Path repositoryDatasets = root.resolve("datasets");
            Path candidate = Files.isDirectory(repositoryDatasets.resolve("public/kb"))
                    ? repositoryDatasets
                    : root;
            if (!Files.isDirectory(candidate.resolve("public/kb"))
                    || !Files.isDirectory(candidate.resolve("derived/public-kb"))) {
                throw invalid("입력 경로에는 datasets/public/kb와 datasets/derived/public-kb가 필요합니다.");
            }
            return candidate.toRealPath();
        } catch (IOException exception) {
            throw new BaselineImportException("INVALID_INPUT_ROOT", "baseline 입력 경로를 읽을 수 없습니다.", exception);
        }
    }

    private Path checkedFile(Path datasetRoot, String relativePath) {
        Path path = datasetRoot.resolve(relativePath).normalize();
        if (!path.startsWith(datasetRoot) || !Files.isRegularFile(path)) {
            throw invalid("필수 baseline 파일이 없습니다: " + relativePath);
        }
        rejectSymlink(datasetRoot, path);
        return path;
    }

    private Path checkedDirectory(Path datasetRoot, String relativePath) {
        Path path = datasetRoot.resolve(relativePath).normalize();
        if (!path.startsWith(datasetRoot) || !Files.isDirectory(path)) {
            throw invalid("필수 baseline 디렉터리가 없습니다: " + relativePath);
        }
        rejectSymlink(datasetRoot, path);
        return path;
    }

    private void rejectSymlink(Path datasetRoot, Path path) {
        try {
            if (!path.toRealPath().startsWith(datasetRoot)) {
                throw invalid("baseline 경로가 입력 루트 밖을 가리킵니다.");
            }
        } catch (IOException exception) {
            throw new BaselineImportException("INVALID_INPUT_ROOT", "baseline 경로를 확인할 수 없습니다.", exception);
        }
    }

    private List<Path> jsonFiles(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .peek(path -> rejectSymlink(directory, path))
                    .toList();
        } catch (IOException exception) {
            throw new BaselineImportException("INPUT_READ_FAILED", "baseline 파일 목록을 읽을 수 없습니다.", exception);
        }
    }

    private void rejectUnexpectedInputFiles(Path datasetRoot, Set<Path> allowedJson) {
        for (String root : List.of("public/kb", "derived/public-kb")) {
            try (Stream<Path> paths = Files.walk(datasetRoot.resolve(root))) {
                List<Path> unexpected = paths.filter(Files::isRegularFile)
                        .filter(path -> !allowedJson.contains(path))
                        .toList();
                if (!unexpected.isEmpty()) {
                    throw invalid("허용되지 않은 baseline 입력 파일입니다: "
                            + displayPath(datasetRoot, unexpected.getFirst()));
                }
            } catch (IOException exception) {
                throw new BaselineImportException("INPUT_READ_FAILED", "baseline 경계를 검사할 수 없습니다.", exception);
            }
        }
    }

    private List<BaselineDataset.SourceRecord> loadProducts(Path datasetRoot, Path path, JsonNode catalog) {
        requireObject(catalog, "product catalog");
        requireExactFields(catalog, Set.of("dataset_class", "synthetic", "baseline_status", "snapshot_date", "products"), "product catalog");
        requireEquals(catalog, "dataset_class", "PUBLIC_KB", "product catalog");
        requireFalse(catalog, "synthetic", "product catalog");
        requireEquals(catalog, "baseline_status", "SNAPSHOT_VERIFIED", "product catalog");
        parseDate(requiredText(catalog, "snapshot_date", "product catalog"), "product catalog.snapshot_date");
        JsonNode products = requiredArray(catalog, "products", "product catalog");
        List<BaselineDataset.SourceRecord> result = new ArrayList<>();
        for (JsonNode product : products) {
            requireExactFields(product, Set.of("product_key", "display_name", "source_marker", "source_url"), "product");
            ObjectNode source = objectMapper.createObjectNode();
            source.put("dataset_class", "PUBLIC_KB");
            source.put("synthetic", false);
            source.setAll((ObjectNode) product.deepCopy());
            validateProduct(source, "product catalog");
            result.add(new BaselineDataset.SourceRecord(
                    displayPath(datasetRoot, path) + "#products/" + requiredText(product, "product_key", "product"),
                    source,
                    typedRecord(PRODUCT, source, "product catalog"),
                    canonicalJsonHasher.canonicalize(source).sha256()));
        }
        if (result.isEmpty()) {
            throw invalid("product catalog에 상품이 없습니다.");
        }
        return List.copyOf(result);
    }

    private BaselineDataset.SourceRecord sourceRecord(
            Path datasetRoot,
            Path path,
            JsonNode json,
            BaselineDataset.RecordType type) {
        return new BaselineDataset.SourceRecord(
                displayPath(datasetRoot, path),
                json,
                typedRecord(type, json, displayPath(datasetRoot, path)),
                canonicalJsonHasher.canonicalize(json).sha256());
    }

    private BaselineDtos.BaselineRecord typedRecord(
            BaselineDataset.RecordType type,
            JsonNode json,
            String location) {
        Class<? extends BaselineDtos.BaselineRecord> target = switch (type) {
            case PRODUCT -> BaselineDtos.Product.class;
            case SNAPSHOT -> BaselineDtos.Snapshot.class;
            case COLLECTION_ATTEMPT -> BaselineDtos.CollectionAttempt.class;
            case OBSERVATION -> BaselineDtos.Observation.class;
            case PRODUCT_TERMS_VERSION -> BaselineDtos.ProductTermsVersion.class;
            case VERSION_EVIDENCE -> BaselineDtos.VersionEvidence.class;
            case RATE_QUOTE -> BaselineDtos.RateQuote.class;
            case EXTRACTION_ATTEMPT -> BaselineDtos.ExtractionAttempt.class;
            case CHANGE_DETECTION_RESULT -> BaselineDtos.ChangeDetectionResult.class;
        };
        try {
            return objectMapper.treeToValue(json, target);
        } catch (JacksonException exception) {
            throw new BaselineImportException(
                    "INVALID_BASELINE",
                    location + "을 타입이 정해진 baseline record로 변환할 수 없습니다.",
                    exception);
        }
    }

    private InputFile inputFile(Path datasetRoot, Path path) {
        try {
            return new InputFile(displayPath(datasetRoot, path), sha256(Files.readAllBytes(path)));
        } catch (IOException exception) {
            throw new BaselineImportException("INPUT_READ_FAILED", "baseline 파일을 읽을 수 없습니다.", exception);
        }
    }

    private JsonNode readJson(Path path) {
        try {
            JsonNode json = objectMapper.readTree(Files.readAllBytes(path));
            requireObject(json, path.getFileName().toString());
            return json;
        } catch (JacksonException exception) {
            throw new BaselineImportException("INVALID_JSON", "baseline JSON 문법이 올바르지 않습니다.", exception);
        } catch (IOException exception) {
            throw new BaselineImportException("INPUT_READ_FAILED", "baseline JSON을 읽을 수 없습니다.", exception);
        }
    }

    private String displayPath(Path datasetRoot, Path path) {
        return "datasets/" + datasetRoot.relativize(path).toString().replace('\\', '/');
    }

    private void validateRecord(BaselineDataset.RecordType type, JsonNode json, String location) {
        switch (type) {
            case SNAPSHOT -> validateSnapshot(json, location);
            case COLLECTION_ATTEMPT -> validateCollectionAttempt(json, location);
            case OBSERVATION -> validateObservation(json, location);
            case PRODUCT_TERMS_VERSION -> validateTermsVersion(json, location);
            case VERSION_EVIDENCE -> validateVersionEvidence(json, location);
            case RATE_QUOTE -> validateRateQuote(json, location);
            case EXTRACTION_ATTEMPT -> validateExtractionAttempt(json, location);
            case CHANGE_DETECTION_RESULT -> validateChangeDetection(json, location);
            case PRODUCT -> throw new IllegalStateException("product는 catalog에서 검증합니다.");
        }
    }

    private void validateProduct(JsonNode json, String location) {
        requireEquals(json, "dataset_class", "PUBLIC_KB", location);
        requireFalse(json, "synthetic", location);
        requirePattern(requiredText(json, "product_key", location), "^[a-z0-9-]+$", location);
        requiredText(json, "display_name", location);
        requiredText(json, "source_marker", location);
        requireKbUrl(requiredText(json, "source_url", location), location);
    }

    private void validateSnapshot(JsonNode json, String location) {
        requireExactFields(json, Set.of("dataset_class", "product_key", "source_url", "snapshot_storage", "snapshot_object_key", "content_type", "byte_size", "snapshot_hash", "synthetic"), location);
        publicRecord(json, location);
        requirePattern(requiredText(json, "product_key", location), "^[a-z0-9-]+$", location);
        requirePattern(requiredText(json, "snapshot_hash", location), HASH.pattern(), location);
        requireKbUrl(requiredText(json, "source_url", location), location);
        requireEnum(json, "snapshot_storage", Set.of("LOCAL_PRIVATE", "PRIVATE_OBJECT_STORAGE"), location);
        requireEquals(json, "content_type", "text/html", location);
        requirePositiveLong(json, "byte_size", location);
        requirePattern(requiredText(json, "snapshot_object_key", location), "^public-kb/sha256/[a-f0-9]{64}\\.html$", location);
    }

    private void validateCollectionAttempt(JsonNode json, String location) {
        requireExactFields(json, Set.of("dataset_class", "collection_attempt_id", "collection_run_id", "product_key", "attempt_sequence", "attempted_at", "status", "observation_id", "error_code", "error_message"), location);
        derivedRecord(json, location);
        requirePattern(requiredText(json, "collection_attempt_id", location), "^collect:[a-z0-9-]+:[a-f0-9]{32}$", location);
        requirePattern(requiredText(json, "collection_run_id", location), RUN_ID.pattern(), location);
        requirePositiveLong(json, "attempt_sequence", location);
        parseInstant(requiredText(json, "attempted_at", location), location);
        String status = requireEnum(json, "status", Set.of("SUCCEEDED", "FAILED"), location);
        validateAttemptOutcome(json, status, List.of("observation_id"), location);
    }

    private void validateObservation(JsonNode json, String location) {
        requireExactFields(json, Set.of("dataset_class", "synthetic", "observation_id", "collection_attempt_id", "collection_run_id", "product_key", "source_url", "final_url", "observed_at", "acquisition_method", "acquisition_note", "snapshot_manifest_path", "snapshot_hash"), location, Set.of("acquisition_note"));
        publicRecord(json, location);
        requirePattern(requiredText(json, "observation_id", location), "^obs:[a-z0-9-]+:[a-f0-9]{32}$", location);
        requirePattern(requiredText(json, "collection_attempt_id", location), "^collect:[a-z0-9-]+:[a-f0-9]{32}$", location);
        requirePattern(requiredText(json, "collection_run_id", location), RUN_ID.pattern(), location);
        requireKbUrl(requiredText(json, "source_url", location), location);
        String finalUrl = nullableText(json, "final_url", location);
        if (finalUrl != null) requireKbUrl(finalUrl, location);
        parseInstant(requiredText(json, "observed_at", location), location);
        String method = requireEnum(json, "acquisition_method", Set.of("HTTP_DOWNLOAD", "MANUAL_DOWNLOAD"), location);
        String note = nullableText(json, "acquisition_note", location);
        if ((method.equals("MANUAL_DOWNLOAD")) != (note != null && !note.isBlank())) {
            throw invalid(location + "의 acquisition_method와 acquisition_note가 일치하지 않습니다.");
        }
        requirePattern(requiredText(json, "snapshot_hash", location), HASH.pattern(), location);
        requirePattern(requiredText(json, "snapshot_manifest_path", location), "^datasets/public/kb/manifests/[0-9]{4}-[0-9]{2}-[0-9]{2}/[a-z0-9-]+.*\\.manifest\\.json$", location);
    }

    private void validateTermsVersion(JsonNode json, String location) {
        requireExactFields(json, Set.of("dataset_class", "synthetic", "product_terms_version_id", "product_key", "terms_hash", "effective_from", "effective_to", "facts"), location);
        publicRecord(json, location);
        String versionId = requiredText(json, "product_terms_version_id", location);
        requirePattern(versionId, "^ptv:[a-z0-9-]+:sha256:[a-f0-9]{64}$", location);
        requirePattern(requiredText(json, "terms_hash", location), HASH.pattern(), location);
        LocalDate effectiveFrom = nullableDate(json, "effective_from", location);
        LocalDate effectiveTo = nullableDate(json, "effective_to", location);
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw invalid(location + "의 effective_to가 effective_from보다 빠릅니다.");
        }
        if (!versionId.equals("ptv:" + requiredText(json, "product_key", location) + ":" + requiredText(json, "terms_hash", location))) {
            throw invalid(location + "의 version ID와 terms hash가 일치하지 않습니다.");
        }
        JsonNode facts = requiredArray(json, "facts", location);
        Set<String> factIds = new HashSet<>();
        for (JsonNode fact : facts) {
            requireExactFields(fact, Set.of("fact_id", "fact_key", "subject_type", "value_type", "value", "unit"), location + ".facts");
            String factId = requiredText(fact, "fact_id", location);
            requirePattern(factId, "^fact:[a-z0-9-]+:[a-z0-9_]+$", location);
            if (!factIds.add(factId)) throw invalid(location + "에 중복 fact_id가 있습니다.");
            requireEnum(fact, "fact_key", Set.of("product_name", "applicant_eligibility_text", "max_limit_individual_krw", "max_limit_corporate_krw", "repayment_method_text", "sale_status"), location);
            requireEnum(fact, "subject_type", Set.of("PRODUCT", "SOLE_PROPRIETOR", "CORPORATION"), location);
            String valueType = requireEnum(fact, "value_type", Set.of("TEXT", "INTEGER", "STATUS"), location);
            String unit = requireEnum(fact, "unit", Set.of("TEXT", "KRW", "STATUS"), location);
            JsonNode value = fact.get("value");
            if (value == null || value.isNull()) throw invalid(location + "의 fact value가 없습니다.");
            if (valueType.equals("TEXT") && (!value.isString() || !unit.equals("TEXT"))) throw invalid(location + "의 TEXT fact가 올바르지 않습니다.");
            if (valueType.equals("INTEGER") && (!value.isIntegralNumber() || !unit.equals("KRW"))) throw invalid(location + "의 INTEGER fact가 올바르지 않습니다.");
            if (valueType.equals("STATUS") && (!value.isString() || !Set.of("SELLING", "DISCONTINUED").contains(value.stringValue()) || !unit.equals("STATUS"))) throw invalid(location + "의 STATUS fact가 올바르지 않습니다.");
        }
    }

    private void validateVersionEvidence(JsonNode json, String location) {
        requireExactFields(json, Set.of("dataset_class", "synthetic", "version_evidence_id", "observation_id", "product_terms_version_id", "product_key", "snapshot_hash", "parser_version", "fact_evidence"), location);
        publicRecord(json, location);
        requirePattern(requiredText(json, "version_evidence_id", location), "^evidence:[a-z0-9-]+:[a-f0-9]{32}$", location);
        requirePattern(requiredText(json, "observation_id", location), "^obs:[a-z0-9-]+:[a-f0-9]{32}$", location);
        requirePattern(requiredText(json, "product_terms_version_id", location), "^ptv:[a-z0-9-]+:sha256:[a-f0-9]{64}$", location);
        requirePattern(requiredText(json, "snapshot_hash", location), HASH.pattern(), location);
        requirePattern(requiredText(json, "parser_version", location), "^public-kb-html-v[0-9]+$", location);
        Set<String> factIds = new HashSet<>();
        for (JsonNode evidence : requiredArray(json, "fact_evidence", location)) {
            requireExactFields(evidence, Set.of("fact_id", "locators"), location + ".fact_evidence");
            String factId = requiredText(evidence, "fact_id", location);
            if (!factIds.add(factId)) throw invalid(location + "에 중복 fact evidence가 있습니다.");
            JsonNode locators = requiredArray(evidence, "locators", location);
            if (locators.isEmpty()) throw invalid(location + "의 locator가 비어 있습니다.");
            for (JsonNode locator : locators) validateLocator(locator, location);
        }
    }

    private void validateRateQuote(JsonNode json, String location) {
        requireExactFields(json, Set.of("dataset_class", "synthetic", "rate_quote_id", "observation_id", "product_key", "advertised_rate_text", "advertised_rate_reference_date", "text_locator", "reference_date_locator"), location);
        publicRecord(json, location);
        requirePattern(requiredText(json, "rate_quote_id", location), "^quote:[a-z0-9-]+:[a-f0-9]{32}$", location);
        requirePattern(requiredText(json, "observation_id", location), "^obs:[a-z0-9-]+:[a-f0-9]{32}$", location);
        requiredText(json, "advertised_rate_text", location);
        nullableDate(json, "advertised_rate_reference_date", location);
        validateLocator(requiredObject(json, "text_locator", location), location);
        JsonNode dateLocator = json.get("reference_date_locator");
        if (dateLocator != null && !dateLocator.isNull()) validateLocator(dateLocator, location);
    }

    private void validateExtractionAttempt(JsonNode json, String location) {
        requireExactFields(json, Set.of("dataset_class", "extraction_attempt_id", "extraction_run_id", "observation_id", "product_key", "attempt_sequence", "attempted_at", "attempted_at_source", "parser_version", "status", "product_terms_version_id", "version_evidence_id", "rate_quote_id", "error_code", "error_message"), location);
        derivedRecord(json, location);
        requirePattern(requiredText(json, "extraction_attempt_id", location), "^extract:[a-z0-9-]+:[a-f0-9]{32}$", location);
        requirePattern(requiredText(json, "extraction_run_id", location), RUN_ID.pattern(), location);
        requirePattern(requiredText(json, "observation_id", location), "^obs:[a-z0-9-]+:[a-f0-9]{32}$", location);
        requirePositiveLong(json, "attempt_sequence", location);
        parseInstant(requiredText(json, "attempted_at", location), location);
        requireEnum(json, "attempted_at_source", Set.of("MEASURED", "BACKFILLED_FROM_OBSERVATION"), location);
        requirePattern(requiredText(json, "parser_version", location), "^public-kb-html-v[0-9]+$", location);
        String status = requireEnum(json, "status", Set.of("SUCCEEDED", "FAILED"), location);
        validateAttemptOutcome(json, status, List.of("product_terms_version_id", "version_evidence_id", "rate_quote_id"), location);
    }

    private void validateChangeDetection(JsonNode json, String location) {
        requireExactFields(json, Set.of("dataset_class", "change_detection_result_id", "product_key", "observation_id", "previous_observation_id", "evaluated_at", "policy_version", "classifications", "supersedes_result_id"), location);
        derivedRecord(json, location);
        requirePattern(requiredText(json, "change_detection_result_id", location), "^change:[a-z0-9-]+:[a-f0-9]{64}$", location);
        requirePattern(requiredText(json, "observation_id", location), "^obs:[a-z0-9-]+:[a-f0-9]{32}$", location);
        nullableText(json, "previous_observation_id", location);
        nullableText(json, "supersedes_result_id", location);
        parseInstant(requiredText(json, "evaluated_at", location), location);
        requireEquals(json, "policy_version", "public-kb-change-v1", location);
        Set<String> values = new HashSet<>();
        for (JsonNode value : requiredArray(json, "classifications", location)) {
            if (!value.isString() || !Set.of("BASELINE_ESTABLISHED", "PRODUCT_TERMS_CHANGED", "RATE_QUOTE_CHANGED", "QUOTE_REFRESHED", "NO_SEMANTIC_CHANGE").contains(value.stringValue())) {
                throw invalid(location + "의 classification이 올바르지 않습니다.");
            }
            if (!values.add(value.stringValue())) throw invalid(location + "에 중복 classification이 있습니다.");
        }
        if (values.isEmpty()) throw invalid(location + "의 classifications가 비어 있습니다.");
    }

    private void validateLocator(JsonNode locator, String location) {
        requireExactFields(locator, Set.of("strategy", "selector", "label", "evidence_text", "evidence_hash", "snapshot_hash", "source_url"), location + ".locator");
        requireEnum(locator, "strategy", Set.of("CSS_ATTRIBUTE", "LABELED_ADJACENT_TEXT", "CSS_TEXT_MATCH"), location);
        requiredText(locator, "selector", location);
        requiredText(locator, "label", location);
        requiredText(locator, "evidence_text", location);
        requirePattern(requiredText(locator, "evidence_hash", location), HASH.pattern(), location);
        requirePattern(requiredText(locator, "snapshot_hash", location), HASH.pattern(), location);
        requireKbUrl(requiredText(locator, "source_url", location), location);
    }

    private void validateAttemptOutcome(JsonNode json, String status, List<String> successFields, String location) {
        boolean allSuccessFieldsPresent = successFields.stream().allMatch(json::hasNonNull);
        boolean anySuccessFieldPresent = successFields.stream().anyMatch(json::hasNonNull);
        boolean allErrorFieldsPresent = json.hasNonNull("error_code") && json.hasNonNull("error_message");
        boolean anyErrorFieldPresent = json.hasNonNull("error_code") || json.hasNonNull("error_message");
        if (status.equals("SUCCEEDED") && (!allSuccessFieldsPresent || anyErrorFieldPresent)) throw invalid(location + "의 성공 attempt 결과가 올바르지 않습니다.");
        if (status.equals("FAILED") && (anySuccessFieldPresent || !allErrorFieldsPresent)) throw invalid(location + "의 실패 attempt 결과가 올바르지 않습니다.");
        if (json.hasNonNull("error_code")) requirePattern(requiredText(json, "error_code", location), "^[A-Z][A-Z0-9_]*$", location);
    }

    private void validateReferences(Map<BaselineDataset.RecordType, List<BaselineDataset.SourceRecord>> records) {
        Map<String, JsonNode> products = uniqueBy(records.get(PRODUCT), node -> text(node, "product_key"), "product_key");
        Map<String, JsonNode> snapshots = uniqueBy(records.get(SNAPSHOT), node -> text(node, "snapshot_hash"), "snapshot_hash");
        Map<String, JsonNode> collections = uniqueBy(records.get(COLLECTION_ATTEMPT), node -> text(node, "collection_attempt_id"), "collection_attempt_id");
        Map<String, JsonNode> observations = uniqueBy(records.get(OBSERVATION), node -> text(node, "observation_id"), "observation_id");
        Map<String, JsonNode> versions = uniqueBy(records.get(PRODUCT_TERMS_VERSION), node -> text(node, "product_terms_version_id"), "product_terms_version_id");
        Map<String, JsonNode> evidence = uniqueBy(records.get(VERSION_EVIDENCE), node -> text(node, "version_evidence_id"), "version_evidence_id");
        Map<String, JsonNode> quotes = uniqueBy(records.get(RATE_QUOTE), node -> text(node, "rate_quote_id"), "rate_quote_id");
        uniqueBy(records.get(EXTRACTION_ATTEMPT), node -> text(node, "extraction_attempt_id"), "extraction_attempt_id");
        Map<String, JsonNode> changes = uniqueBy(records.get(CHANGE_DETECTION_RESULT), node -> text(node, "change_detection_result_id"), "change_detection_result_id");

        Map<String, String> snapshotPaths = records.get(SNAPSHOT).stream().collect(Collectors.toMap(
                record -> text(record.json(), "snapshot_hash"), BaselineDataset.SourceRecord::relativePath));

        records.values().stream().flatMap(List::stream).map(BaselineDataset.SourceRecord::json)
                .filter(node -> node.has("product_key"))
                .forEach(node -> requireReference(products, text(node, "product_key"), "product"));
        snapshots.values().forEach(node -> {
            JsonNode product = products.get(text(node, "product_key"));
            requireSame(node, product, "source_url", "source_url");
        });
        observations.values().forEach(node -> {
            requireReference(collections, text(node, "collection_attempt_id"), "collection attempt");
            JsonNode snapshot = requireReference(snapshots, text(node, "snapshot_hash"), "snapshot");
            JsonNode product = products.get(text(node, "product_key"));
            JsonNode collection = collections.get(text(node, "collection_attempt_id"));
            requireSame(node, collection, "observation_id", "observation_id");
            requireSame(node, collection, "collection_run_id", "collection_run_id");
            requireSame(node, collection, "product_key", "product_key");
            requireSame(node, snapshot, "product_key", "product_key");
            requireSame(node, product, "source_url", "source_url");
            if (!text(node, "snapshot_manifest_path").equals(snapshotPaths.get(text(node, "snapshot_hash")))) {
                throw invalid("Observation의 snapshot_manifest_path가 snapshot 입력 파일과 일치하지 않습니다.");
            }
        });
        collections.values().stream().filter(node -> node.hasNonNull("observation_id"))
                .forEach(node -> requireReference(observations, text(node, "observation_id"), "observation"));
        versions.values().forEach(node -> {
            String product = text(node, "product_key");
            for (JsonNode fact : node.get("facts")) {
                if (!text(fact, "fact_id").startsWith("fact:" + product + ":")) throw invalid("fact_id의 product_key가 일치하지 않습니다.");
            }
        });
        evidence.values().forEach(node -> {
            JsonNode observation = requireReference(observations, text(node, "observation_id"), "observation");
            JsonNode version = requireReference(versions, text(node, "product_terms_version_id"), "terms version");
            requireReference(snapshots, text(node, "snapshot_hash"), "snapshot");
            requireSame(node, observation, "product_key", "product_key");
            requireSame(node, observation, "snapshot_hash", "snapshot_hash");
            requireSame(node, version, "product_key", "product_key");
            Set<String> factIds = stream(version.get("facts")).map(fact -> text(fact, "fact_id")).collect(Collectors.toSet());
            Set<String> evidenceFactIds = new HashSet<>();
            for (JsonNode item : node.get("fact_evidence")) {
                if (!factIds.contains(text(item, "fact_id"))) throw invalid("evidence가 존재하지 않는 fact를 참조합니다.");
                evidenceFactIds.add(text(item, "fact_id"));
                for (JsonNode locator : item.get("locators")) {
                    requireSame(locator, node, "snapshot_hash", "snapshot_hash");
                    requireSame(locator, observation, "source_url", "source_url");
                }
            }
            if (!factIds.equals(evidenceFactIds)) throw invalid("VersionEvidence가 terms fact 전체를 설명하지 않습니다.");
        });
        quotes.values().forEach(node -> {
            JsonNode observation = requireReference(observations, text(node, "observation_id"), "observation");
            requireSame(node, observation, "product_key", "product_key");
            requireSame(node.get("text_locator"), observation, "snapshot_hash", "snapshot_hash");
            requireSame(node.get("text_locator"), observation, "source_url", "source_url");
            if (node.hasNonNull("reference_date_locator")) {
                requireSame(node.get("reference_date_locator"), observation, "snapshot_hash", "snapshot_hash");
                requireSame(node.get("reference_date_locator"), observation, "source_url", "source_url");
            }
        });
        records.get(EXTRACTION_ATTEMPT).forEach(record -> {
            JsonNode node = record.json();
            JsonNode observation = requireReference(observations, text(node, "observation_id"), "observation");
            requireSame(node, observation, "product_key", "product_key");
            if (parseInstant(text(node, "attempted_at"), "extraction attempt").isBefore(parseInstant(text(observation, "observed_at"), "observation"))) {
                throw invalid("ExtractionAttempt attempted_at이 Observation observed_at보다 빠릅니다.");
            }
            if (text(node, "status").equals("SUCCEEDED")) {
                JsonNode version = requireReference(versions, text(node, "product_terms_version_id"), "terms version");
                JsonNode evidenceNode = requireReference(evidence, text(node, "version_evidence_id"), "version evidence");
                JsonNode quote = requireReference(quotes, text(node, "rate_quote_id"), "rate quote");
                requireSame(node, version, "product_key", "product_key");
                requireSame(node, evidenceNode, "observation_id", "observation_id");
                requireSame(node, evidenceNode, "product_terms_version_id", "product_terms_version_id");
                requireSame(node, quote, "observation_id", "observation_id");
            }
        });
        changes.values().forEach(node -> {
            JsonNode observation = requireReference(observations, text(node, "observation_id"), "observation");
            requireSame(node, observation, "product_key", "product_key");
            if (node.hasNonNull("previous_observation_id")) {
                JsonNode previous = requireReference(observations, text(node, "previous_observation_id"), "previous observation");
                requireSame(node, previous, "product_key", "product_key");
            }
            if (node.hasNonNull("supersedes_result_id")) requireReference(changes, text(node, "supersedes_result_id"), "superseded result");
        });
    }

    private Map<String, JsonNode> uniqueBy(List<BaselineDataset.SourceRecord> records, Function<JsonNode, String> id, String name) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (BaselineDataset.SourceRecord record : records) {
            String value = id.apply(record.json());
            if (result.putIfAbsent(value, record.json()) != null) throw invalid("중복 " + name + "가 있습니다: " + value);
        }
        return result;
    }

    private JsonNode requireReference(Map<String, JsonNode> records, String id, String name) {
        JsonNode result = records.get(id);
        if (result == null) throw invalid("존재하지 않는 " + name + " 참조입니다: " + id);
        return result;
    }

    private void requireSame(JsonNode left, JsonNode right, String leftField, String rightField) {
        if (!text(left, leftField).equals(text(right, rightField))) throw invalid(leftField + " 참조 값이 일치하지 않습니다.");
    }

    private void publicRecord(JsonNode json, String location) {
        requireEquals(json, "dataset_class", "PUBLIC_KB", location);
        requireFalse(json, "synthetic", location);
        requiredText(json, "product_key", location);
    }

    private void derivedRecord(JsonNode json, String location) {
        requireEquals(json, "dataset_class", "DERIVED", location);
        requiredText(json, "product_key", location);
    }

    private void requireExactFields(JsonNode json, Set<String> required, String location) {
        requireExactFields(json, required, location, Set.of());
    }

    private void requireExactFields(JsonNode json, Set<String> fields, String location, Set<String> optional) {
        requireObject(json, location);
        Set<String> actual = new LinkedHashSet<>();
        json.propertyStream().forEach(entry -> actual.add(entry.getKey()));
        Set<String> required = new LinkedHashSet<>(fields);
        required.removeAll(optional);
        if (!actual.containsAll(required) || !fields.containsAll(actual)) throw invalid(location + "의 field 구성이 contract와 다릅니다.");
    }

    private void requireObject(JsonNode node, String location) {
        if (node == null || !node.isObject()) throw invalid(location + "은 JSON object여야 합니다.");
    }

    private JsonNode requiredObject(JsonNode node, String field, String location) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) throw invalid(location + "." + field + "는 object여야 합니다.");
        return value;
    }

    private JsonNode requiredArray(JsonNode node, String field, String location) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw invalid(location + "." + field + "는 array여야 합니다.");
        return value;
    }

    private String requiredText(JsonNode node, String field, String location) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) throw invalid(location + "." + field + "는 비어 있지 않은 문자열이어야 합니다.");
        return value.stringValue();
    }

    private String nullableText(JsonNode node, String field, String location) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw invalid(location + "." + field + "는 문자열 또는 null이어야 합니다.");
        return value.stringValue();
    }

    private String text(JsonNode node, String field) {
        return node.get(field).stringValue();
    }

    private void requireFalse(JsonNode node, String field, String location) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean() || value.booleanValue()) throw invalid(location + "." + field + "는 false여야 합니다.");
    }

    private void requireEquals(JsonNode node, String field, String expected, String location) {
        if (!expected.equals(requiredText(node, field, location))) throw invalid(location + "." + field + " 값이 올바르지 않습니다.");
    }

    private String requireEnum(JsonNode node, String field, Set<String> values, String location) {
        String value = requiredText(node, field, location);
        if (!values.contains(value)) throw invalid(location + "." + field + " enum이 올바르지 않습니다.");
        return value;
    }

    private void requirePattern(String value, String pattern, String location) {
        if (!Pattern.matches(pattern, value)) throw invalid(location + "의 ID 또는 hash 형식이 올바르지 않습니다.");
    }

    private void requirePositiveLong(JsonNode node, String field, String location) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || value.longValue() < 1) throw invalid(location + "." + field + "는 양의 정수여야 합니다.");
    }

    private Instant parseInstant(String value, String location) {
        if (!value.endsWith("Z")) throw invalid(location + "의 date-time은 UTC Z 형식이어야 합니다.");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BaselineImportException("INVALID_BASELINE", location + "의 date-time이 올바르지 않습니다.", exception);
        }
    }

    private LocalDate nullableDate(JsonNode node, String field, String location) {
        String value = nullableText(node, field, location);
        return value == null ? null : parseDate(value, location + "." + field);
    }

    private LocalDate parseDate(String value, String location) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BaselineImportException("INVALID_BASELINE", location + "의 date가 올바르지 않습니다.", exception);
        }
    }

    private void requireKbUrl(String value, String location) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null
                    || !(host.equals("kbstar.com") || host.endsWith(".kbstar.com"))
                    || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw invalid(location + "의 source URL이 KB HTTPS allowlist를 벗어납니다.");
            }
        } catch (IllegalArgumentException exception) {
            throw new BaselineImportException("INVALID_BASELINE", location + "의 URL이 올바르지 않습니다.", exception);
        }
    }

    private Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values.stream();
    }

    private String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private BaselineImportException invalid(String message) {
        return new BaselineImportException("INVALID_BASELINE", message);
    }

    private record DirectoryRule(String relativeDirectory, String suffix) {
    }

    private record InputFile(String path, String sha256) {
    }
}
