package com.trustagent.core.grant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 수동 대조 명령(ADR-014 9-1항): --trust-agent.grant-reconcile.enabled=true.
 * 만료된 CONSUMING grant를 업무 DB와 대조해 CONSUMED(사후) 또는 EXPIRED로 정리한다. 주기 실행(스케줄)은 아니다.
 */
@Component
@ConditionalOnProperty(name = "trust-agent.grant-reconcile.enabled", havingValue = "true")
final class GrantReconcileRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(GrantReconcileRunner.class);

    private final GrantService grants;
    private final RecordOutcomeLookup lookup;

    GrantReconcileRunner(GrantService grants, RecordOutcomeLookup lookup) {
        this.grants = grants;
        this.lookup = lookup;
    }

    @Override
    public void run(ApplicationArguments args) {
        int settled = grants.reconcile(lookup::committedForGrant, "grant-reconcile-runner");
        LOGGER.info("GRANT_RECONCILE settled={}", settled);
    }
}
