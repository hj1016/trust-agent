package com.trustagent.core.internalpolicy.bootstrap;

import static com.trustagent.core.bootstrap.AppendOnlyBootstrapChecks.requireAllowedIdentifier;
import static com.trustagent.core.bootstrap.AppendOnlyBootstrapChecks.verifyExactCounts;

import com.trustagent.core.internalpolicy.BusinessTimePolicy;
import com.trustagent.core.json.CanonicalJsonHasher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class SyntheticInternalImporter {
    private static final Pattern RUN_ID = Pattern.compile("^synthetic-import:[a-f0-9]{32}$");
    private static final Set<String> BUSINESS_TABLES = Set.of(
            "internal_notice_version", "internal_notice_reference", "internal_notice_receipt",
            "policy_extraction_attempt", "internal_policy_rule_version", "internal_policy_rule_evidence");
    private static final Map<String, String> SOURCE_ID_COLUMNS = Map.of(
            "internal_notice_version", "notice_id",
            "internal_notice_receipt", "receipt_id",
            "policy_extraction_attempt", "extraction_attempt_id",
            "synthetic_internal_import_run", "import_run_id");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CanonicalJsonHasher hasher;
    private final BusinessTimePolicy businessTime;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public SyntheticInternalImporter(
            JdbcClient jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            String businessTimezone) {
        this(jdbc, mapper, manager, businessTimezone, Clock.systemUTC());
    }

    SyntheticInternalImporter(
            JdbcClient jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            String businessTimezone,
            Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.hasher = new CanonicalJsonHasher(mapper);
        this.businessTime = new BusinessTimePolicy(businessTimezone);
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public SyntheticInternalImportResult importBaseline(Path repositoryRoot, String requestedRunId) {
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? "synthetic-import:" + UUID.randomUUID().toString().replace("-", "")
                : requestedRunId;
        if (!RUN_ID.matcher(runId).matches()) {
            throw failure("INVALID_RUN_ID", "synthetic import run ID 형식이 올바르지 않습니다.");
        }
        Dataset dataset = load(repositoryRoot);
        try {
            return transaction.execute(status -> importDataset(dataset, runId));
        } catch (SyntheticInternalImportException exception) {
            if (!exception.code().equals("RUN_ID_CONFLICT")) {
                recordFailure(runId, dataset.fingerprint(), exception.code());
            }
            throw exception;
        } catch (RuntimeException exception) {
            recordFailure(runId, dataset.fingerprint(), "SYNTHETIC_IMPORT_FAILED");
            throw new SyntheticInternalImportException(
                    "SYNTHETIC_IMPORT_FAILED",
                    "synthetic baseline 적재 중 DB 검증에 실패했습니다.",
                    exception);
        }
    }

    private void recordFailure(String runId, String fingerprint, String errorCode) {
        try {
            transaction.executeWithoutResult(status -> {
                Instant now = clock.instant();
                jdbc.sql("""
                                insert into synthetic_internal_import_run values (
                                  :id,:fingerprint,:started,:completed,'FAILED',null,:error)
                                """)
                        .param("id", runId)
                        .param("fingerprint", fingerprint)
                        .param("started", now.atOffset(ZoneOffset.UTC))
                        .param("completed", now.atOffset(ZoneOffset.UTC))
                        .param("error", errorCode)
                        .update();
            });
        } catch (RuntimeException auditFailure) {
            throw new SyntheticInternalImportException(
                    "FAILURE_AUDIT_WRITE_FAILED",
                    "synthetic import 실패 감사 기록을 저장할 수 없습니다 (원래 실패: "
                            + errorCode + ")",
                    auditFailure);
        }
    }

    private SyntheticInternalImportResult importDataset(Dataset dataset, String runId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('trust-agent-synthetic-internal-import'))")
                .query()
                .singleRow();
        if (count("synthetic_internal_import_run", "import_run_id", runId) > 0) {
            throw failure("RUN_ID_CONFLICT", "이미 사용한 synthetic import run ID입니다.");
        }
        validateFingerprint(dataset.fingerprint());
        if (businessRowCount() > 0) {
            verifyExisting(dataset);
        } else {
            insert(dataset);
        }
        verifyChildren(dataset);
        Map<String, Integer> counts = expectedCounts(dataset);
        verifyCounts(counts);
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into synthetic_internal_import_run values (
                          :id,:fingerprint,:started,:completed,'SUCCEEDED',cast(:counts as jsonb),null)
                        """)
                .param("id", runId)
                .param("fingerprint", dataset.fingerprint())
                .param("started", now.atOffset(ZoneOffset.UTC))
                .param("completed", now.atOffset(ZoneOffset.UTC))
                .param("counts", json(counts))
                .update();
        return new SyntheticInternalImportResult(runId, dataset.fingerprint(), Map.copyOf(counts));
    }

    private void validateFingerprint(String fingerprint) {
        List<String> stored = jdbc.sql("""
                        select distinct baseline_fingerprint
                        from synthetic_internal_import_run where status='SUCCEEDED'
                        """)
                .query(String.class)
                .list();
        if (businessRowCount() == 0 && stored.isEmpty()) {
            return;
        }
        if (stored.size() != 1 || !stored.getFirst().equals(fingerprint)) {
            throw failure(
                    "BASELINE_MISMATCH",
                    "다른 synthetic internal baseline을 비어 있지 않은 DB에 섞을 수 없습니다.");
        }
    }

    private void insert(Dataset dataset) {
        for (Source notice : dataset.notices()) {
            JsonNode n = notice.json();
            jdbc.sql("""
                    insert into internal_notice_version values
                    (:id,'SYNTHETIC_INTERNAL',true,:disclaimer,:family,:version,:title,
                     'ISSUED',:issued,:from,:to,:supersedes,:hash)
                    """)
                    .param("id", text(n, "notice_id"))
                    .param("disclaimer", text(n, "disclaimer"))
                    .param("family", text(n, "family_id"))
                    .param("version", n.get("version").asInt())
                    .param("title", text(n, "title"))
                    .param("issued", LocalDate.parse(text(n, "issued_on")))
                    .param("from", LocalDate.parse(text(n,"effective_from")))
                    .param("to", nullableDate(n.get("effective_to")))
                    .param("supersedes", nullableText(n.get("supersedes_notice_id")))
                    .param("hash", notice.hash())
                    .update();
        }
        for (Source notice : dataset.notices()) {
            insertRulesAndReferences(notice);
        }
        for (Source receipt : dataset.receipts()) {
            JsonNode n = receipt.json();
            Instant receivedAt = Instant.parse(text(n, "received_at"));
            jdbc.sql("""
                            insert into internal_notice_receipt values (
                              :id,'SYNTHETIC_INTERNAL',true,:notice,:received,:businessDate,
                              :zone,:policy,:hash)
                            """)
                    .param("id", text(n, "receipt_id"))
                    .param("notice", text(n, "notice_id"))
                    .param("received", receivedAt.atOffset(ZoneOffset.UTC))
                    .param("businessDate", businessTime.businessDate(receivedAt))
                    .param("zone", businessTime.businessTimezone())
                    .param("policy", BusinessTimePolicy.POLICY_VERSION)
                    .param("hash", receipt.hash())
                    .update();
        }
        for (Source extraction : dataset.extractions()) {
            JsonNode n = extraction.json();
            jdbc.sql("""
                            insert into policy_extraction_attempt values (
                              :id,'DERIVED',:receipt,:notice,:attempted,:parser,
                              'SUCCEEDED',null,:hash)
                            """)
                    .param("id", text(n, "extraction_attempt_id"))
                    .param("receipt", text(n, "receipt_id"))
                    .param("notice", text(n, "notice_id"))
                    .param("attempted", Instant.parse(text(n, "attempted_at")).atOffset(ZoneOffset.UTC))
                    .param("parser", text(n, "parser_version"))
                    .param("hash", extraction.hash())
                    .update();
            Source notice = dataset.noticeById().get(text(n, "notice_id"));
            insertEvidence(notice, text(n, "extraction_attempt_id"));
        }
    }

    private void insertRulesAndReferences(Source notice) {
        JsonNode rules = notice.json().get("rules");
        int referenceOrder = 0;
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            String ruleHash = hasher.canonicalize(rule).sha256();
            String ruleId = "policy-rule:" + ruleHash;
            jdbc.sql("""
                            insert into internal_policy_rule_version values (
                              :id,'SYNTHETIC_INTERNAL',:key,:instruction,:required,:hash)
                            on conflict (rule_version_id) do nothing
                            """)
                    .param("id", ruleId)
                    .param("key", text(rule, "rule_key"))
                    .param("instruction", text(rule, "instruction"))
                    .param("required", rule.get("evidence_required").asBoolean())
                    .param("hash", ruleHash)
                    .update();
            JsonNode cross = rule.get("public_cross_check");
            if (!cross.isNull()) {
                jdbc.sql("""
                                insert into internal_notice_reference values (
                                  :notice,:order,'PUBLIC_KB',:product,:snapshot,:fact,:subject,
                                  :type,:value,:unit,:requirement,:hash)
                                """)
                        .param("notice", text(notice.json(), "notice_id"))
                        .param("order", referenceOrder++)
                        .param("product", text(cross, "product_key"))
                        .param("snapshot", text(cross, "snapshot_hash"))
                        .param("fact", text(cross, "fact_key"))
                        .param("subject", text(cross, "subject_type"))
                        .param("type", text(cross, "value_type"))
                        .param("value", cross.get("expected_value").asLong())
                        .param("unit", text(cross, "unit"))
                        .param("requirement", text(cross, "evidence_requirement"))
                        .param("hash", hasher.canonicalize(cross).sha256())
                        .update();
            }
        }
    }

    private void insertEvidence(Source notice, String extractionId) {
        JsonNode rules = notice.json().get("rules");
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            String ruleHash = hasher.canonicalize(rule).sha256();
            ObjectNode evidence = mapper.createObjectNode();
            evidence.put("notice_id", text(notice.json(), "notice_id"));
            evidence.put("rule_order", i);
            evidence.put("rule_hash", ruleHash);
            jdbc.sql("""
                            insert into internal_policy_rule_evidence values (
                              :extraction,:notice,:rule,:order,:pointer,:text,
                              :evidenceHash,:sourceHash)
                            """)
                    .param("extraction", extractionId)
                    .param("notice", text(notice.json(), "notice_id"))
                    .param("rule", "policy-rule:" + ruleHash)
                    .param("order", i)
                    .param("pointer", "/rules/" + i)
                    .param("text", text(rule, "instruction"))
                    .param("evidenceHash", hasher.canonicalize(rule.get("instruction")).sha256())
                    .param("sourceHash", hasher.canonicalize(evidence).sha256())
                    .update();
        }
    }

    private void verifyExisting(Dataset dataset) {
        for (Source source : concat(dataset)) {
            String table;
            String column;
            String id;
            if (dataset.notices().contains(source)) {
                table = "internal_notice_version";
                column = "notice_id";
                id = text(source.json(), "notice_id");
            } else if (dataset.receipts().contains(source)) {
                table = "internal_notice_receipt";
                column = "receipt_id";
                id = text(source.json(), "receipt_id");
            } else {
                table = "policy_extraction_attempt";
                column = "extraction_attempt_id";
                id = text(source.json(), "extraction_attempt_id");
            }
            requireSourceIdentifier(table, column);
            String stored = jdbc.sql(
                            "select source_record_hash from " + table + " where " + column + "=:id")
                    .param("id", id)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> failure(
                            "BASELINE_CONTENT_MISMATCH", "기존 baseline 행이 없습니다: " + id));
            if (!stored.equals(source.hash())) {
                throw failure(
                        "SOURCE_RECORD_CONFLICT", "동일 ID의 source record 내용이 다릅니다: " + id);
            }
        }
    }

    private void verifyChildren(Dataset dataset) {
        for (Source notice : dataset.notices()) {
            JsonNode rules = notice.json().get("rules");
            int referenceOrder = 0;
            for (int i = 0; i < rules.size(); i++) {
                JsonNode rule = rules.get(i);
                String ruleHash = hasher.canonicalize(rule).sha256();
                Map<String, Object> storedRule = jdbc.sql("""
                                select rule_key,instruction,evidence_required,source_record_hash
                                from internal_policy_rule_version where rule_version_id=:id
                                """)
                        .param("id", "policy-rule:" + ruleHash)
                        .query()
                        .singleRow();
                requireEqual(text(rule, "rule_key"), storedRule.get("rule_key"), "rule_key");
                requireEqual(
                        text(rule, "instruction"), storedRule.get("instruction"), "instruction");
                requireEqual(
                        rule.get("evidence_required").asBoolean(),
                        storedRule.get("evidence_required"),
                        "evidence_required");
                requireEqual(
                        ruleHash, storedRule.get("source_record_hash"), "rule source_record_hash");
                JsonNode cross = rule.get("public_cross_check");
                if (!cross.isNull()) {
                    Map<String, Object> reference = jdbc.sql("""
                                    select product_key,snapshot_hash,fact_key,subject_type,value_type,
                                           expected_value,unit,evidence_requirement,source_record_hash
                                    from internal_notice_reference
                                    where notice_id=:notice and reference_order=:order
                                    """)
                            .param("notice", text(notice.json(), "notice_id"))
                            .param("order", referenceOrder++)
                            .query()
                            .singleRow();
                    requireEqual(
                            text(cross, "product_key"),
                            reference.get("product_key"),
                            "reference product_key");
                    requireEqual(
                            text(cross, "snapshot_hash"),
                            reference.get("snapshot_hash"),
                            "reference snapshot_hash");
                    requireEqual(text(cross, "fact_key"), reference.get("fact_key"), "reference fact_key");
                    requireEqual(
                            text(cross, "subject_type"),
                            reference.get("subject_type"),
                            "reference subject_type");
                    requireEqual(
                            text(cross, "value_type"),
                            reference.get("value_type"),
                            "reference value_type");
                    requireEqual(
                            cross.get("expected_value").asLong(),
                            ((Number) reference.get("expected_value")).longValue(),
                            "reference expected_value");
                    requireEqual(text(cross, "unit"), reference.get("unit"), "reference unit");
                    requireEqual(
                            text(cross, "evidence_requirement"),
                            reference.get("evidence_requirement"),
                            "reference requirement");
                    requireEqual(
                            hasher.canonicalize(cross).sha256(),
                            reference.get("source_record_hash"),
                            "reference source_record_hash");
                }
            }
        }
        for (Source extraction : dataset.extractions()) {
            String noticeId = text(extraction.json(), "notice_id");
            Source notice = dataset.noticeById().get(noticeId);
            JsonNode rules = notice.json().get("rules");
            for (int i = 0; i < rules.size(); i++) {
                JsonNode rule = rules.get(i);
                String ruleHash = hasher.canonicalize(rule).sha256();
                ObjectNode expectedSource = mapper.createObjectNode();
                expectedSource.put("notice_id", noticeId);
                expectedSource.put("rule_order", i);
                expectedSource.put("rule_hash", ruleHash);
                Map<String, Object> evidence = jdbc.sql("""
                                select notice_id,rule_order,json_pointer,evidence_text,
                                       evidence_hash,source_record_hash
                                from internal_policy_rule_evidence
                                where extraction_attempt_id=:attempt and rule_version_id=:rule
                                """)
                        .param("attempt", text(extraction.json(), "extraction_attempt_id"))
                        .param("rule", "policy-rule:" + ruleHash)
                        .query()
                        .singleRow();
                requireEqual(noticeId, evidence.get("notice_id"), "evidence notice_id");
                requireEqual(
                        i, ((Number) evidence.get("rule_order")).intValue(), "evidence rule_order");
                requireEqual("/rules/" + i, evidence.get("json_pointer"), "evidence json_pointer");
                requireEqual(text(rule, "instruction"), evidence.get("evidence_text"), "evidence text");
                requireEqual(
                        hasher.canonicalize(rule.get("instruction")).sha256(),
                        evidence.get("evidence_hash"),
                        "evidence hash");
                requireEqual(
                        hasher.canonicalize(expectedSource).sha256(),
                        evidence.get("source_record_hash"),
                        "evidence source_record_hash");
            }
        }
    }

    private static void requireEqual(Object expected, Object actual, String field) {
        if (!Objects.equals(expected, actual)) {
            throw failure(
                    "BASELINE_CONTENT_MISMATCH", "기존 synthetic baseline 하위 행이 다릅니다: " + field);
        }
    }

    private Map<String, Integer> expectedCounts(Dataset dataset) {
        int rules = (int) dataset.notices().stream()
                .flatMap(notice -> {
                    List<String> hashes = new ArrayList<>();
                    notice.json().get("rules").forEach(
                            rule -> hashes.add(hasher.canonicalize(rule).sha256()));
                    return hashes.stream();
                })
                .distinct()
                .count();
        int evidence = dataset.notices().stream()
                .mapToInt(notice -> notice.json().get("rules").size())
                .sum();
        int references = dataset.notices().stream()
                .mapToInt(notice -> {
                    int count = 0;
                    for (JsonNode rule : notice.json().get("rules")) {
                        if (!rule.get("public_cross_check").isNull()) {
                            count++;
                        }
                    }
                    return count;
                })
                .sum();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("internal_notice_version", dataset.notices().size());
        counts.put("internal_notice_reference", references);
        counts.put("internal_notice_receipt", dataset.receipts().size());
        counts.put("policy_extraction_attempt", dataset.extractions().size());
        counts.put("internal_policy_rule_version", rules);
        counts.put("internal_policy_rule_evidence", evidence);
        return counts;
    }

    private void verifyCounts(Map<String,Integer> expected) {
        verifyExactCounts(expected, this::tableCount, SyntheticInternalImportException::new);
    }

    private Dataset load(Path root) {
        Path datasetRoot = Files.isDirectory(root.resolve("datasets"))
                ? root.resolve("datasets")
                : root;
        List<Source> notices = loadFiles(datasetRoot, "synthetic/internal/notices");
        List<Source> receipts = loadFiles(datasetRoot, "synthetic/internal/receipts");
        List<Source> extractions =
                loadFiles(datasetRoot, "derived/synthetic-internal/policy-extraction-attempts");
        if (notices.isEmpty() || receipts.isEmpty() || extractions.isEmpty()) {
            throw failure("INVALID_INPUT_ROOT", "synthetic baseline 필수 디렉터리가 비어 있습니다.");
        }
        Map<String, Source> byId = new LinkedHashMap<>();
        notices.forEach(notice -> byId.put(text(notice.json(), "notice_id"), notice));
        validateRelations(notices, receipts, extractions, byId);
        ArrayNode inventory = mapper.createArrayNode();
        concat(new Dataset(notices, receipts, extractions, byId, "", inventory)).stream()
                .sorted(Comparator.comparing(Source::path))
                .forEach(source -> {
                    ObjectNode item = inventory.addObject();
                    item.put("path", source.path());
                    item.put("sha256", source.hash());
                });
        return new Dataset(
                notices,
                receipts,
                extractions,
                Map.copyOf(byId),
                hasher.canonicalize(inventory).sha256(),
                inventory);
    }

    private void validateRelations(
            List<Source> notices,
            List<Source> receipts,
            List<Source> extractions,
            Map<String, Source> byId) {
        Map<String, Integer> roots = new LinkedHashMap<>();
        Set<String> families = new HashSet<>();
        for (Source source : notices) {
            JsonNode notice = source.json();
            String family = text(notice, "family_id");
            families.add(family);
            if (!"SYNTHETIC_INTERNAL".equals(text(notice, "dataset_class"))
                    || !notice.get("synthetic").asBoolean()
                    || !"ISSUED".equals(text(notice, "document_status"))) {
                throw failure("INVALID_NOTICE", "발행된 합성 공문만 적재할 수 있습니다.");
            }
            LocalDate from = LocalDate.parse(text(notice, "effective_from"));
            LocalDate to = nullableDate(notice.get("effective_to"));
            if (to != null && !from.isBefore(to)) {
                throw failure("INVALID_NOTICE_INTERVAL", "공문 적용 종료일은 시작일보다 뒤여야 합니다.");
            }
            String predecessor = nullableText(notice.get("supersedes_notice_id"));
            if (predecessor == null) {
                roots.merge(family, 1, Integer::sum);
            } else {
                Source previous = byId.get(predecessor);
                if (previous == null) {
                    throw failure("INVALID_SUPERSESSION", "supersede 대상 공문이 없습니다.");
                }
                if (!family.equals(text(previous.json(), "family_id"))
                        || notice.get("version").asInt()
                                <= previous.json().get("version").asInt()) {
                    throw failure(
                            "INVALID_SUPERSESSION",
                            "공문 supersession family 또는 version이 올바르지 않습니다.");
                }
            }
        }
        if (families.stream().anyMatch(family -> roots.getOrDefault(family, 0) != 1)) {
            throw failure("INVALID_SUPERSESSION", "family마다 root 공문은 하나여야 합니다.");
        }
        Map<String, Source> receiptById = new LinkedHashMap<>();
        for (Source source : receipts) {
            JsonNode receipt = source.json();
            if (!byId.containsKey(text(receipt, "notice_id"))) {
                throw failure("INVALID_RECEIPT", "receipt의 공문이 없습니다.");
            }
            if (!businessTime.businessTimezone().equals(text(receipt, "business_timezone"))
                    || !BusinessTimePolicy.POLICY_VERSION.equals(
                            text(receipt, "timezone_policy_version"))) {
                throw failure(
                        "TIMEZONE_POLICY_MISMATCH", "receipt timezone 정책이 실행 설정과 다릅니다.");
            }
            Instant.parse(text(receipt, "received_at"));
            receiptById.put(text(receipt, "receipt_id"), source);
        }
        for (Source source : extractions) {
            JsonNode extraction = source.json();
            Source receipt = receiptById.get(text(extraction, "receipt_id"));
            if (receipt == null
                    || !text(receipt.json(), "notice_id")
                            .equals(text(extraction, "notice_id"))) {
                throw failure(
                        "INVALID_EXTRACTION_REFERENCE", "extraction과 receipt 관계가 올바르지 않습니다.");
            }
            if (Instant.parse(text(extraction, "attempted_at"))
                    .isBefore(Instant.parse(text(receipt.json(), "received_at")))) {
                throw failure("INVALID_EXTRACTION_TIME", "공문 수신 전 extraction은 허용하지 않습니다.");
            }
        }
    }

    private List<Source> loadFiles(Path datasetRoot, String relativeDirectory) {
        Path directory = datasetRoot.resolve(relativeDirectory).normalize();
        if (!directory.startsWith(datasetRoot.normalize())) {
            throw failure("INVALID_INPUT_ROOT", "synthetic baseline 경로가 입력 root 밖입니다.");
        }
        try (Stream<Path> paths = Files.list(directory)) {
            List<Source> result = new ArrayList<>();
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path)
                        || !path.toString().endsWith(".json")) {
                    throw failure(
                            "UNEXPECTED_INPUT_FILE",
                            "허용되지 않은 synthetic baseline 파일입니다: " + path.getFileName());
                }
                JsonNode json = mapper.readTree(path.toFile());
                result.add(new Source(
                        datasetRoot.relativize(path).toString(),
                        json,
                        hasher.canonicalize(json).sha256()));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new SyntheticInternalImportException(
                    "INPUT_READ_FAILED", "synthetic baseline을 읽을 수 없습니다.", exception);
        }
    }

    private static List<Source> concat(Dataset dataset) {
        List<Source> all = new ArrayList<>();
        all.addAll(dataset.notices());
        all.addAll(dataset.receipts());
        all.addAll(dataset.extractions());
        return all;
    }

    private int businessRowCount() {
        return BUSINESS_TABLES.stream().mapToInt(this::tableCount).sum();
    }

    private int tableCount(String table) {
        String allowed = requireAllowedIdentifier(
                table, BUSINESS_TABLES, "synthetic baseline table");
        return jdbc.sql("select count(*) from " + allowed).query(Integer.class).single();
    }

    private int count(String table, String column, String value) {
        requireSourceIdentifier(table, column);
        return jdbc.sql("select count(*) from " + table + " where " + column + "=:value")
                .param("value", value)
                .query(Integer.class)
                .single();
    }

    static void requireSourceIdentifier(String table, String column) {
        com.trustagent.core.bootstrap.AppendOnlyBootstrapChecks.requireSourceIdentifier(
                table, column, SOURCE_ID_COLUMNS, "synthetic baseline");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw failure("JSON_WRITE_FAILED", "감사 JSON을 만들 수 없습니다.");
        }
    }

    private static String text(JsonNode node, String field) {
        return node.get(field).stringValue();
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.stringValue();
    }

    private static LocalDate nullableDate(JsonNode node) {
        return node == null || node.isNull() ? null : LocalDate.parse(node.stringValue());
    }

    private static SyntheticInternalImportException failure(String code, String message) {
        return new SyntheticInternalImportException(code, message);
    }

    private record Source(String path, JsonNode json, String hash) {}

    private record Dataset(
            List<Source> notices,
            List<Source> receipts,
            List<Source> extractions,
            Map<String, Source> noticeById,
            String fingerprint,
            JsonNode inventory) {}
}
