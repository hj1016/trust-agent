package com.trustagent.core.review;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 검수자 읽기 경로(TASK-017a). 역할 분리 검증용 최소 경로이며 생성·검증·결정 쓰기 경로는 TASK-027이다.
 * 변경안 ID·공문군·대상 공문·항목 수·생성 시각·결정 여부만 돌려주고 항목 본문이나 검수자 ID는 내지 않는다.
 */
@RestController
@RequestMapping("/api/v1/reviews")
public class ProposalReadController {

    public record ProposalSummary(String proposalId, String familyId, String targetNoticeId, int itemCount, OffsetDateTime createdAt, String decision) {}

    public record ProposalList(List<ProposalSummary> proposals) {}

    private final JdbcClient jdbc;

    public ProposalReadController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/proposals")
    public ProposalList proposals() {
        List<ProposalSummary> rows = jdbc.sql("""
                select p.proposal_id, p.family_id, p.target_notice_id, p.item_count, p.created_at, d.decision
                from checklist_change_proposal p
                left join human_review_decision d on d.proposal_id = p.proposal_id
                order by p.created_at desc, p.proposal_id
                """)
                .query((rs, rowNum) -> new ProposalSummary(rs.getString("proposal_id"), rs.getString("family_id"), rs.getString("target_notice_id"),
                        rs.getInt("item_count"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("decision")))
                .list();
        return new ProposalList(rows);
    }
}
