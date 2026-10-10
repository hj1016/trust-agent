package com.trustagent.core.grant;

import com.trustagent.core.control.ControlDataSourceConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 요청 승인(ADR-014 7·8·9·9-1항). 제어 DB에 발급·검사·사용 처리한다.
 * 검사 순서: 존재 → 만료 → workspace → 상담 ID → 공문군 범위(Tool) 또는 신청 ID(기록) → 읽기 상한(원자 증가) / 기록 단일 사용(원자 갱신).
 * 중복 기록 방지는 workspace DB의 run_id PK와 preparation_id 멱등이 맡고, 이 표는 범위와 사용 상태만 본다.
 */
@Service
public class GrantService {

    public record Grant(String grantId, String workspaceId, String consultationId, String applicationId, List<String> allowedFamilyIds,
                        LocalDate businessDate, String userId, String activeRole, Instant issuedAt, int ttlSeconds, Instant expiresAt,
                        String state, int readCalls, int readCallLimit, String recordRunId, String recordPreparationId) {}

    private static final Logger LOGGER = LoggerFactory.getLogger(GrantService.class);
    private static final java.util.Set<String> DATE_SCOPED_TOOLS = java.util.Set.of("applicable_checklist");
    private static final java.util.regex.Pattern RUN_ID = java.util.regex.Pattern.compile("^consultation-preparation-run:[a-f0-9]{32}$");
    private static final java.util.regex.Pattern PREPARATION_ID = java.util.regex.Pattern.compile("^consultation-preparation:sha256:[a-f0-9]{64}$");

    private final JdbcClient control;
    private final ObjectMapper mapper;
    private final GrantProperties properties;
    private final Clock clock;

    public GrantService(@Qualifier(ControlDataSourceConfiguration.CONTROL_JDBC_CLIENT) JdbcClient control, ObjectMapper mapper,
                        GrantProperties properties, Clock clock) {
        this.control = control;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 이 Core가 서비스하는 workspace. Tool·기록 경로는 사용자 세션이 없으므로 grant의 workspace를 이 값과 대조한다.
     * 현재는 운영 workspace 하나(main)이며 workspace별 DB 라우팅은 TASK-026이다.
     */
    public String servedWorkspace() {
        return com.trustagent.core.security.ActiveRole.DEFAULT_WORKSPACE;
    }

    public boolean required() {
        return properties.require();
    }

    /** 발급. 저장에 실패하면 AI 서비스를 부르지 않도록 503으로 끝난다. */
    public Grant issue(String workspaceId, String consultationId, String applicationId, List<String> allowedFamilyIds, LocalDate businessDate,
                       String userId, String activeRole, String traceId) {
        Instant now = clock.instant();
        int ttl = (int) properties.ttl().toSeconds();
        String grantId = "ai-grant:" + UUID.randomUUID().toString().replace("-", "");
        try {
            control.sql("""
                    insert into ai_request_grant (grant_id, workspace_id, consultation_id, application_id, allowed_family_ids, business_date, user_id,
                        active_role, issued_at, ttl_seconds, expires_at, state, read_calls, read_call_limit, consumed_run_id, updated_at)
                    values (:id, :workspace, :consultation, :application, cast(:families as jsonb), :businessDate, :user, :role, :issued, :ttl, :expires,
                        'ISSUED', 0, :limit, null, :issued)
                    """)
                    .param("id", grantId).param("workspace", workspaceId).param("consultation", consultationId).param("application", applicationId)
                    .param("families", mapper.writeValueAsString(allowedFamilyIds)).param("businessDate", businessDate).param("user", userId)
                    .param("role", activeRole).param("issued", now.atOffset(ZoneOffset.UTC)).param("ttl", ttl)
                    .param("expires", now.plusSeconds(ttl).atOffset(ZoneOffset.UTC)).param("limit", properties.readCallLimit())
                    .update();
        } catch (DataAccessException exception) {
            LOGGER.error("grant 발급 저장 실패 traceId={}", traceId, exception);
            throw new GrantException("GRANT_ISSUE_FAILED", 503, "AI 요청 승인을 저장하지 못해 요청을 보내지 않았습니다.");
        }
        return find(grantId).orElseThrow();
    }

    /**
     * Tool 호출 검사. require=false이고 헤더가 없으면 통과(로컬 CLI). 통과하면 읽기 호출 수를 원자적으로 늘린다.
     * 반환값은 사용 여부(헤더 없음이면 null).
     */
    public Optional<Grant> authorizeTool(String grantId, String consultationId, String familyId, String businessDate, String toolName,
                                         String workspaceId, String traceId) {
        if (grantId == null || grantId.isBlank()) {
            if (properties.require()) {
                throw new GrantException("GRANT_REQUIRED", 401, "AI 요청 승인(grant)이 필요합니다.");
            }
            return Optional.empty();
        }
        Grant grant = find(grantId).orElseThrow(() -> use(grantId, "TOOL", toolName, "GRANT_NOT_FOUND", traceId,
                new GrantException("GRANT_NOT_FOUND", 403, "알 수 없는 AI 요청 승인입니다.")));
        checkCommon(grant, consultationId, workspaceId, "TOOL", toolName, traceId);
        if (familyId == null || !grant.allowedFamilyIds().contains(familyId)) {
            throw use(grantId, "TOOL", toolName, "GRANT_SCOPE_MISMATCH", traceId,
                    new GrantException("GRANT_SCOPE_MISMATCH", 403, "승인된 상담 건의 공문군이 아닙니다."));
        }
        // 업무일로 조회하는 Tool(applicable_checklist)은 grant의 업무일과 같아야 한다. 생략하면 Tool이 오늘로 채우므로 grant 사용 시에는 명시를 요구한다.
        // rule_evidence는 업무일 인자가 없고 평가 시각(Core 시계) 기준이라 대조하지 않는다.
        if (DATE_SCOPED_TOOLS.contains(toolName) && !grant.businessDate().toString().equals(businessDate)) {
            throw use(grantId, "TOOL", toolName, "GRANT_SCOPE_MISMATCH", traceId,
                    new GrantException("GRANT_SCOPE_MISMATCH", 403, "승인된 업무일이 아닙니다."));
        }
        int updated = control.sql("""
                update ai_request_grant set read_calls = read_calls + 1, updated_at = :now
                where grant_id = :id and state = 'ISSUED' and read_calls < read_call_limit
                """).param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("id", grantId).update();
        if (updated == 0) {
            throw use(grantId, "TOOL", toolName, "GRANT_EXHAUSTED", traceId,
                    new GrantException("GRANT_EXHAUSTED", 403, "AI 요청 승인의 읽기 호출 상한을 넘었습니다."));
        }
        recordUse(grantId, "TOOL", toolName, "OK", traceId);
        return Optional.of(grant);
    }

    /**
     * 기록 시작: ISSUED → CONSUMING 원자 갱신. 0행이면 이미 사용 중·사용됨(409).
     * 이번 시도의 run_id와 본문의 preparation_id를 grant에 남겨, 커밋 여부가 불확실할 때 대조가 업무 DB에서 찾을 수 있게 한다.
     */
    public Optional<Grant> beginRecord(String grantId, String consultationId, String applicationId, String businessDate, String workspaceId,
                                       String runId, String preparationId, String traceId) {
        if (grantId == null || grantId.isBlank()) {
            if (properties.require()) {
                throw new GrantException("GRANT_REQUIRED", 401, "AI 요청 승인(grant)이 필요합니다.");
            }
            return Optional.empty();
        }
        Grant grant = find(grantId).orElseThrow(() -> use(grantId, "RECORD_BEGIN", null, "GRANT_NOT_FOUND", traceId,
                new GrantException("GRANT_NOT_FOUND", 403, "알 수 없는 AI 요청 승인입니다.")));
        checkCommon(grant, consultationId, workspaceId, "RECORD_BEGIN", null, traceId);
        if (applicationId == null || !grant.applicationId().equals(applicationId)) {
            throw use(grantId, "RECORD_BEGIN", null, "GRANT_SCOPE_MISMATCH", traceId,
                    new GrantException("GRANT_SCOPE_MISMATCH", 403, "승인된 상담 건의 신청이 아닙니다."));
        }
        if (!grant.businessDate().toString().equals(businessDate)) {
            throw use(grantId, "RECORD_BEGIN", null, "GRANT_SCOPE_MISMATCH", traceId,
                    new GrantException("GRANT_SCOPE_MISMATCH", 403, "승인된 업무일이 아닙니다."));
        }
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            // run_id가 없으면 커밋 여부를 대조할 수 없으므로 grant를 쓰지 않는다(기록 경로의 INVALID_REQUEST와 같은 판단).
            throw use(grantId, "RECORD_BEGIN", null, "INVALID_REQUEST", traceId,
                    new GrantException("INVALID_REQUEST", 400, "run_id 형식이 올바르지 않습니다."));
        }
        String preparation = preparationId != null && PREPARATION_ID.matcher(preparationId).matches() ? preparationId : null;
        int updated = control.sql("""
                update ai_request_grant set state = 'CONSUMING', record_run_id = :run, record_preparation_id = :preparation, updated_at = :now
                where grant_id = :id and state = 'ISSUED'
                """).param("run", runId).param("preparation", preparation)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("id", grantId).update();
        if (updated == 0) {
            throw use(grantId, "RECORD_BEGIN", null, "GRANT_CONSUMED", traceId,
                    new GrantException("GRANT_CONSUMED", 409, "이 AI 요청 승인으로는 이미 기록을 시작했거나 끝냈습니다."));
        }
        recordUse(grantId, "RECORD_BEGIN", null, "OK", traceId);
        return Optional.of(grant);
    }

    /**
     * 기록 완료: CONSUMING → CONSUMED. 업무 기록은 이미 커밋됐으므로 실패해도 응답을 바꾸지 않는다.
     * grant는 CONSUMING으로 남고(재사용 불가) 만료 뒤 대조가 업무 DB에서 run_id를 찾아 CONSUMED로 정리한다(ADR-014 9-1항 두 번째 행).
     */
    public void completeRecord(String grantId, String runId, String traceId) {
        try {
            control.sql("update ai_request_grant set state = 'CONSUMED', consumed_run_id = :run, updated_at = :now where grant_id = :id and state = 'CONSUMING'")
                    .param("run", runId).param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("id", grantId).update();
            recordUse(grantId, "RECORD_COMPLETE", null, "OK", traceId);
        } catch (RuntimeException exception) {
            LOGGER.error("grant CONSUMED 갱신 실패(업무 기록은 커밋됨, CONSUMING 유지, 대조가 정리) grant={} traceId={}", grantId, traceId, exception);
            bestEffortUse(grantId, "RECORD_COMPLETE_FAILED", "CONSUMED_UPDATE_FAILED", traceId);
        }
    }

    /**
     * 기록이 커밋 전에 거부됐음이 코드 구조상 확정된 경우에만 CONSUMING → ISSUED로 되돌린다(재시도는 새 run_id로).
     * 커밋 여부가 불확실한 실패에는 쓰지 않는다. 그때는 {@link #markUncertain}이다.
     */
    public void releaseRecord(String grantId, String outcome, String traceId) {
        try {
            control.sql("update ai_request_grant set state = 'ISSUED', record_run_id = null, record_preparation_id = null, updated_at = :now where grant_id = :id and state = 'CONSUMING'")
                    .param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("id", grantId).update();
            recordUse(grantId, "RECORD_RELEASE", null, outcome, traceId);
        } catch (RuntimeException exception) {
            // 되돌리지 못하면 CONSUMING으로 남는다. 재사용은 막히고 만료 뒤 대조가 EXPIRED로 정리한다(fail-closed).
            LOGGER.error("grant 반환 실패(CONSUMING 유지) grant={} traceId={}", grantId, traceId, exception);
        }
    }

    /** 커밋 여부를 확인하지 못한 실패. grant는 CONSUMING으로 남아 재사용되지 않고, 만료 뒤 대조가 업무 DB를 보고 정리한다. */
    public void markUncertain(String grantId, String outcome, String traceId) {
        LOGGER.warn("기록 결과 불확실: grant를 CONSUMING으로 둔다 grant={} outcome={} traceId={}", grantId, outcome, traceId);
        bestEffortUse(grantId, "RECORD_UNCERTAIN", outcome, traceId);
    }

    /**
     * 대조(ADR-014 9-1항): 만료된 CONSUMING grant를 업무 DB의 기록과 대조해 정리한다.
     * lookup은 그 grant의 기록이 커밋됐으면 run_id를 돌려준다. 있으면 CONSUMED(사후), 없으면 EXPIRED. 어느 경우에도 ISSUED로 돌리지 않는다.
     * 이 메서드와 수동 runner만 있고 주기 실행(스케줄)은 없다.
     */
    public int reconcile(java.util.function.Function<Grant, Optional<String>> runOutcomeLookup, String traceId) {
        List<Grant> stale = control.sql("select * from ai_request_grant where state = 'CONSUMING' and expires_at < :now order by expires_at, grant_id")
                .param("now", clock.instant().atOffset(ZoneOffset.UTC)).query(this::map).list();
        int settled = 0;
        for (Grant grant : stale) {
            Optional<String> runId = runOutcomeLookup.apply(grant);
            String newState = runId.isPresent() ? "CONSUMED" : "EXPIRED";
            int updated = control.sql("update ai_request_grant set state = :state, consumed_run_id = :run, updated_at = :now where grant_id = :id and state = 'CONSUMING'")
                    .param("state", newState).param("run", runId.orElse(null)).param("now", clock.instant().atOffset(ZoneOffset.UTC))
                    .param("id", grant.grantId()).update();
            if (updated == 1) {
                recordUse(grant.grantId(), "RECONCILE", null, newState, traceId);
                settled++;
            }
        }
        return settled;
    }

    public Optional<Grant> find(String grantId) {
        return control.sql("select * from ai_request_grant where grant_id = :id").param("id", grantId).query(this::map).optional();
    }

    public List<String> usesOf(String grantId) {
        return control.sql("select kind || ':' || outcome from ai_request_grant_use where grant_id = :id order by used_at, use_id")
                .param("id", grantId).query(String.class).list();
    }

    private void checkCommon(Grant grant, String consultationId, String workspaceId, String kind, String toolName, String traceId) {
        Instant now = clock.instant();
        if (!grant.state().equals("ISSUED") && kind.equals("TOOL")) {
            throw use(grant.grantId(), kind, toolName, "GRANT_CONSUMED", traceId, new GrantException("GRANT_CONSUMED", 409, "이미 기록에 사용된 AI 요청 승인입니다."));
        }
        if (!now.isBefore(grant.expiresAt())) {
            markExpired(grant.grantId());
            throw use(grant.grantId(), kind, toolName, "GRANT_EXPIRED", traceId, new GrantException("GRANT_EXPIRED", 403, "AI 요청 승인이 만료됐습니다."));
        }
        // workspace는 호출자가 비워 둘 수 없다. null이면 검사 우회가 아니라 거부다(ADR-014 8항).
        if (workspaceId == null || !grant.workspaceId().equals(workspaceId)) {
            throw use(grant.grantId(), kind, toolName, "WORKSPACE_UNAVAILABLE", traceId, new GrantException("WORKSPACE_UNAVAILABLE", 403, "승인된 workspace가 아닙니다."));
        }
        if (consultationId == null || !grant.consultationId().equals(consultationId)) {
            throw use(grant.grantId(), kind, toolName, "GRANT_SCOPE_MISMATCH", traceId, new GrantException("GRANT_SCOPE_MISMATCH", 403, "승인된 상담 건이 아닙니다."));
        }
    }

    private void markExpired(String grantId) {
        control.sql("update ai_request_grant set state = 'EXPIRED', updated_at = :now where grant_id = :id and state = 'ISSUED'")
                .param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("id", grantId).update();
    }

    private GrantException use(String grantId, String kind, String toolName, String outcome, String traceId, GrantException exception) {
        try {
            if (find(grantId).isPresent()) {
                recordUse(grantId, kind, toolName, outcome, traceId);
            }
        } catch (DataAccessException failure) {
            LOGGER.error("grant 사용 기록 실패 grant={} outcome={}", grantId, outcome, failure);
        }
        return exception;
    }

    private void bestEffortUse(String grantId, String kind, String outcome, String traceId) {
        try {
            recordUse(grantId, kind, null, outcome, traceId);
        } catch (RuntimeException failure) {
            LOGGER.error("grant 사용 기록 실패 grant={} kind={} outcome={}", grantId, kind, outcome, failure);
        }
    }

    private void recordUse(String grantId, String kind, String toolName, String outcome, String traceId) {
        control.sql("insert into ai_request_grant_use (use_id, grant_id, used_at, kind, tool_name, outcome, trace_id) values (:id, :grant, :at, :kind, :tool, :outcome, :trace)")
                .param("id", "ai-grant-use:" + UUID.randomUUID().toString().replace("-", "")).param("grant", grantId)
                .param("at", clock.instant().atOffset(ZoneOffset.UTC)).param("kind", kind).param("tool", toolName).param("outcome", outcome)
                .param("trace", traceId == null ? "unavailable" : traceId).update();
    }

    private Grant map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        List<String> families = mapper.readValue(rs.getString("allowed_family_ids"), mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        return new Grant(rs.getString("grant_id"), rs.getString("workspace_id"), rs.getString("consultation_id"), rs.getString("application_id"), families,
                rs.getObject("business_date", LocalDate.class), rs.getString("user_id"), rs.getString("active_role"),
                rs.getObject("issued_at", OffsetDateTime.class).toInstant(), rs.getInt("ttl_seconds"), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getString("state"), rs.getInt("read_calls"), rs.getInt("read_call_limit"), rs.getString("record_run_id"),
                rs.getString("record_preparation_id"));
    }
}
