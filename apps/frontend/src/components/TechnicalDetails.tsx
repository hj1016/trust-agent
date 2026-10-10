import { ChevronDown } from "lucide-react";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

/** 기술 식별자(규칙 키, 공문·상담 ID, 승인 version, 해시, 원문 위치)는 기본 화면에서 접어 두고 필요할 때 펼친다. 값은 Core 응답 그대로다. */
export function TechnicalDetails({ rows, label = "상세 정보" }: { rows: Array<[string, string | null | undefined]>; label?: string }) {
  const visible = rows.filter(([, value]) => value);
  if (visible.length === 0) return null;
  return (
    <Collapsible>
      <CollapsibleTrigger className="group inline-flex items-center gap-1 rounded-lg px-1 py-1 text-sm font-semibold text-ink-soft hover:text-ink">
        {label} <ChevronDown className="size-4 transition-transform group-data-[state=open]:rotate-180" />
      </CollapsibleTrigger>
      <CollapsibleContent>
        <dl className="mt-1 grid grid-cols-[max-content_minmax(0,1fr)] gap-x-4 gap-y-1 rounded-lg bg-muted/70 px-3 py-2 text-sm">
          {visible.map(([term, value]) => (
            <div key={term} className="contents">
              <dt className="text-ink-soft">{term}</dt>
              <dd className="font-mono text-[13px] break-all text-ink">{value}</dd>
            </div>
          ))}
        </dl>
      </CollapsibleContent>
    </Collapsible>
  );
}
