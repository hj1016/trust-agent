import { describe, expect, it } from "vitest";
import { applicationStatus, consultationStatus, errorGuide, preparationStatus, recordStatus, reviewDecision, roleLabel, sectionStatus } from "./labels";

describe("Korean display labels", () => {
  it("maps the status values that are actually in use", () => {
    expect(consultationStatus("OPEN").text).toBe("진행 중");
    expect(applicationStatus("DRAFT").text).toBe("작성 중");
    expect(preparationStatus("READY").text).toBe("기준 자료 준비 완료");
    expect(preparationStatus("PARTIAL").text).toBe("일부 준비·추가 확인 필요");
    expect(preparationStatus("HOLD").text).toBe("준비 보류");
    expect(sectionStatus("READY").text).toBe("준비 완료");
    expect(reviewDecision("APPROVE").text).toBe("승인 완료");
    expect(reviewDecision("MODIFY").text).toBe("수정 후 재검토");
    expect(reviewDecision("REJECT").text).toBe("반려");
    expect(reviewDecision(null).text).toBe("검토 결정 없음");
    expect(roleLabel("STAFF").text).toBe("직원");
    expect(recordStatus("ALREADY_RECORDED").tone).toBe("ready");
  });

  it("never shows an unknown value as success or approval and keeps the original code", () => {
    for (const label of [preparationStatus("COMPLETE"), sectionStatus("DONE"), reviewDecision("AUTO_APPROVE"), recordStatus("OK"), consultationStatus("CLOSED")]) {
      expect(label.tone).toBe("neutral");
      expect(label.text).toMatch(/^확인 필요\(.+\)$/);
      expect(label.text).not.toMatch(/완료|승인/);
    }
  });

  it("explains error codes in Korean without inventing causes", () => {
    expect(errorGuide("SECTION_ON_HOLD")).toContain("보류");
    expect(errorGuide("SOMETHING_NEW")).toBe("요청을 처리하지 못했습니다.");
    expect(errorGuide("HTTP_503")).toContain("서버");
  });
});
