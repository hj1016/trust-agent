import { describe, expect, it } from "vitest";
import { reasonText } from "./messages";
import { describeChange } from "@/features/consultation/structured";

describe("messages", () => {
  it("maps known Core reason codes and never invents text for unknown codes", () => {
    expect(reasonText("HUMAN_REVIEW_PENDING")).toContain("검토 대기");
    expect(reasonText("SOMETHING_NEW")).toBe("설명이 등록되지 않은 사유 코드입니다: SOMETHING_NEW");
  });

  it("describes structured changes from Core values only", () => {
    expect(describeChange({ before_value: "1.2", after_value: "0.8", unit: "PERCENT", effective_on: "2026-10-01", conditions: ["A"], exceptions: [] }))
      .toEqual(["변경 값: 1.2% → 0.8%", "시행일: 2026-10-01", "조건: A"]);
    expect(describeChange({ after_value: true, unit: "BOOLEAN", effective_on: "2026-10-01" })).toEqual(["시행일: 2026-10-01"]);
    expect(describeChange(null)).toEqual([]);
  });
});
