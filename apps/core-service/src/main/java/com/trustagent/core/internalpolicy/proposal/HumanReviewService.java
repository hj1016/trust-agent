package com.trustagent.core.internalpolicy.proposal;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * 사람 검토 결정. 승인(APPROVE)은 결정 기록, HUMAN_REVIEW 출처 승인 checklist, 적용 일정 revision을 한 트랜잭션으로 발행한다.
 * 수정(MODIFY)은 새 변경안 revision만 만들고, 반려(REJECT)는 결정 기록만 남긴다. 결정은 현재 시각 기준이며 과거 knownAt 입력이 없다.
 * 합성 검수자 ID를 쓰는 test/demo 기능이며 실제 인증 체계가 아니다.
 */
public final class HumanReviewService {

    public enum Decision { APPROVE, MODIFY, REJECT }

    public record Request(
            String proposalId,
            String validationResultId,
            Decision decision,
            String reviewerId,
            String reason,
            List<ChecklistItemContent> revisedRules,
            String runId) {}

    public record Result(
            String runId,
            String decisionId,
            Decision decision,
            String approvedChecklistVersionId,
            String scheduleRevisionId,
            String revisionProposalId) {}

    static final String HUMAN_REVISION_GENERATOR = "human-revision-v1";
    private static final Pattern RUN_ID = Pattern.compile("^review-run:[a-f0-9]{32}$");
    private static final Pattern REVIEWER_ID = Pattern.compile("^[A-Z][A-Z0-9-]+$");

    private final ProposalRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration maxValidationAge;
    private final ObjectMapper mapper;
    private final CanonicalJsonHasher hasher;
    private final ChecklistChangeProposalGenerator generator;

    public HumanReviewService(
            JdbcClient jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock, Duration maxValidationAge) {
        this.repository = new ProposalRepository(jdbc, mapper);
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
        this.maxValidationAge = maxValidationAge;
        this.mapper = mapper;
        this.hasher = new CanonicalJsonHasher(mapper);
        this.generator = new ChecklistChangeProposalGenerator(mapper);
    }

    public Result decide(Request request) {
        String runId = request.runId() == null || request.runId().isBlank()
                ? "review-run:" + UUID.randomUUID().toString().replace("-", "")
                : request.runId();
        if (!RUN_ID.matcher(runId).matches()) {
            throw new HumanReviewException("INVALID_RUN_ID", "review run ID 형식이 올바르지 않습니다.");
        }
        if (request.decision() == null) {
            throw new HumanReviewException("INVALID_DECISION", "결정 종류(APPROVE, MODIFY, REJECT)가 필요합니다.");
        }
        if (request.reviewerId() == null || !REVIEWER_ID.matcher(request.reviewerId()).matches()) {
            throw new HumanReviewException("INVALID_REVIEWER_ID", "검수자 ID 형식이 올바르지 않습니다.");
        }
        Instant startedAt = clock.instant();
        try {
            return transaction.execute(status -> doDecide(request, runId, startedAt));
        } catch (HumanReviewException exception) {
            if (!exception.code().equals("RUN_ID_CONFLICT")) {
                recordFailure(request, runId, startedAt, exception.code());
            }
            throw exception;
        } catch (RuntimeException exception) {
            String code = isUniqueViolation(exception) ? "SCHEDULE_CONFLICT" : "REVIEW_WRITE_FAILED";
            recordFailure(request, runId, startedAt, code);
            throw new HumanReviewException(code,
                    "결정 저장 중 DB 처리에 실패했습니다. 결정, checklist, 일정 어느 것도 저장되지 않았습니다.", exception);
        }
    }

    private Result doDecide(Request request, String runId, Instant decidedAt) {
        repository.lockGeneration();
        if (repository.reviewRunExists(runId)) {
            throw new HumanReviewException("RUN_ID_CONFLICT", "이미 사용한 review run ID입니다.");
        }
        ProposalRepository.ProposalRow proposal = repository.findProposal(request.proposalId())
                .orElseThrow(() -> new HumanReviewException("PROPOSAL_NOT_FOUND", "변경안이 없습니다: " + request.proposalId()));
        repository.findDecision(proposal.proposalId()).ifPresent(existing -> {
            throw new HumanReviewException(
                    "REJECT".equals(existing.decision()) ? "PROPOSAL_REJECTED" : "PROPOSAL_ALREADY_DECIDED",
                    "이미 결정된 변경안입니다: " + existing.decision() + " (" + existing.decisionId() + ")");
        });
        if (repository.proposalSuperseded(proposal.proposalId())) {
            throw new HumanReviewException("PROPOSAL_SUPERSEDED", "새 revision으로 대체된 변경안입니다. 최신 revision을 검토하세요.");
        }
        ProposalRepository.ValidationRow validation = latestValidation(request, proposal);

        String decisionId = "review-decision:" + runId.substring("review-run:".length());
        Result result = switch (request.decision()) {
            case APPROVE -> approve(request, proposal, validation, decisionId, decidedAt);
            case MODIFY -> modify(request, proposal, validation, decisionId, decidedAt);
            case REJECT -> reject(request, proposal, validation, decisionId, decidedAt);
        };
        repository.insertReviewRun(runId, proposal.proposalId(), request.decision().name(), request.reviewerId(),
                decisionId, decidedAt, clock.instant(), "SUCCEEDED", null);
        return new Result(runId, decisionId, request.decision(), result.approvedChecklistVersionId(),
                result.scheduleRevisionId(), result.revisionProposalId());
    }

    /** 입력한 검증 결과 ID가 있으면 그 변경안의 최신 결과와 같아야 한다. 승인은 결과가 필수다. */
    private ProposalRepository.ValidationRow latestValidation(Request request, ProposalRepository.ProposalRow proposal) {
        var latest = repository.findLatestValidation(proposal.proposalId()).orElse(null);
        if (request.validationResultId() != null && !request.validationResultId().isBlank()) {
            if (latest == null || !latest.validationResultId().equals(request.validationResultId())) {
                throw new HumanReviewException("VALIDATION_RESULT_MISMATCH",
                        "검토한 검증 결과가 이 변경안의 최신 결과가 아닙니다. 최신: " + (latest == null ? "없음" : latest.validationResultId()));
            }
        }
        if (request.decision() == Decision.APPROVE && latest == null) {
            throw new HumanReviewException("VALIDATION_MISSING", "자동 검증 결과가 없는 변경안은 승인할 수 없습니다.");
        }
        return latest;
    }

    private Result approve(
            Request request, ProposalRepository.ProposalRow proposal, ProposalRepository.ValidationRow validation,
            String decisionId, Instant decidedAt) {
        if ("FAIL".equals(validation.status())) {
            throw new HumanReviewException("VALIDATION_FAILED", "자동 검증이 FAIL인 변경안은 승인할 수 없습니다.");
        }
        if ("WARN".equals(validation.status()) && blank(request.reason())) {
            throw new HumanReviewException("REASON_REQUIRED", "WARN 결과를 승인하려면 사유가 필요합니다.");
        }
        if (Duration.between(validation.validatedAt(), decidedAt).compareTo(maxValidationAge) > 0
                || validation.validatedAt().isAfter(decidedAt)) {
            throw new HumanReviewException("VALIDATION_STALE", "검증 결과가 유효 기간을 넘겼습니다. 재검증 뒤 다시 결정하세요.");
        }
        if (!validation.proposalHash().equals(proposal.afterHash())) {
            throw new HumanReviewException("PROPOSAL_HASH_MISMATCH", "검증한 내용과 변경안 내용이 다릅니다.");
        }
        ProposalRepository.TargetNotice target = repository
                .findVisibleNotice(proposal.familyId(), proposal.targetNoticeId(), decidedAt)
                .orElseThrow(() -> new HumanReviewException("TARGET_NOTICE_NOT_VISIBLE", "대상 공문이 보이지 않습니다."));
        if (target.withdrawn()) {
            throw new HumanReviewException("TARGET_NOTICE_WITHDRAWN", "철회된 공문의 변경안은 승인할 수 없습니다.");
        }
        if (repository.humanApprovalExists(target.noticeId())) {
            throw new HumanReviewException("HUMAN_APPROVAL_EXISTS", "이 공문에는 이미 사람 검토 승인 checklist가 있습니다.");
        }
        List<String> leaves = repository.findVisibleScheduleLeaves(proposal.familyId(), decidedAt);
        if (leaves.size() != 1) {
            throw new HumanReviewException("BASE_CHECKLIST_STALE", "적용 일정 leaf가 하나가 아닙니다: " + leaves.size());
        }
        String leaf = leaves.getFirst();
        boolean baseCurrent = repository
                .findChecklistCovering(leaf, proposal.familyId(), target.effectiveFrom().minusDays(1), decidedAt)
                .map(base -> base.versionId().equals(proposal.baseChecklistVersionId()))
                .orElse(false);
        if (!baseCurrent) {
            throw new HumanReviewException("BASE_CHECKLIST_STALE", "변경안의 기준 checklist가 현재 적용 일정과 다릅니다.");
        }

        // 1) 승인 checklist 항목 = 기준 항목에 변경안의 추가/수정/삭제 적용
        List<ProposalRepository.ApprovedItemRow> baseItems = repository.findItemRows(proposal.baseChecklistVersionId());
        List<ProposalValidator.ProposalItem> changes = repository.findProposalItems(proposal.proposalId());
        Map<String, String> ruleVersionIds = repository.findLatestSucceededExtraction(target.noticeId(), decidedAt)
                .map(repository::findRuleVersionIds)
                .orElse(Map.of());
        List<ProposalRepository.ApprovedItemRow> issued = issueItems(baseItems, changes, ruleVersionIds);

        String versionId = "approved-checklist:" + hex32("approved-checklist:" + decisionId);
        String scheduleId = "checklist-schedule:" + hex32("checklist-schedule:" + decisionId);
        ArrayNode itemsJson = mapper.createArrayNode();
        issued.forEach(item -> itemsJson.add(item.toJson(mapper)));
        repository.insertApprovedVersion(versionId, proposal.familyId(), target.noticeId(), decidedAt,
                hasher.canonicalize(itemsJson).sha256(), "HUMAN_REVIEW");
        int order = 0;
        for (ProposalRepository.ApprovedItemRow item : issued) {
            repository.insertApprovedItem(versionId, proposal.familyId(), order++, item,
                    hasher.canonicalize(item.toJson(mapper)).sha256());
        }

        // 2) 새 일정 revision: 기존 항목 복사 + 직전 구간 종료일 제한 + 새 구간
        List<ProposalRepository.ScheduleEntryRow> entries = repository.findScheduleEntries(leaf);
        List<ProposalRepository.ScheduleEntryRow> next = new ArrayList<>();
        LocalDate newFrom = target.effectiveFrom();
        for (ProposalRepository.ScheduleEntryRow entry : entries) {
            if (!entry.effectiveFrom().isBefore(newFrom)) {
                throw new HumanReviewException("SCHEDULE_CONFLICT",
                        "새 시행일(" + newFrom + ") 이후에 시작하는 일정 구간이 이미 있습니다: " + entry.approvedChecklistVersionId());
            }
            LocalDate to = entry.effectiveTo() == null || entry.effectiveTo().isAfter(newFrom) ? newFrom : entry.effectiveTo();
            next.add(new ProposalRepository.ScheduleEntryRow(entry.approvedChecklistVersionId(), entry.effectiveFrom(), to));
        }
        next.add(new ProposalRepository.ScheduleEntryRow(versionId, newFrom, null));
        ArrayNode entriesJson = mapper.createArrayNode();
        next.forEach(entry -> entriesJson.add(entry.toJson(mapper)));
        repository.insertScheduleRevision(scheduleId, proposal.familyId(), leaf, decidedAt,
                hasher.canonicalize(entriesJson).sha256());
        int entryOrder = 0;
        for (ProposalRepository.ScheduleEntryRow entry : next) {
            repository.insertScheduleEntry(scheduleId, proposal.familyId(), entryOrder++, entry,
                    hasher.canonicalize(entry.toJson(mapper)).sha256());
        }

        // 3) 결정 기록
        repository.insertDecision(decisionId, proposal.proposalId(), validation.validationResultId(), proposal.afterHash(),
                "APPROVE", request.reviewerId(), blank(request.reason()) ? null : request.reason(), decidedAt,
                versionId, scheduleId, null);
        return new Result(null, decisionId, Decision.APPROVE, versionId, scheduleId, null);
    }

    private Result modify(
            Request request, ProposalRepository.ProposalRow proposal, ProposalRepository.ValidationRow validation,
            String decisionId, Instant decidedAt) {
        if (blank(request.reason())) {
            throw new HumanReviewException("REASON_REQUIRED", "수정 결정에는 사유가 필요합니다.");
        }
        if (request.revisedRules() == null || request.revisedRules().isEmpty()) {
            throw new HumanReviewException("REVISED_ITEMS_REQUIRED", "수정 결정에는 고친 항목 내용이 필요합니다.");
        }
        List<ChecklistItemContent> baseItems = repository.findItems(proposal.baseChecklistVersionId());
        ChecklistChangeProposalGenerator.Proposal revision = generator.generate(
                proposal.baseChecklistVersionId(), baseItems, proposal.targetNoticeId(),
                request.revisedRules(), HUMAN_REVISION_GENERATOR);
        if (revision.proposalId().equals(proposal.proposalId()) || repository.proposalExists(revision.proposalId())) {
            throw new HumanReviewException("REVISION_ALREADY_EXISTS", "같은 내용의 변경안이 이미 있습니다: " + revision.proposalId());
        }
        repository.insertProposalRevision(revision, proposal.familyId(), decidedAt, proposal.proposalId(), request.reason());
        repository.insertDecision(decisionId, proposal.proposalId(),
                validation == null ? null : validation.validationResultId(), proposal.afterHash(),
                "MODIFY", request.reviewerId(), request.reason(), decidedAt, null, null, revision.proposalId());
        return new Result(null, decisionId, Decision.MODIFY, null, null, revision.proposalId());
    }

    private Result reject(
            Request request, ProposalRepository.ProposalRow proposal, ProposalRepository.ValidationRow validation,
            String decisionId, Instant decidedAt) {
        if (blank(request.reason())) {
            throw new HumanReviewException("REASON_REQUIRED", "반려 결정에는 사유가 필요합니다.");
        }
        repository.insertDecision(decisionId, proposal.proposalId(),
                validation == null ? null : validation.validationResultId(), proposal.afterHash(),
                "REJECT", request.reviewerId(), request.reason(), decidedAt, null, null, null);
        return new Result(null, decisionId, Decision.REJECT, null, null, null);
    }

    /** 기준 항목 순서를 유지하고 수정은 자리에서 바꾸며 삭제는 빼고 추가는 뒤에 붙인다. */
    static List<ProposalRepository.ApprovedItemRow> issueItems(
            List<ProposalRepository.ApprovedItemRow> baseItems,
            List<ProposalValidator.ProposalItem> changes,
            Map<String, String> ruleVersionIds) {
        Map<String, ProposalValidator.ProposalItem> byKey = new LinkedHashMap<>();
        changes.forEach(change -> byKey.put(change.ruleKey(), change));
        List<ProposalRepository.ApprovedItemRow> issued = new ArrayList<>();
        for (ProposalRepository.ApprovedItemRow base : baseItems) {
            ProposalValidator.ProposalItem change = byKey.remove(base.ruleKey());
            if (change == null) {
                issued.add(base);
            } else if ("MODIFY".equals(change.changeType())) {
                issued.add(ProposalRepository.ApprovedItemRow.fromContent(change.after(), ruleVersionIds.get(base.ruleKey())));
            } else if (!"REMOVE".equals(change.changeType())) {
                throw new HumanReviewException("PROPOSAL_INTEGRITY_VIOLATION", "기준 항목에 추가(ADD)가 적용될 수 없습니다: " + base.ruleKey());
            }
        }
        for (ProposalValidator.ProposalItem change : byKey.values()) {
            if (!"ADD".equals(change.changeType())) {
                throw new HumanReviewException("PROPOSAL_INTEGRITY_VIOLATION", "기준에 없는 항목의 변경 종류가 ADD가 아닙니다: " + change.ruleKey());
            }
            issued.add(ProposalRepository.ApprovedItemRow.fromContent(change.after(), ruleVersionIds.get(change.ruleKey())));
        }
        return issued;
    }

    private void recordFailure(Request request, String runId, Instant startedAt, String errorCode) {
        try {
            transaction.executeWithoutResult(status -> repository.insertReviewRun(
                    runId, request.proposalId(), request.decision() == null ? "UNKNOWN" : request.decision().name(),
                    request.reviewerId() == null ? "UNKNOWN" : request.reviewerId(), null,
                    startedAt, clock.instant(), "FAILED", errorCode));
        } catch (RuntimeException auditFailure) {
            throw new HumanReviewException("FAILURE_AUDIT_WRITE_FAILED",
                    "결정 실패 기록을 저장할 수 없습니다 (원래 실패: " + errorCode + ")", auditFailure);
        }
    }

    private static boolean isUniqueViolation(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String hex32(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
