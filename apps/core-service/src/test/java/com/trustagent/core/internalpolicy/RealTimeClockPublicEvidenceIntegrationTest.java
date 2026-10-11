package com.trustagent.core.internalpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.TrustAgentCoreApplication;
import com.trustagent.core.internalpolicy.proposal.FixtureApprovedChecklistLoader;
import com.trustagent.core.internalpolicy.bootstrap.SyntheticInternalImporter;
import com.trustagent.core.internalpolicy.proposal.HumanReviewService;
import com.trustagent.core.internalpolicy.proposal.ProposalGenerationService;
import com.trustagent.core.internalpolicy.proposal.ProposalValidationService;
import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableService;
import com.trustagent.core.internalpolicy.query.InternalPolicyApplicableState;
import com.trustagent.core.publicproduct.baseline.BaselineImporter;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import com.trustagent.core.publicproduct.query.PublicProductQueryException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * 회귀: 실제로 시간이 흐르는 Clock에서 변경안 검증과 적용 조회의 공개 근거 확인.
 * 결함: 검증·조회가 자기 기준 시각을 asOf로 넘기면 공개 상품 조회가 시계를 다시 읽어 더 늦은 평가 시각과 비교했고,
 * asOf가 몇 마이크로초 앞서 HISTORICAL_AS_OF로 막혔다(고정 시계 테스트에서는 두 시각이 같아 드러나지 않음).
 * 수정 뒤에도 과거 시점 조회 차단과 미래 시점 거부는 그대로여야 한다.
 */
@SpringBootTest(classes = {TrustAgentCoreApplication.class, RealTimeClockPublicEvidenceIntegrationTest.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealTimeClockPublicEvidenceIntegrationTest {

    /** 실제 시스템 시계를 기준 시각으로 옮긴 시계. 호출할 때마다 실제 시간만큼 앞으로 간다. */
    private static final Instant BASE = Instant.parse("2026-10-06T03:00:00Z");
    private static final Clock TICKING = Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), BASE));
    private static final String IMAGE = "postgres@sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("trust-agent.public-evidence.max-confirmation-age", () -> "30d");
    }

    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private PublicProductObservedStateService publicProducts;
    @Autowired private InternalPolicyApplicableService applicable;

    private JdbcClient jdbc;
    private ProposalValidationService.Result sellerValidation;

    @BeforeAll
    void loadThroughGenerationValidationAndApproval() {
        jdbc = JdbcClient.create(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        Path root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        new BaselineImporter(jdbc, mapper, manager).importBaseline(root, "baseline:" + "7".repeat(32));
        new SyntheticInternalImporter(jdbc, mapper, manager, "Asia/Seoul").importBaseline(root, "synthetic-import:" + "7".repeat(32));
        new FixtureApprovedChecklistLoader(jdbc, mapper, manager, TICKING).load(root, "checklist-fixture-run:" + "7".repeat(32));
        var generation = new ProposalGenerationService(jdbc, mapper, manager, TICKING);
        var validation = new ProposalValidationService(jdbc, mapper, manager, TICKING, publicProducts);
        var review = new HumanReviewService(jdbc, mapper, manager, TICKING, Duration.ofHours(24));
        for (String[] target : List.of(new String[] {"SIN-PREPAYMENT-FEE", "SIN-PREPAYMENT-FEE-V2"}, new String[] {"SIN-SELLER-CHECKLIST", "SIN-SELLER-CHECKLIST-V2"})) {
            String proposalId = generation.generate(new ProposalGenerationService.Request(target[0], target[1], null, "proposal-generator-v1")).proposalId();
            var result = validation.validate(new ProposalValidationService.Request(proposalId, null, "proposal-validator-v1"));
            if (target[0].equals("SIN-SELLER-CHECKLIST")) sellerValidation = result;
            if (result.status() == com.trustagent.core.internalpolicy.proposal.ProposalValidator.Status.PASS) {
                review.decide(new HumanReviewService.Request(proposalId, result.validationResultId(), HumanReviewService.Decision.APPROVE,
                        "SYN-REVIEWER-01", null, List.of(), null));
            }
        }
    }

    @Test
    void sellerValidationWithRequiredPublicEvidencePassesOnATickingClock() {
        assertEquals(com.trustagent.core.internalpolicy.proposal.ProposalValidator.Status.PASS, sellerValidation.status(), "필수 공개 근거가 있는 셀러론 변경안: " + issues(sellerValidation.validationResultId()));
        assertFalse(issues(sellerValidation.validationResultId()).contains("PUBLIC_EVIDENCE_UNCONFIRMED"));
        String reference = jdbc.sql("select public_evidence_refs::text from automated_validation_result where validation_result_id = :id")
                .param("id", sellerValidation.validationResultId()).query(String.class).single();
        assertFalse(reference.contains("HISTORICAL_AS_OF"), reference);
    }

    @Test
    void applicableQueryAfterApprovalIsUsableOnATickingClock() {
        InternalPolicyApplicableState seller = applicable.get("SIN-SELLER-CHECKLIST", "2026-10-06", null);
        assertTrue(seller.internalChecklistUseAllowed(), "승인 뒤 현재 조회: " + seller.blockingReasons());
        assertFalse(seller.blockingReasons().contains("PUBLIC_EVIDENCE_UNCONFIRMED"));
        assertTrue(applicable.get("SIN-PREPAYMENT-FEE", "2026-10-06", null).internalChecklistUseAllowed());
    }

    @Test
    void historicalAndFutureRulesAreUnchanged() {
        // 과거 시점(knownAt)을 명시한 조회는 여전히 확인 불가(차단)다.
        InternalPolicyApplicableState historical = applicable.get("SIN-SELLER-CHECKLIST", "2026-10-06", TICKING.instant().minusSeconds(60).toString());
        assertFalse(historical.internalChecklistUseAllowed());
        // 공개 상품 조회의 과거 asOf는 확인 불가, 미래 asOf는 거부다.
        var past = publicProducts.get("kb-seller-loan", TICKING.instant().minusSeconds(60).toString());
        assertTrue(past.historicalQuery());
        assertFalse(past.publicEvidenceConfirmationAllowed());
        var future = assertThrows(PublicProductQueryException.class,
                () -> publicProducts.get("kb-seller-loan", TICKING.instant().plusSeconds(3600).toString()));
        assertEquals("FUTURE_AS_OF_NOT_ALLOWED", future.code());
    }

    private List<String> issues(String validationResultId) {
        return jdbc.sql("select code from automated_validation_issue where validation_result_id = :id order by issue_order")
                .param("id", validationResultId).query(String.class).list();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        Clock tickingClock() {
            return TICKING;
        }
    }
}
