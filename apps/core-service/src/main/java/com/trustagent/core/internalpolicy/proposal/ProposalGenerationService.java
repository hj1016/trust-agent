package com.trustagent.core.internalpolicy.proposal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 새 공문 version의 구조화 rule과 직전 승인 checklist를 비교해 변경안을 만들고 append-only로 저장한다.
 * 생성은 승인이 아니다. 적용 공문 조회의 사용 허용 판단에 영향을 주지 않는다.
 */
public final class ProposalGenerationService {

    public record Request(String familyId, String targetNoticeId, String runId, String generatorVersion) {}

    public record Result(String runId, String proposalId, boolean created, int itemCount) {}

    private static final Pattern RUN_ID = Pattern.compile("^proposal-run:[a-f0-9]{32}$");
    private static final Pattern GENERATOR_VERSION = Pattern.compile("^[a-z][a-z0-9-]+$");

    private final ProposalRepository repository;
    private final ChecklistChangeProposalGenerator generator;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ProposalGenerationService(
            JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        this.repository = new ProposalRepository(jdbc, mapper);
        this.generator = new ChecklistChangeProposalGenerator(mapper);
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public Result generate(Request request) {
        String runId = request.runId() == null || request.runId().isBlank()
                ? "proposal-run:" + UUID.randomUUID().toString().replace("-", "")
                : request.runId();
        if (!RUN_ID.matcher(runId).matches()) {
            throw new ProposalGenerationException("INVALID_RUN_ID", "proposal run ID 형식이 올바르지 않습니다.");
        }
        if (request.generatorVersion() == null || !GENERATOR_VERSION.matcher(request.generatorVersion()).matches()) {
            throw new ProposalGenerationException("INVALID_GENERATOR_VERSION", "generator version 형식이 올바르지 않습니다.");
        }
        Instant startedAt = clock.instant();
        try {
            return transaction.execute(status -> doGenerate(request, runId, startedAt));
        } catch (ProposalGenerationException exception) {
            if (!exception.code().equals("RUN_ID_CONFLICT")) {
                recordFailure(request, runId, startedAt, exception.code());
            }
            throw exception;
        } catch (RuntimeException exception) {
            recordFailure(request, runId, startedAt, "PROPOSAL_GENERATION_FAILED");
            throw new ProposalGenerationException(
                    "PROPOSAL_GENERATION_FAILED", "checklist 변경안 생성 중 DB 처리에 실패했습니다.", exception);
        }
    }

    private Result doGenerate(Request request, String runId, Instant knownAt) {
        repository.lockGeneration();
        if (repository.runExists(runId)) {
            throw new ProposalGenerationException("RUN_ID_CONFLICT", "이미 사용한 proposal run ID입니다.");
        }
        ProposalRepository.TargetNotice target = repository
                .findVisibleNotice(request.familyId(), request.targetNoticeId(), knownAt)
                .filter(notice -> !notice.withdrawn())
                .orElseThrow(() -> new ProposalGenerationException(
                        "TARGET_NOTICE_NOT_VISIBLE",
                        "대상 공문이 수신되지 않았거나 철회됐습니다: " + request.targetNoticeId()));
        String extractionId = repository.findLatestSucceededExtraction(target.noticeId(), knownAt)
                .orElseThrow(() -> new ProposalGenerationException(
                        "TARGET_EXTRACTION_PENDING", "대상 공문의 구조화 결과가 없습니다: " + target.noticeId()));
        List<ChecklistItemContent> targetRules = repository.findRules(extractionId);

        List<String> leaves = repository.findVisibleScheduleLeaves(request.familyId(), knownAt);
        if (leaves.size() > 1) {
            throw new ProposalGenerationException(
                    "POLICY_INTEGRITY_VIOLATION", "visible approved checklist schedule leaf가 둘 이상입니다.");
        }
        Optional<ProposalRepository.BaseChecklist> base = leaves.isEmpty()
                ? Optional.empty()
                : repository.findChecklistCovering(
                        leaves.getFirst(), request.familyId(), target.effectiveFrom().minusDays(1), knownAt);
        ProposalRepository.BaseChecklist baseChecklist = base.orElseThrow(() -> new ProposalGenerationException(
                "NO_BASE_CHECKLIST",
                "대상 공문 시행 전날에 적용되는 승인 checklist가 없습니다: " + request.familyId()));

        ChecklistChangeProposalGenerator.Proposal proposal = generator.generate(
                baseChecklist.versionId(),
                baseChecklist.items(),
                target.noticeId(),
                targetRules,
                request.generatorVersion());

        boolean created = !repository.proposalExists(proposal.proposalId());
        if (created) {
            repository.insertProposal(proposal, request.familyId(), knownAt);
        }
        repository.insertRun(
                runId, request.familyId(), target.noticeId(), request.generatorVersion(),
                proposal.proposalId(), knownAt, clock.instant(), "SUCCEEDED", null);
        return new Result(runId, proposal.proposalId(), created, proposal.items().size());
    }

    private void recordFailure(Request request, String runId, Instant startedAt, String errorCode) {
        try {
            transaction.executeWithoutResult(status -> repository.insertRun(
                    runId, request.familyId(), request.targetNoticeId(), request.generatorVersion(),
                    null, startedAt, clock.instant(), "FAILED", errorCode));
        } catch (RuntimeException auditFailure) {
            throw new ProposalGenerationException(
                    "FAILURE_AUDIT_WRITE_FAILED",
                    "proposal 생성 실패 기록을 저장할 수 없습니다 (원래 실패: " + errorCode + ")",
                    auditFailure);
        }
    }
}
