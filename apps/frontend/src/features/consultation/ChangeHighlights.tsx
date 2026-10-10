import { ArrowRight, CalendarCheck } from "lucide-react";
import type { ApprovedItem } from "@/lib/types";
import { formatValue } from "./structured";

/**
 * 공문 카드 맨 위의 변경 요약. 승인 항목의 구조화 값(변경 전·후 값, 단위, 시행일)을 크게 보인다.
 * 값은 Core 승인 checklist 응답 그대로이며 이름·설명을 지어내지 않는다(설명은 항목 지시 문장).
 */
export function ChangeHighlights({ items, effectiveFrom }: { items: ApprovedItem[]; effectiveFrom: string | null | undefined }) {
  const numeric = items.filter((item) => item.structuredChange && item.structuredChange.unit !== "BOOLEAN"
    && item.structuredChange.after_value !== undefined && item.structuredChange.after_value !== null);
  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-brand/40 bg-brand-soft/70 p-4" data-testid="change-highlights">
      {effectiveFrom && (
        <div className="flex items-center gap-2 text-[15px] font-semibold text-brand-ink">
          <CalendarCheck className="size-5" /> 시행일 <span className="text-lg font-extrabold">{effectiveFrom}</span>
        </div>
      )}
      {numeric.map((item) => {
        const change = item.structuredChange!;
        return (
          <div key={item.sourceRuleVersionId} className="rounded-xl bg-surface px-4 py-3">
            <p className="text-sm leading-relaxed text-ink-soft">{item.instruction}</p>
            <div className="mt-2 flex flex-wrap items-center gap-3">
              {change.before_value !== null && change.before_value !== undefined && (
                <span className="flex items-baseline gap-1.5">
                  <span className="text-sm text-ink-soft">변경 전</span>
                  <span className="text-2xl font-bold text-ink-soft line-through decoration-2" data-testid="change-before">{formatValue(change.before_value, change.unit)}</span>
                </span>
              )}
              <ArrowRight aria-hidden className="size-6 text-brand-ink" />
              <span className="flex items-baseline gap-1.5">
                <span className="text-sm text-ink-soft">변경 후</span>
                <span className="text-3xl font-extrabold text-brand-ink" data-testid="change-after">{formatValue(change.after_value, change.unit)}</span>
              </span>
              {change.effective_on && <span className="rounded-full bg-brand px-3 py-1 text-sm font-bold text-brand-ink">{change.effective_on} 시행</span>}
            </div>
          </div>
        );
      })}
    </div>
  );
}
