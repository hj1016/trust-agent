package com.trustagent.core.internalpolicy.proposal;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * test/demo 전용 승인 checklist 예시 데이터를 적재한다. origin=FIXTURE로 저장하며 인간 승인 증거가 아니다.
 * runtime 계정 권한으로 동작하고 importer 계정 권한은 사용하지 않는다. production profile에서는 bean이 만들어지지 않는다.
 */
public final class FixtureApprovedChecklistLoader {

    public record Result(String runId, String fingerprint, Map<String, Integer> counts) {}

    private static final Pattern RUN_ID = Pattern.compile("^checklist-fixture-run:[a-f0-9]{32}$");
    private static final String RELATIVE_DIRECTORY = "synthetic/internal/approved-checklists";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CanonicalJsonHasher hasher;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public FixtureApprovedChecklistLoader(
            JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.hasher = new CanonicalJsonHasher(mapper);
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public Result load(Path repositoryRoot, String requestedRunId) {
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? "checklist-fixture-run:" + UUID.randomUUID().toString().replace("-", "")
                : requestedRunId;
        if (!RUN_ID.matcher(runId).matches()) {
            throw failure("INVALID_RUN_ID", "fixture run ID 형식이 올바르지 않습니다.");
        }
        Dataset dataset = load(repositoryRoot);
        try {
            return transaction.execute(status -> loadDataset(dataset, runId));
        } catch (FixtureApprovedChecklistException exception) {
            if (!exception.code().equals("RUN_ID_CONFLICT")) {
                recordFailure(runId, dataset.fingerprint(), exception.code());
            }
            throw exception;
        } catch (RuntimeException exception) {
            recordFailure(runId, dataset.fingerprint(), "FIXTURE_LOAD_FAILED");
            throw new FixtureApprovedChecklistException(
                    "FIXTURE_LOAD_FAILED", "승인 checklist fixture 적재 중 DB 처리에 실패했습니다.", exception);
        }
    }

    private Result loadDataset(Dataset dataset, String runId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('trust-agent-approved-checklist-fixture'))")
                .query()
                .singleRow();
        if (count("approved_checklist_fixture_run", "fixture_run_id", runId) > 0) {
            throw failure("RUN_ID_CONFLICT", "이미 사용한 fixture run ID입니다.");
        }
        Instant startedAt = clock.instant();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("approved_checklist_version", 0);
        counts.put("approved_checklist_item", 0);
        counts.put("approved_checklist_schedule_revision", 0);
        counts.put("approved_checklist_schedule_entry", 0);

        for (String familyId : dataset.families()) {
            int humanReviewed = jdbc.sql("""
                            select count(*) from approved_checklist_version
                            where family_id = :family and origin = 'HUMAN_REVIEW'
                            """)
                    .param("family", familyId)
                    .query(Integer.class)
                    .single();
            if (humanReviewed > 0) {
                throw failure("HUMAN_APPROVAL_EXISTS",
                        "사람 검토로 승인된 checklist가 있는 family에는 fixture를 적재하지 않습니다: " + familyId);
            }
        }

        for (Source version : dataset.versions()) {
            String id = text(version.json(), "approved_checklist_version_id");
            Optional<String> stored = jdbc.sql(
                            "select source_record_hash from approved_checklist_version where approved_checklist_version_id = :id")
                    .param("id", id)
                    .query(String.class)
                    .optional();
            if (stored.isPresent()) {
                if (!stored.get().equals(version.hash())) {
                    throw failure("FIXTURE_CONTENT_CONFLICT", "같은 ID의 fixture 내용이 DB와 다릅니다: " + id);
                }
                int items = jdbc.sql("select count(*) from approved_checklist_item where approved_checklist_version_id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single();
                if (items != version.json().get("items").size()) {
                    throw failure("FIXTURE_CONTENT_CONFLICT", "같은 ID의 fixture 항목 수가 DB와 다릅니다: " + id);
                }
                continue;
            }
            insertVersion(version);
            counts.merge("approved_checklist_version", 1, Integer::sum);
            counts.merge("approved_checklist_item", version.json().get("items").size(), Integer::sum);
        }

        for (Source schedule : dataset.schedules()) {
            String id = text(schedule.json(), "schedule_revision_id");
            Optional<String> stored = jdbc.sql(
                            "select source_record_hash from approved_checklist_schedule_revision where schedule_revision_id = :id")
                    .param("id", id)
                    .query(String.class)
                    .optional();
            if (stored.isPresent()) {
                if (!stored.get().equals(schedule.hash())) {
                    throw failure("FIXTURE_CONTENT_CONFLICT", "같은 ID의 schedule fixture 내용이 DB와 다릅니다: " + id);
                }
                continue;
            }
            insertSchedule(schedule);
            counts.merge("approved_checklist_schedule_revision", 1, Integer::sum);
            counts.merge("approved_checklist_schedule_entry", schedule.json().get("entries").size(), Integer::sum);
        }

        jdbc.sql("""
                        insert into approved_checklist_fixture_run (
                            fixture_run_id, fixture_fingerprint, started_at, completed_at, status, imported_counts, error_code)
                        values (:id, :fingerprint, :started, :completed, 'SUCCEEDED', cast(:counts as jsonb), null)
                        """)
                .param("id", runId)
                .param("fingerprint", dataset.fingerprint())
                .param("started", startedAt.atOffset(ZoneOffset.UTC))
                .param("completed", clock.instant().atOffset(ZoneOffset.UTC))
                .param("counts", mapper.writeValueAsString(counts))
                .update();
        return new Result(runId, dataset.fingerprint(), Map.copyOf(counts));
    }

    private void insertVersion(Source version) {
        JsonNode json = version.json();
        String id = text(json, "approved_checklist_version_id");
        String familyId = text(json, "family_id");
        jdbc.sql("""
                        insert into approved_checklist_version (
                            approved_checklist_version_id, dataset_class, family_id, notice_id, created_at,
                            source_record_hash, origin)
                        values (:id, 'SYNTHETIC_INTERNAL', :family, :notice, :createdAt, :hash, 'FIXTURE')
                        """)
                .param("id", id)
                .param("family", familyId)
                .param("notice", text(json, "notice_id"))
                .param("createdAt", Instant.parse(text(json, "created_at")).atOffset(ZoneOffset.UTC))
                .param("hash", version.hash())
                .update();
        for (JsonNode item : json.get("items")) {
            jdbc.sql("""
                            insert into approved_checklist_item (
                                approved_checklist_version_id, family_id, item_order, rule_key, instruction,
                                evidence_required, structured_change, source_rule_version_id, source_record_hash)
                            values (:id, :family, :order, :ruleKey, :instruction, :evidenceRequired,
                                    cast(:structuredChange as jsonb), :sourceRule, :hash)
                            """)
                    .param("id", id)
                    .param("family", familyId)
                    .param("order", item.get("item_order").asInt())
                    .param("ruleKey", text(item, "rule_key"))
                    .param("instruction", text(item, "instruction"))
                    .param("evidenceRequired", item.get("evidence_required").asBoolean())
                    .param("structuredChange", mapper.writeValueAsString(item.get("structured_change")))
                    .param("sourceRule", nullableText(item.get("source_rule_version_id")))
                    .param("hash", hasher.canonicalize(item).sha256())
                    .update();
        }
    }

    private void insertSchedule(Source schedule) {
        JsonNode json = schedule.json();
        String id = text(json, "schedule_revision_id");
        String familyId = text(json, "family_id");
        jdbc.sql("""
                        insert into approved_checklist_schedule_revision (
                            schedule_revision_id, dataset_class, family_id, supersedes_schedule_revision_id,
                            created_at, source_record_hash)
                        values (:id, 'SYNTHETIC_INTERNAL', :family, :supersedes, :createdAt, :hash)
                        """)
                .param("id", id)
                .param("family", familyId)
                .param("supersedes", nullableText(json.get("supersedes_schedule_revision_id")))
                .param("createdAt", Instant.parse(text(json, "created_at")).atOffset(ZoneOffset.UTC))
                .param("hash", schedule.hash())
                .update();
        for (JsonNode entry : json.get("entries")) {
            jdbc.sql("""
                            insert into approved_checklist_schedule_entry (
                                schedule_revision_id, family_id, entry_order, approved_checklist_version_id,
                                effective_from, effective_to, source_record_hash)
                            values (:id, :family, :order, :version, :from, :to, :hash)
                            """)
                    .param("id", id)
                    .param("family", familyId)
                    .param("order", entry.get("entry_order").asInt())
                    .param("version", text(entry, "approved_checklist_version_id"))
                    .param("from", LocalDate.parse(text(entry, "effective_from")))
                    .param("to", entry.get("effective_to").isNull() ? null : LocalDate.parse(text(entry, "effective_to")))
                    .param("hash", hasher.canonicalize(entry).sha256())
                    .update();
        }
    }

    private void recordFailure(String runId, String fingerprint, String errorCode) {
        try {
            transaction.executeWithoutResult(status -> {
                Instant now = clock.instant();
                jdbc.sql("""
                                insert into approved_checklist_fixture_run (
                                    fixture_run_id, fixture_fingerprint, started_at, completed_at, status, imported_counts, error_code)
                                values (:id, :fingerprint, :started, :completed, 'FAILED', null, :error)
                                """)
                        .param("id", runId)
                        .param("fingerprint", fingerprint)
                        .param("started", now.atOffset(ZoneOffset.UTC))
                        .param("completed", now.atOffset(ZoneOffset.UTC))
                        .param("error", errorCode)
                        .update();
            });
        } catch (RuntimeException auditFailure) {
            throw new FixtureApprovedChecklistException(
                    "FAILURE_AUDIT_WRITE_FAILED",
                    "fixture 적재 실패 기록을 저장할 수 없습니다 (원래 실패: " + errorCode + ")",
                    auditFailure);
        }
    }

    private Dataset load(Path root) {
        Path datasetRoot = Files.isDirectory(root.resolve("datasets")) ? root.resolve("datasets") : root;
        Path directory = datasetRoot.resolve(RELATIVE_DIRECTORY);
        List<Source> versions = loadFiles(directory, ".approved-checklist.json");
        List<Source> schedules = loadFiles(directory, ".schedule.json");
        if (versions.isEmpty()) {
            throw failure("FIXTURE_NOT_FOUND", "승인 checklist fixture 파일이 없습니다: " + directory);
        }
        List<String> families = new ArrayList<>();
        List<String> versionIds = new ArrayList<>();
        for (Source version : versions) {
            validateCommon(version);
            requireText(version, "approved_checklist_version_id", "^approved-checklist:[a-f0-9]{32}$");
            requireText(version, "notice_id", "^SIN-[A-Z0-9-]+-V[1-9][0-9]*$");
            JsonNode items = version.json().get("items");
            if (items == null || !items.isArray() || items.isEmpty()) {
                throw failure("FIXTURE_INVALID", "fixture items가 비어 있습니다: " + version.path());
            }
            versionIds.add(text(version.json(), "approved_checklist_version_id"));
            String family = text(version.json(), "family_id");
            if (!families.contains(family)) {
                families.add(family);
            }
        }
        for (Source schedule : schedules) {
            validateCommon(schedule);
            requireText(schedule, "schedule_revision_id", "^checklist-schedule:[a-f0-9]{32}$");
            for (JsonNode entry : schedule.json().get("entries")) {
                String versionId = text(entry, "approved_checklist_version_id");
                if (!versionIds.contains(versionId)) {
                    throw failure("FIXTURE_INVALID",
                            "schedule entry가 fixture에 없는 checklist version을 참조합니다: " + versionId);
                }
            }
        }
        List<String> hashes = Stream.concat(versions.stream(), schedules.stream())
                .map(Source::hash)
                .sorted()
                .toList();
        String fingerprint = hasher.canonicalize(mapper.valueToTree(hashes)).sha256();
        return new Dataset(versions, schedules, families, fingerprint);
    }

    private void validateCommon(Source source) {
        JsonNode json = source.json();
        if (!"SYNTHETIC_INTERNAL".equals(nullableText(json.get("dataset_class")))
                || json.get("synthetic") == null || !json.get("synthetic").asBoolean()
                || !"FIXTURE".equals(nullableText(json.get("origin")))) {
            throw failure("FIXTURE_INVALID",
                    "fixture는 dataset_class SYNTHETIC_INTERNAL, synthetic true, origin FIXTURE여야 합니다: " + source.path());
        }
        String disclaimer = nullableText(json.get("disclaimer"));
        if (disclaimer == null || !disclaimer.contains("합성")) {
            throw failure("FIXTURE_INVALID", "fixture 면책 문구가 없습니다: " + source.path());
        }
        requireText(source, "family_id", "^SIN-[A-Z0-9-]+$");
        requireText(source, "created_at", "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");
    }

    private void requireText(Source source, String field, String pattern) {
        String value = nullableText(source.json().get(field));
        if (value == null || !value.matches(pattern)) {
            throw failure("FIXTURE_INVALID", "fixture 필드 " + field + " 형식이 올바르지 않습니다: " + source.path());
        }
    }

    private List<Source> loadFiles(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .map(path -> {
                        try {
                            JsonNode json = mapper.readTree(Files.readString(path));
                            return new Source(path.toString(), json, hasher.canonicalize(json).sha256());
                        } catch (IOException exception) {
                            throw failure("FIXTURE_READ_FAILED", "fixture 파일을 읽을 수 없습니다: " + path);
                        }
                    })
                    .toList();
        } catch (IOException exception) {
            throw failure("FIXTURE_READ_FAILED", "fixture 디렉터리를 읽을 수 없습니다: " + directory);
        }
    }

    private int count(String table, String column, String value) {
        if (!table.equals("approved_checklist_fixture_run") || !column.equals("fixture_run_id")) {
            throw new IllegalArgumentException("허용되지 않은 fixture 식별자입니다: " + table + "." + column);
        }
        return jdbc.sql("select count(*) from approved_checklist_fixture_run where fixture_run_id = :value")
                .param("value", value)
                .query(Integer.class)
                .single();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw failure("FIXTURE_INVALID", "fixture 필드가 없습니다: " + field);
        }
        return value.stringValue();
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.stringValue();
    }

    private static FixtureApprovedChecklistException failure(String code, String message) {
        return new FixtureApprovedChecklistException(code, message);
    }

    private record Source(String path, JsonNode json, String hash) {}

    private record Dataset(List<Source> versions, List<Source> schedules, List<String> families, String fingerprint) {}
}
