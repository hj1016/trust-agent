package com.trustagent.core.internalpolicy.query;

import com.trustagent.core.internalpolicy.BusinessTimePolicy;
import com.trustagent.core.internalpolicy.InternalChecklistUsePolicy;
import com.trustagent.core.internalpolicy.InternalChecklistUsePolicy.ChecklistStatus;
import com.trustagent.core.internalpolicy.InternalChecklistUsePolicy.NoticeStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class InternalPolicyApplicableService {

    private static final String DATASET_CLASS = "SYNTHETIC_INTERNAL";
    private static final String DISCLAIMER =
            "프로젝트 시연을 위해 생성한 합성 공문이며 실제 KB 내부자료가 아닙니다.";

    private final InternalPolicyApplicableRepository repository;
    private final InternalChecklistUsePolicy policy;
    private final BusinessTimePolicy businessTimePolicy;
    private final com.trustagent.core.internalpolicy.InternalValidationPolicyProperties validationPolicy;
    private final com.trustagent.core.publicproduct.query.PublicProductObservedStateService publicProducts;
    private final Clock clock;

    InternalPolicyApplicableService(
            InternalPolicyApplicableRepository repository,
            InternalChecklistUsePolicy policy,
            BusinessTimePolicy businessTimePolicy,
            com.trustagent.core.internalpolicy.InternalValidationPolicyProperties validationPolicy,
            com.trustagent.core.publicproduct.query.PublicProductObservedStateService publicProducts,
            Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.businessTimePolicy = businessTimePolicy;
        this.validationPolicy = validationPolicy;
        this.publicProducts = publicProducts;
        this.clock = clock;
    }

    /** TASK-008 Tool API가 같은 판단을 재사용한다. 사용 허용 여부는 여기서 결정되고 Tool은 줄여 전달한다. */
    public InternalPolicyApplicableState get(String familyId, String requestedBusinessDate, String requestedKnownAt) {
        Instant evaluatedAt = clock.instant();
        LocalDate businessDate = parseBusinessDate(requestedBusinessDate);
        Instant knownAt = parseKnownAt(requestedKnownAt, evaluatedAt);
        if (knownAt.isAfter(evaluatedAt)) {
            throw new InternalPolicyQueryException(
                    "FUTURE_KNOWN_AT_NOT_ALLOWED",
                    "knownAt은 요청 평가 시각보다 미래일 수 없습니다.");
        }
        if (!repository.familyExists(familyId)) {
            throw new InternalPolicyQueryException(
                    "POLICY_FAMILY_NOT_FOUND",
                    "등록되지 않은 내부 정책 family입니다.");
        }

        LocalDate evaluatedBusinessDate = businessTimePolicy.businessDate(evaluatedAt);
        boolean futureBusinessDate = businessDate.isAfter(evaluatedBusinessDate);
        boolean historicalKnownAt = knownAt.isBefore(evaluatedAt);
        List<InternalPolicyApplicableRepository.NoticeRow> knownNotices =
                repository.findKnownNotices(familyId, knownAt);
        NoticeSelection selection = selectNotice(knownNotices, businessDate);

        EnumSet<ChecklistStatus> checklistConditions = EnumSet.noneOf(ChecklistStatus.class);
        List<String> blockingReasons = new ArrayList<>();
        List<String> warningReasons = new ArrayList<>();
        List<InternalPolicyApplicableState.Rule> rules = List.of();
        InternalPolicyApplicableState.ApprovedChecklist checklist = null;
        String validatedProposalId = null;
        String validationResultId = null;
        boolean approvedByHuman = false;
        boolean publicEvidenceConfirmed = false;

        addTimeReasons(historicalKnownAt, futureBusinessDate, blockingReasons);
        addNoticeReasons(selection, blockingReasons);

        if (selection.selected() == null) {
            checklistConditions.add(ChecklistStatus.UNAVAILABLE);
        } else {
            var selected = selection.selected();
            // TASK-007: 일정 구간이 업무일을 덮지만 그 checklist가 선택된 공문의 것이 아니면 따로 알린다
            // (예: 새 공문이 선택됐는데 승인 checklist는 이전 공문용). 상태는 아래 추출·변경안·검증 단계가 정한다.
            if (coveredByOtherNoticeChecklist(familyId, selected.noticeId(), businessDate, knownAt)) {
                blockingReasons.add("APPROVED_CHECKLIST_NOTICE_MISMATCH");
            }
            if (selected.effectiveFrom().isBefore(selected.receivedBusinessDate())) {
                warningReasons.add("RETROACTIVE_NOTICE");
            }
            Optional<InternalPolicyApplicableRepository.ExtractionRow> extraction =
                    repository.findLatestExtraction(selected.noticeId(), knownAt);
            if (extraction.isEmpty()) {
                checklistConditions.add(ChecklistStatus.PENDING_EXTRACTION);
                blockingReasons.add("POLICY_EXTRACTION_PENDING");
            } else if (!"SUCCEEDED".equals(extraction.orElseThrow().status())) {
                checklistConditions.add(ChecklistStatus.PENDING_EXTRACTION);
                blockingReasons.add("POLICY_EXTRACTION_FAILED");
            } else {
                rules = repository.findRules(extraction.orElseThrow().extractionAttemptId());
                checklist = findChecklist(familyId, selected.noticeId(), businessDate, knownAt).orElse(null);
                if (checklist == null) {
                    // TASK-006: knownAt까지 보이는 최신 변경안의 최신 자동 검증 결과로 상태를 정한다.
                    // 어떤 결과도 사람 승인을 대신하지 않으므로 사용 허용은 여전히 false다.
                    var proposal = repository.findLatestVisibleProposal(familyId, selected.noticeId(), knownAt);
                    var reject = proposal.flatMap(row -> repository.findVisibleReject(row.proposalId(), knownAt));
                    var validation = reject.isPresent() ? Optional.<InternalPolicyApplicableRepository.ValidationRow>empty()
                            : proposal.flatMap(row -> repository.findLatestVisibleValidation(row.proposalId(), knownAt));
                    if (reject.isPresent()) {
                        // TASK-007: 반려된 변경안은 이전 검사 통과 결과로 검토 대기에 되돌아가지 않는다.
                        checklistConditions.add(ChecklistStatus.UNAVAILABLE);
                        blockingReasons.add("PROPOSAL_REJECTED");
                    } else if (validation.isEmpty()) {
                        checklistConditions.add(ChecklistStatus.PENDING_VALIDATION);
                        blockingReasons.add("CHECKLIST_VALIDATION_PENDING");
                    } else {
                        var result = validation.orElseThrow();
                        validatedProposalId = proposal.orElseThrow().proposalId();
                        validationResultId = result.validationResultId();
                        if ("FAIL".equals(result.status())) {
                            checklistConditions.add(ChecklistStatus.VALIDATION_FAILED);
                            blockingReasons.add("VALIDATION_FAILED");
                        } else if (!policy.validationFresh(
                                result.validatedAt(), evaluatedAt, validationPolicy.maxValidationAge())) {
                            checklistConditions.add(ChecklistStatus.VALIDATION_STALE);
                            blockingReasons.add("VALIDATION_STALE");
                        } else {
                            checklistConditions.add(ChecklistStatus.PENDING_REVIEW);
                            blockingReasons.add("HUMAN_REVIEW_PENDING");
                        }
                    }
                } else {
                    checklistConditions.add(ChecklistStatus.AVAILABLE);
                    // TASK-007: 사람 결정이 있는 HUMAN_REVIEW checklist만 사용 허용 후보다.
                    // 검증 유효 기간은 승인 시점에 확인했고(HumanReviewService), 승인 뒤 기간 경과만으로 만료시키지 않는다.
                    if ("FIXTURE".equals(checklist.origin())) {
                        blockingReasons.add("FIXTURE_CHECKLIST_NOT_APPROVED");
                    } else if (checklist.decisionId() == null) {
                        blockingReasons.add("HUMAN_DECISION_MISSING");
                    } else {
                        approvedByHuman = true;
                    }
                    // 공개 근거: 선택된 공문의 참조가 있으면 조회 시점 기준으로 확인한다. 참조가 없으면 해당 없음(확인된 것으로 본다).
                    publicEvidenceConfirmed = evaluatePublicEvidence(
                            selected.noticeId(), knownAt, historicalKnownAt, blockingReasons, warningReasons);
                }
            }
        }

        var policyResult = policy.evaluate(new InternalChecklistUsePolicy.Input(
                EnumSet.of(selection.status()),
                checklistConditions,
                historicalKnownAt,
                futureBusinessDate,
                approvedByHuman,
                publicEvidenceConfirmed,
                checklist != null));

        return new InternalPolicyApplicableState(
                familyId,
                DATASET_CLASS,
                true,
                DISCLAIMER,
                businessDate,
                knownAt,
                evaluatedAt,
                evaluatedBusinessDate,
                futureBusinessDate,
                historicalKnownAt,
                policyResult.noticeStatus(),
                policyResult.checklistStatus(),
                List.copyOf(new LinkedHashSet<>(blockingReasons)),
                List.copyOf(new LinkedHashSet<>(warningReasons)),
                policyResult.internalChecklistUseAllowed(),
                selection.candidateIds(),
                toNotice(selection.selected()),
                rules,
                checklist,
                validatedProposalId,
                validationResultId);
    }

    private Optional<InternalPolicyApplicableState.ApprovedChecklist> findChecklist(
            String familyId,
            String noticeId,
            LocalDate businessDate,
            Instant knownAt) {
        List<InternalPolicyApplicableRepository.ScheduleRevisionRow> leaves =
                repository.findVisibleScheduleLeaves(familyId, knownAt);
        if (leaves.size() > 1) {
            throw new IllegalStateException("visible approved checklist schedule leaf가 둘 이상입니다.");
        }
        if (leaves.isEmpty()) return Optional.empty();
        return repository.findApplicableChecklist(
                leaves.getFirst().scheduleRevisionId(), noticeId, businessDate, knownAt);
    }

    private boolean coveredByOtherNoticeChecklist(
            String familyId, String noticeId, LocalDate businessDate, Instant knownAt) {
        List<InternalPolicyApplicableRepository.ScheduleRevisionRow> leaves =
                repository.findVisibleScheduleLeaves(familyId, knownAt);
        if (leaves.size() != 1) return false;
        return repository.findCoveringChecklistNoticeIds(leaves.getFirst().scheduleRevisionId(), businessDate, knownAt)
                .stream().anyMatch(id -> !id.equals(noticeId));
    }

    /** 필수 참조가 하나라도 확인되지 않으면 차단, 참고용은 경고. 과거 조회는 이미 차단이므로 확인 결과만 false로 둔다. */
    private boolean evaluatePublicEvidence(
            String noticeId, Instant knownAt, boolean historicalKnownAt,
            List<String> blockingReasons, List<String> warningReasons) {
        List<InternalPolicyApplicableRepository.ReferenceRow> references = repository.findReferences(noticeId);
        if (references.isEmpty()) return true;
        if (historicalKnownAt) return false;
        boolean confirmed = true;
        for (InternalPolicyApplicableRepository.ReferenceRow reference : references) {
            var state = publicProducts.get(reference.productKey(), knownAt.toString());
            if (state.publicEvidenceConfirmationAllowed()) continue;
            if ("REQUIRED".equals(reference.evidenceRequirement())) {
                confirmed = false;
                blockingReasons.add("PUBLIC_EVIDENCE_UNCONFIRMED");
            } else {
                warningReasons.add("INFORMATIONAL_PUBLIC_EVIDENCE_UNCONFIRMED");
            }
        }
        return confirmed;
    }

    private static NoticeSelection selectNotice(
            List<InternalPolicyApplicableRepository.NoticeRow> notices,
            LocalDate businessDate) {
        List<InternalPolicyApplicableRepository.NoticeRow> inWindow = notices.stream()
                .filter(notice -> !businessDate.isBefore(notice.effectiveFrom()))
                .filter(notice -> notice.effectiveTo() == null || businessDate.isBefore(notice.effectiveTo()))
                .toList();
        List<InternalPolicyApplicableRepository.NoticeRow> candidates = inWindow.stream()
                .filter(notice -> !notice.withdrawn())
                .toList();
        if (candidates.isEmpty()) {
            if (!inWindow.isEmpty()) {
                return new NoticeSelection(NoticeStatus.WITHDRAWN, null, ids(inWindow));
            }
            if (notices.stream().anyMatch(notice -> businessDate.isBefore(notice.effectiveFrom()))) {
                return new NoticeSelection(NoticeStatus.NOT_YET_EFFECTIVE, null, List.of());
            }
            return new NoticeSelection(NoticeStatus.NO_APPLICABLE_NOTICE, null, List.of());
        }
        if (candidates.size() == 1) {
            return new NoticeSelection(NoticeStatus.SELECTED, candidates.getFirst(), ids(candidates));
        }

        Map<String, InternalPolicyApplicableRepository.NoticeRow> byId = new HashMap<>();
        Set<String> supersededIds = new java.util.HashSet<>();
        candidates.forEach(candidate -> {
            byId.put(candidate.noticeId(), candidate);
            if (candidate.supersedesNoticeId() != null && byCandidateId(candidates, candidate.supersedesNoticeId())) {
                supersededIds.add(candidate.supersedesNoticeId());
            }
        });
        List<InternalPolicyApplicableRepository.NoticeRow> leaves = candidates.stream()
                .filter(candidate -> !supersededIds.contains(candidate.noticeId()))
                .toList();
        if (leaves.size() == 1 && chainCoversEveryCandidate(leaves.getFirst(), byId)) {
            return new NoticeSelection(NoticeStatus.SELECTED, leaves.getFirst(), ids(candidates));
        }
        return new NoticeSelection(NoticeStatus.AMBIGUOUS, null, ids(candidates));
    }

    private static boolean byCandidateId(
            List<InternalPolicyApplicableRepository.NoticeRow> candidates,
            String noticeId) {
        return candidates.stream().anyMatch(candidate -> candidate.noticeId().equals(noticeId));
    }

    private static boolean chainCoversEveryCandidate(
            InternalPolicyApplicableRepository.NoticeRow leaf,
            Map<String, InternalPolicyApplicableRepository.NoticeRow> candidates) {
        Set<String> visited = new java.util.HashSet<>();
        InternalPolicyApplicableRepository.NoticeRow current = leaf;
        while (current != null && visited.add(current.noticeId())) {
            current = current.supersedesNoticeId() == null
                    ? null
                    : candidates.get(current.supersedesNoticeId());
        }
        return current == null && visited.size() == candidates.size();
    }

    private static void addTimeReasons(
            boolean historicalKnownAt,
            boolean futureBusinessDate,
            List<String> blockingReasons) {
        if (historicalKnownAt) blockingReasons.add("HISTORICAL_KNOWN_AT");
        if (futureBusinessDate) blockingReasons.add("FUTURE_BUSINESS_DATE");
    }

    private static void addNoticeReasons(NoticeSelection selection, List<String> blockingReasons) {
        switch (selection.status()) {
            case SELECTED -> {
            }
            case AMBIGUOUS -> blockingReasons.add("AMBIGUOUS_EFFECTIVE_NOTICE");
            case WITHDRAWN -> blockingReasons.add("EFFECTIVE_NOTICE_WITHDRAWN");
            case NOT_YET_EFFECTIVE -> blockingReasons.add("NOTICE_NOT_YET_EFFECTIVE");
            case NO_APPLICABLE_NOTICE -> blockingReasons.add("NO_APPLICABLE_NOTICE");
        }
    }

    private static List<String> ids(List<InternalPolicyApplicableRepository.NoticeRow> notices) {
        return notices.stream().map(InternalPolicyApplicableRepository.NoticeRow::noticeId).sorted().toList();
    }

    private static InternalPolicyApplicableState.Notice toNotice(
            InternalPolicyApplicableRepository.NoticeRow notice) {
        if (notice == null) return null;
        return new InternalPolicyApplicableState.Notice(
                notice.noticeId(),
                notice.version(),
                notice.title(),
                notice.issuedOn(),
                notice.effectiveFrom(),
                notice.effectiveTo(),
                notice.supersedesNoticeId(),
                notice.receivedAt(),
                notice.receivedBusinessDate());
    }

    private static LocalDate parseBusinessDate(String value) {
        if (value == null || value.isBlank()) {
            throw new InternalPolicyQueryException(
                    "INVALID_BUSINESS_DATE",
                    "businessDate는 유효한 ISO 8601 date여야 합니다.");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InternalPolicyQueryException(
                    "INVALID_BUSINESS_DATE",
                    "businessDate는 유효한 ISO 8601 date여야 합니다.");
        }
    }

    private static Instant parseKnownAt(String value, Instant evaluatedAt) {
        if (value == null) return evaluatedAt;
        if (value.isBlank()) {
            throw new InternalPolicyQueryException(
                    "INVALID_KNOWN_AT",
                    "knownAt은 유효한 RFC 3339 instant여야 합니다.");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InternalPolicyQueryException(
                    "INVALID_KNOWN_AT",
                    "knownAt은 유효한 RFC 3339 instant여야 합니다.");
        }
    }

    private record NoticeSelection(
            NoticeStatus status,
            InternalPolicyApplicableRepository.NoticeRow selected,
            List<String> candidateIds) {
    }
}
