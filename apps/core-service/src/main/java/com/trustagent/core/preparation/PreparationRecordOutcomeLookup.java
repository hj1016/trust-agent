package com.trustagent.core.preparation;

import com.trustagent.core.grant.GrantService;
import com.trustagent.core.grant.RecordOutcomeLookup;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 업무 DB의 준비안·실행 기록으로 grant 기록의 커밋 여부를 확인한다. 읽기만 한다. */
@Component
class PreparationRecordOutcomeLookup implements RecordOutcomeLookup {

    private final JdbcClient jdbc;

    PreparationRecordOutcomeLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> committedRun(String runId) {
        if (runId == null) return Optional.empty();
        return jdbc.sql("select run_id from consultation_preparation_run where run_id = :run and outcome in ('RECORDED', 'ALREADY_RECORDED')")
                .param("run", runId).query(String.class).optional();
    }

    @Override
    public Optional<String> committedForGrant(GrantService.Grant grant) {
        Optional<String> run = committedRun(grant.recordRunId());
        if (run.isPresent() || grant.recordPreparationId() == null) {
            return run;
        }
        // 성공 실행 기록이 없지만 준비안은 커밋된 경우(실행 기록 쓰기가 커밋 뒤 실패): 같은 상담 건·발급 뒤 기록이면 이 grant의 기록으로 본다.
        boolean committed = jdbc.sql("""
                select count(*) from consultation_preparation
                where preparation_id = :preparation and consultation_id = :consultation and recorded_at >= :issued
                """).param("preparation", grant.recordPreparationId()).param("consultation", grant.consultationId())
                .param("issued", grant.issuedAt().atOffset(ZoneOffset.UTC)).query(Integer.class).single() > 0;
        return committed ? Optional.ofNullable(grant.recordRunId()) : Optional.empty();
    }
}
