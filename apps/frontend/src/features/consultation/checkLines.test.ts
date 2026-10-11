import { describe, expect, it } from "vitest";
import { deriveChecks } from "./checkLines";
import type { ApprovedItem } from "@/lib/types";

const item = (change: ApprovedItem["structuredChange"]): ApprovedItem => ({ order: 0, ruleKey: "K", instruction: "지시", evidenceRequired: true, structuredChange: change, sourceRuleVersionId: "r" });
const fee = item({ after_value: "0.8", unit: "PERCENT", effective_on: "2026-10-01", applicable_product_keys: ["kb-seller-loan"],
  conditions: ["기업 고객의 기업여신 상담 건", "2026-10-01 이후 중도상환"], exceptions: ["별도 약정으로 수수료 면제가 확인된 경우"] });

describe("deriveChecks", () => {
  it("confirms only mechanical matches and leaves conditions and exceptions as needs-check in original wording", () => {
    const lines = deriveChecks(fee, { productKey: "kb-seller-loan", productName: "KB 셀러론", businessDate: "2026-10-10" });
    expect(lines.filter((l) => l.kind === "confirmed").map((l) => l.text)).toEqual([
      "적용 상품 일치: 이 상담 건 상품(KB 셀러론)이 공문 적용 상품에 포함됩니다.",
      "업무일 기준 시행 중: 업무일 2026-10-10은 시행일 2026-10-01 이후입니다.",
    ]);
    expect(lines.filter((l) => l.kind === "needs-check").map((l) => l.text)).toEqual([
      "조건 충족 여부: 기업 고객의 기업여신 상담 건",
      "조건 충족 여부: 2026-10-01 이후 중도상환",
      "예외 해당 여부: 별도 약정으로 수수료 면제가 확인된 경우",
    ]);
  });

  it("reports mismatches instead of confirming, and never confirms items without structured values", () => {
    const other = deriveChecks(fee, { productKey: "other-loan", businessDate: "2026-09-30" });
    expect(other.filter((l) => l.kind === "mismatch")).toHaveLength(2);
    expect(other.some((l) => l.kind === "confirmed")).toBe(false);
    const none = deriveChecks(item(null), { productKey: "kb-seller-loan", businessDate: "2026-10-10" });
    expect(none).toHaveLength(1);
    expect(none[0].kind).toBe("needs-check");
  });
});
