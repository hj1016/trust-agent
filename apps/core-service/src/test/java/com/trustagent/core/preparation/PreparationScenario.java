package com.trustagent.core.preparation;

import com.trustagent.core.internalpolicy.bootstrap.SyntheticInternalImporter;
import com.trustagent.core.internalpolicy.proposal.FixtureApprovedChecklistLoader;
import com.trustagent.core.internalpolicy.proposal.HumanReviewService;
import com.trustagent.core.internalpolicy.proposal.ProposalGenerationService;
import com.trustagent.core.internalpolicy.proposal.ProposalValidationService;
import com.trustagent.core.json.CanonicalJsonHasher;
import com.trustagent.core.publicproduct.baseline.BaselineImporter;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * TASK-015 테스트 공통 상태: TASK-014 평가 고정 조건과 같다. 중도상환수수료 v2는 2026-10-05T04:30Z에 승인, 셀러론 v2는 변경안·검증만 있고 승인 없음.
 * 매핑은 저장소의 승인 설정 파일을 적재한다. 토큰은 실행 중 생성한 임시 값이다.
 */
public final class PreparationScenario {

    public static final Instant EVALUATED_AT = Instant.parse("2026-10-06T03:00:00Z");
    public static final String PREPAYMENT = "SIN-PREPAYMENT-FEE";
    public static final String SELLER = "SIN-SELLER-CHECKLIST";
    public static final String APPLICATION = "SW-APPLICATION-001";
    static final List<String> BUSINESS_TABLES = List.of(
            "human_review_decision", "approved_checklist_version", "approved_checklist_item",
            "approved_checklist_schedule_revision", "approved_checklist_schedule_entry", "checklist_change_proposal",
            "automated_validation_result", "internal_notice_lifecycle_event", "consultation_family_mapping", "tool_call_audit");

    private PreparationScenario() {}

    public static final class AdjustableClock extends Clock {
        private volatile Instant instant = EVALUATED_AT;
        public void set(Instant value) { instant = value; }
        public void reset() { instant = EVALUATED_AT; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    /** 적재 결과: 활성 매핑 해시와 셀러론 변경안 ID(전체 READY 사례에서 승인에 쓴다). */
    public record State(String mappingHash, String sellerProposalId) {}

    /** 적재 → 예시 checklist → 중도상환수수료 승인(04:30) → 셀러론 변경안·검증만 → 매핑 적재. 끝나면 시계를 평가 시각으로 되돌린다. */
    public static State load(JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, AdjustableClock clock,
            PublicProductObservedStateService publicProducts, Path root) {
        new BaselineImporter(jdbc, mapper, manager).importBaseline(root, "baseline:" + "5".repeat(32));
        new SyntheticInternalImporter(jdbc, mapper, manager, "Asia/Seoul").importBaseline(root, "synthetic-import:" + "5".repeat(32));
        new FixtureApprovedChecklistLoader(jdbc, mapper, manager, clock).load(root, "checklist-fixture-run:" + "5".repeat(32));
        clock.set(Instant.parse("2026-10-05T04:00:00Z"));
        var generation = new ProposalGenerationService(jdbc, mapper, manager, clock);
        var validation = new ProposalValidationService(jdbc, mapper, manager, clock, publicProducts);
        String prepayment = generation.generate(new ProposalGenerationService.Request(
                PREPAYMENT, "SIN-PREPAYMENT-FEE-V2", "proposal-run:" + "5".repeat(32), "proposal-generator-v1")).proposalId();
        var pass = validation.validate(new ProposalValidationService.Request(prepayment, "validation-run:" + "5".repeat(32), "proposal-validator-v1"));
        String seller = generation.generate(new ProposalGenerationService.Request(SELLER, "SIN-SELLER-CHECKLIST-V2", null, "proposal-generator-v1")).proposalId();
        validation.validate(new ProposalValidationService.Request(seller, null, "proposal-validator-v1"));
        clock.set(Instant.parse("2026-10-05T04:30:00Z"));
        new HumanReviewService(jdbc, mapper, manager, clock, Duration.ofHours(24)).decide(new HumanReviewService.Request(
                prepayment, pass.validationResultId(), HumanReviewService.Decision.APPROVE, "SYN-REVIEWER-01", null, List.of(),
                "review-run:" + "3".repeat(32)));
        clock.reset();
        String mappingHash = new ConsultationFamilyMappingLoader(jdbc, mapper, manager, clock).load(root).mappingHash();
        return new State(mappingHash, seller);
    }

    /**
     * 전체 READY 사례용(별도 테스트 환경에서만): 셀러론 v2 변경안을 2026-10-05T05:00Z에 승인한다. 검증은 30일 공개 근거 정책에서 PASS다.
     * 기존 PARTIAL·HOLD 사례의 고정 조건(셀러론 미승인)은 바꾸지 않는다.
     */
    public static String approveSeller(JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, AdjustableClock clock,
            String sellerProposalId) {
        clock.set(Instant.parse("2026-10-05T05:00:00Z"));
        try {
            return new HumanReviewService(jdbc, mapper, manager, clock, Duration.ofHours(24)).decide(new HumanReviewService.Request(
                    sellerProposalId, null, HumanReviewService.Decision.APPROVE, "SYN-REVIEWER-01", null, List.of(),
                    "review-run:" + "6".repeat(32))).decisionId();
        } finally {
            clock.reset();
        }
    }

    /**
     * 연결 검증의 Python 환경. 로컬 기본은 명시적 skip(assumption), 요청·필수 조건에서는 환경 누락이 AI_INTEGRATION_ENV_MISSING 실패다.
     */
    public static Path aiServicePython(Path root) throws Exception {
        boolean requested = "true".equals(System.getProperty("trustAgent.aiServiceIntegration"));
        boolean required = "1".equals(System.getenv("TRUST_AGENT_REQUIRE_AI_INTEGRATION"));
        org.junit.jupiter.api.Assumptions.assumeTrue(requested || required,
                "명시적 skip(로컬 기본): -PaiServiceIntegration=true 또는 TRUST_AGENT_REQUIRE_AI_INTEGRATION=1로 실행한다.");
        Path python = Path.of(System.getProperty("trustAgent.aiServicePython", ""));
        List<String> missing = new java.util.ArrayList<>();
        if (python.toString().isBlank() || !java.nio.file.Files.isExecutable(python)) missing.add("python venv: " + python);
        if (!java.nio.file.Files.isDirectory(root.resolve("apps/ai-service/ai_service"))) missing.add("ai_service 패키지");
        if (missing.isEmpty()) {
            Process check = new ProcessBuilder(python.toString(), "-c", "import fastapi, jsonschema").redirectErrorStream(true).start();
            if (!check.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || check.exitValue() != 0) {
                missing.add("Python 의존성(fastapi, jsonschema): "
                        + new String(check.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim());
            }
        }
        if (!missing.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail("AI_INTEGRATION_ENV_MISSING: " + String.join("; ", missing));
        }
        return python;
    }

    public static DataSourceTransactionManager manager(javax.sql.DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /** AI 서비스(ids.py)와 같은 규칙: preparation_id, run_id, consultation_id, sections[].evaluated_at, sections[].tool_response_hash를 빼고 canonical sha256. */
    static String preparationId(ObjectMapper mapper, JsonNode body) {
        ObjectNode subject = (ObjectNode) body.deepCopy();
        subject.remove("preparation_id");
        subject.remove("run_id");
        subject.remove("consultation_id");
        for (JsonNode section : subject.get("sections")) {
            ((ObjectNode) section).remove("evaluated_at");
            ((ObjectNode) section).remove("tool_response_hash");
        }
        return "consultation-preparation:" + new CanonicalJsonHasher(mapper).canonicalize(subject).sha256();
    }
}
