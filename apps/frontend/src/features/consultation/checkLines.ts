import type { ApprovedItem } from "@/lib/types";

/**
 * 승인 Checklist 항목 하나에 대해, 화면이 이미 가진 자료(상담 건 상품, 업무일, 항목의 구조화 값)로 기계적으로 대조할 수 있는 것만
 * '자료로 확인된 사실'로, 나머지 조건·예외는 원문 그대로 '추가 확인 필요'로 나눈다.
 * 원칙: 원문에 없는 기준을 만들거나 해석하지 않는다. 확인할 값이 없으면 확인 완료로 표시하지 않는다.
 * 이 결과는 대출 가능 여부나 적용 승인 판단이 아니다.
 */
export type CheckLine = { kind: "confirmed" | "needs-check" | "mismatch"; text: string };

export function deriveChecks(item: ApprovedItem, context: { productKey: string; productName?: string; businessDate: string }): CheckLine[] {
  const change = item.structuredChange;
  if (!change) {
    return [{ kind: "needs-check", text: "시스템이 대조할 수 있는 구조화 값이 없는 항목입니다. 원문 근거를 보고 직원이 확인합니다." }];
  }
  const lines: CheckLine[] = [];
  const productLabel = context.productName ?? context.productKey;
  const products = Array.isArray(change.applicable_product_keys) ? (change.applicable_product_keys as string[]) : null;
  if (products) {
    lines.push(products.includes(context.productKey)
      ? { kind: "confirmed", text: `적용 상품 일치: 이 상담 건 상품(${productLabel})이 공문 적용 상품에 포함됩니다.` }
      : { kind: "mismatch", text: `적용 상품 불일치: 이 상담 건 상품(${productLabel})이 공문 적용 상품 목록에 없습니다.` });
  }
  if (typeof change.effective_on === "string") {
    lines.push(context.businessDate >= change.effective_on
      ? { kind: "confirmed", text: `업무일 기준 시행 중: 업무일 ${context.businessDate}은 시행일 ${change.effective_on} 이후입니다.` }
      : { kind: "mismatch", text: `시행 전: 업무일 ${context.businessDate}은 시행일 ${change.effective_on} 이전입니다.` });
  }
  for (const condition of Array.isArray(change.conditions) ? change.conditions : []) {
    lines.push({ kind: "needs-check", text: `조건 충족 여부: ${condition}` });
  }
  for (const exception of Array.isArray(change.exceptions) ? change.exceptions : []) {
    lines.push({ kind: "needs-check", text: `예외 해당 여부: ${exception}` });
  }
  if (!lines.some((line) => line.kind === "needs-check")) {
    lines.push({ kind: "needs-check", text: "항목 지시에 따라 직원이 원문 근거를 확인합니다." });
  }
  return lines;
}
