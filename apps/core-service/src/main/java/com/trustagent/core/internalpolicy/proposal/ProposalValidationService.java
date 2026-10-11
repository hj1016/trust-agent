package com.trustagent.core.internalpolicy.proposal;

import com.trustagent.core.publicproduct.query.PublicProductObservedState;
import com.trustagent.core.publicproduct.query.PublicProductObservedStateService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 변경안 자동 검증. 결과, issue, 실행 기록을 한 트랜잭션으로 저장하고 중간 실패 시 전부 취소한다.
 * 검증 통과는 승인이 아니며 checklist 사용 허용을 바꾸지 않는다.
 */
public final class ProposalValidationService {

    public record Request(String proposalId, String runId, String validatorVersion) {}

    public record Result(String runId, String validationResultId, ProposalValidator.Status status, int issueCount) {}

    private static final Pattern RUN_ID = Pattern.compile("^validation-run:[a-f0-9]{32}$");
    private static final Pattern VALIDATOR_VERSION = Pattern.compile("^[a-z][a-z0-9-]+$");

    private final ProposalRepository repository;
    private final ProposalValidator validator;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final PublicProductObservedStateService publicProducts;
    private final ObjectMapper mapper;

    public ProposalValidationService(
            JdbcClient jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            Clock clock,
            PublicProductObservedStateService publicProducts) {
        this(jdbc, mapper, manager, clock, publicProducts, new ProposalValidator(mapper));
    }

    /** 테스트가 검증기를 바꿔 저장 실패 경로를 유도할 수 있게 한다. */
    ProposalValidationService(
            JdbcClient jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            Clock clock,
            PublicProductObservedStateService publicProducts,
            ProposalValidator validator) {
        this.repository = new ProposalRepository(jdbc, mapper);
        this.validator = validator;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
        this.publicProducts = publicProducts;
        this.mapper = mapper;
    }

    public Result validate(Request request) {
        String runId = request.runId() == null || request.runId().isBlank()
                ? "validation-run:" + UUID.randomUUID().toString().replace("-", "")
                : request.runId();
        if (!RUN_ID.matcher(runId).matches()) {
            throw new ProposalValidationException("INVALID_RUN_ID", "validation run ID 형식이 올바르지 않습니다.");
        }
        if (request.validatorVersion() == null || !VALIDATOR_VERSION.matcher(request.validatorVersion()).matches()) {
            throw new ProposalValidationException("INVALID_VALIDATOR_VERSION", "validator version 형식이 올바르지 않습니다.");
        }
        Instant startedAt = clock.instant();
        try {
            return transaction.execute(status -> doValidate(request, runId, startedAt));
        } catch (ProposalValidationException exception) {
            if (!exception.code().equals("RUN_ID_CONFLICT")) {
                recordFailure(request, runId, startedAt, exception.code());
            }
            throw exception;
        } catch (RuntimeException exception) {
            recordFailure(request, runId, startedAt, "VALIDATION_WRITE_FAILED");
            throw new ProposalValidationException(
                    "VALIDATION_WRITE_FAILED", "검증 결과 저장 중 DB 처리에 실패했습니다. 결과와 issue는 저장되지 않았습니다.", exception);
        }
    }

    private Result doValidate(Request request, String runId, Instant validatedAt) {
        repository.lockGeneration();
        if (repository.validationRunExists(runId)) {
            throw new ProposalValidationException("RUN_ID_CONFLICT", "이미 사용한 validation run ID입니다.");
        }
        ProposalRepository.ProposalRow proposal = repository.findProposal(request.proposalId())
                .orElseThrow(() -> new ProposalValidationException(
                        "PROPOSAL_NOT_FOUND", "변경안이 없습니다: " + request.proposalId()));
        List<ProposalValidator.ProposalItem> items = repository.findProposalItems(proposal.proposalId());

        ProposalRepository.TargetNotice target = repository
                .findVisibleNotice(proposal.familyId(), proposal.targetNoticeId(), validatedAt)
                .orElseThrow(() -> new ProposalValidationException(
                        "TARGET_NOTICE_NOT_VISIBLE", "대상 공문이 보이지 않습니다: " + proposal.targetNoticeId()));
        List<ChecklistItemContent> targetRules = repository.findLatestSucceededExtraction(target.noticeId(), validatedAt)
                .map(repository::findRules)
                .orElse(List.of());
        List<ChecklistItemContent> baseItems = repository.findItems(proposal.baseChecklistVersionId());

        // V-10: 기준 checklist가 지금도 최신 적용 일정에서 대상 시행일 전날을 덮는가. 변경안이 대체됐으면 최신이 아니다.
        List<String> leaves = repository.findVisibleScheduleLeaves(proposal.familyId(), validatedAt);
        boolean baseCurrent = !repository.proposalSuperseded(proposal.proposalId())
                && leaves.size() == 1
                && repository.findChecklistCovering(
                        leaves.getFirst(), proposal.familyId(), target.effectiveFrom().minusDays(1), validatedAt)
                .map(base -> base.versionId().equals(proposal.baseChecklistVersionId()))
                .orElse(false);

        ArrayNode refs = mapper.createArrayNode();
        List<ProposalValidator.PublicFactCheck> checks = new ArrayList<>();
        for (ProposalRepository.ReferenceRow reference : repository.findReferences(target.noticeId())) {
            checks.add(publicFactCheck(reference, validatedAt, refs));
        }

        ProposalValidator.Outcome outcome = validator.validate(new ProposalValidator.Input(
                items, targetRules, baseItems, target.effectiveFrom(), target.withdrawn(), baseCurrent,
                repository.findProductKeys(), checks));
        requireConsistent(outcome);

        String resultId = "validation:" + runId.substring("validation-run:".length());
        repository.insertValidation(resultId, proposal, request.validatorVersion(), outcome, validatedAt, refs);
        repository.insertValidationRun(
                runId, proposal.proposalId(), request.validatorVersion(), resultId,
                validatedAt, clock.instant(), "SUCCEEDED", null);
        return new Result(runId, resultId, outcome.status(), outcome.issues().size());
    }

    private ProposalValidator.PublicFactCheck publicFactCheck(
            ProposalRepository.ReferenceRow reference, Instant validatedAt, ArrayNode refs) {
        // 같은 검증 작업의 기준 시각(validatedAt)으로 현재 공개 근거를 평가한다.
        PublicProductObservedState state = publicProducts.evaluateAt(reference.productKey(), validatedAt, validatedAt);
        Long publicValue = null;
        ObjectNode ref = refs.addObject();
        ref.put("product_key", reference.productKey());
        ref.put("fact_key", reference.factKey());
        ref.put("subject_type", reference.subjectType());
        ref.put("confirmation_allowed", state.publicEvidenceConfirmationAllowed());
        ref.put("freshness_status", state.freshnessStatus().name());
        ref.put("as_of", validatedAt.toString());
        if (state.confirmedObservation() != null) {
            ref.put("observation_id", state.confirmedObservation().observationId());
        }
        if (state.terms() != null) {
            ref.put("product_terms_version_id", state.terms().productTermsVersionId());
            for (PublicProductObservedState.Fact fact : state.terms().facts()) {
                if (fact.factKey().equals(reference.factKey()) && fact.subjectType().equals(reference.subjectType())) {
                    publicValue = fact.integerValue();
                    ref.put("fact_id", fact.factId());
                }
            }
        }
        if (state.evidence() != null) {
            ref.put("version_evidence_id", state.evidence().versionEvidenceId());
        }
        return new ProposalValidator.PublicFactCheck(
                reference.productKey(), reference.factKey(), reference.subjectType(), reference.unit(),
                reference.expectedValue(), reference.evidenceRequirement(),
                state.publicEvidenceConfirmationAllowed(), publicValue, state.confirmationBlockingReasons());
    }

    /** 저장 전 서비스 검사. DB의 복합 FK, CHECK와 commit 시점 trigger가 같은 조건을 다시 강제한다. */
    static void requireConsistent(ProposalValidator.Outcome outcome) {
        long fails = outcome.issues().stream().filter(issue -> issue.severity() == ProposalValidator.Severity.FAIL).count();
        long warns = outcome.issues().stream().filter(issue -> issue.severity() == ProposalValidator.Severity.WARN).count();
        ProposalValidator.Status expected = fails > 0 ? ProposalValidator.Status.FAIL
                : warns > 0 ? ProposalValidator.Status.WARN : ProposalValidator.Status.PASS;
        if (outcome.status() != expected) {
            throw new ProposalValidationException(
                    "VALIDATION_INCONSISTENT", "검증 상태와 issue 심각도가 어긋납니다: " + outcome.status() + " vs " + expected);
        }
    }

    private void recordFailure(Request request, String runId, Instant startedAt, String errorCode) {
        try {
            transaction.executeWithoutResult(status -> repository.insertValidationRun(
                    runId, request.proposalId(), request.validatorVersion(), null,
                    startedAt, clock.instant(), "FAILED", errorCode));
        } catch (RuntimeException auditFailure) {
            throw new ProposalValidationException(
                    "FAILURE_AUDIT_WRITE_FAILED",
                    "검증 실패 기록을 저장할 수 없습니다 (원래 실패: " + errorCode + ")",
                    auditFailure);
        }
    }
}
