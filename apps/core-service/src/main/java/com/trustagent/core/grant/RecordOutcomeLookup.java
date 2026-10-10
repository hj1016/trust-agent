package com.trustagent.core.grant;

import java.util.Optional;

/**
 * 업무 DB(workspace DB)에서 grant의 기록이 커밋됐는지 확인한다(ADR-014 9-1항). 제어 DB와 업무 DB 사이에 외래 키가 없으므로
 * grant에 남긴 run_id·preparation_id로 찾는다. 조회 실패는 예외로 알리며 호출자는 그때 grant를 CONSUMING으로 둔다.
 */
public interface RecordOutcomeLookup {

    /** 이번 시도의 run_id로 성공 실행 기록(RECORDED·ALREADY_RECORDED)이 있으면 run_id. 요청 처리 중 불확실한 실패 직후에 쓴다. */
    Optional<String> committedRun(String runId);

    /**
     * 대조용: 성공 실행 기록이 있거나, 실행 기록 쓰기가 커밋 뒤 실패한 경우를 위해 같은 상담 건의 준비안이 grant 발급 뒤 기록됐으면 run_id.
     */
    Optional<String> committedForGrant(GrantService.Grant grant);
}
