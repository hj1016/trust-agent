package com.trustagent.core.consultation;

import com.trustagent.core.grant.GrantService;
import com.trustagent.core.security.ActiveRole;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 상담 건(ADR-014 5항). Core가 소유하며 담당 사용자만 접근한다. 신청은 업무 DB에 등록된 합성 신청만 허용한다(A안).
 * 준비안 요청은 담당자 검사 뒤 grant를 발급해 AI 서비스에 넘긴다.
 */
@Service
public class ConsultationService {

    public record View(String consultationId, String applicationId, String companyId, String productKey, String assignedUserId,
                       String status, String workspaceId, String createdAt) {}

    private final ConsultationRepository repository;
    private final GrantService grants;
    private final Clock clock;

    public ConsultationService(JdbcClient jdbc, GrantService grants, Clock clock) {
        this.repository = new ConsultationRepository(jdbc);
        this.grants = grants;
        this.clock = clock;
    }

    public View create(String applicationId, String userId, String workspaceId, String traceId) {
        ConsultationRepository.Application application = repository.findApplication(applicationId)
                .orElseThrow(() -> new ConsultationException("APPLICATION_NOT_REGISTERED", "업무 DB에 등록된 합성 신청이 아닙니다."));
        if (repository.familiesForProduct(application.productKey()).isEmpty()) {
            throw new ConsultationException("PRODUCT_NOT_MAPPED", "승인된 공문군 매핑에 없는 상품입니다.");
        }
        ConsultationRepository.Consultation consultation = new ConsultationRepository.Consultation(
                "consultation:" + UUID.randomUUID().toString().replace("-", ""), workspaceId, applicationId, userId, "OPEN",
                clock.instant().atOffset(ZoneOffset.UTC));
        repository.insert(consultation, traceId);
        return view(consultation, application);
    }

    public List<View> mine(String userId, String workspaceId) {
        return repository.findMine(workspaceId, userId).stream().map(consultation -> view(consultation,
                repository.findApplication(consultation.applicationId()).orElseThrow())).toList();
    }

    /** 담당자가 아니면 존재 여부를 숨기기 위해 비어 있다(404). */
    public Optional<View> findOwned(String consultationId, String userId, String workspaceId) {
        return repository.find(consultationId)
                .filter(consultation -> consultation.assignedUserId().equals(userId) && consultation.workspaceId().equals(workspaceId))
                .map(consultation -> view(consultation, repository.findApplication(consultation.applicationId()).orElseThrow()));
    }

    /** 담당 상담 건에 대해 grant를 발급한다. 허용 공문군은 승인된 매핑의 상품 공문군이다. */
    public GrantService.Grant issueGrant(View consultation, LocalDate businessDate, String userId, String activeRole, String traceId) {
        List<String> families = repository.familiesForProduct(consultation.productKey());
        return grants.issue(consultation.workspaceId(), consultation.consultationId(), consultation.applicationId(), families, businessDate,
                userId, activeRole, traceId);
    }

    private static View view(ConsultationRepository.Consultation consultation, ConsultationRepository.Application application) {
        return new View(consultation.consultationId(), consultation.applicationId(), application.companyId(), application.productKey(),
                consultation.assignedUserId(), consultation.status(), consultation.workspaceId(), consultation.createdAt().toString());
    }

    public static String workspace(jakarta.servlet.http.HttpServletRequest request) {
        return ActiveRole.workspace(request);
    }
}
