package com.trustagent.core.search;

import java.time.LocalDate;

/** 업무 DB에서 읽은 승인 근거 한 줄(규칙 version 하나). 색인 문서의 원천이다. */
record IndexedRule(String ruleVersionId, String familyId, String noticeId, String ruleKey, String evidenceText, String jsonPointer,
                   String evidenceHash, String structuredChangeJson, String approvedChecklistVersionId, String decisionId,
                   LocalDate effectiveFrom, LocalDate effectiveTo) {
}
