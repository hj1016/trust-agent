import { AlertTriangle, CircleCheck, CircleHelp } from "lucide-react";
import type { CheckLine } from "./checkLines";

/** 항목별 확인 상태: 자료로 확인된 사실과 추가 확인이 필요한 사항을 나눠 보인다. */
export function CheckStatus({ lines }: { lines: CheckLine[] }) {
  const confirmed = lines.filter((line) => line.kind === "confirmed");
  const mismatch = lines.filter((line) => line.kind === "mismatch");
  const needs = lines.filter((line) => line.kind === "needs-check");
  return (
    <div className="mt-2 grid gap-2 md:grid-cols-2" data-testid="check-status">
      <div className="rounded-lg bg-ready-soft/60 px-3 py-2">
        <p className="text-sm font-semibold text-ready">자료로 확인된 사실</p>
        {confirmed.length === 0 && mismatch.length === 0 && <p className="mt-1 text-sm text-ink-soft">없음</p>}
        <ul className="mt-1 flex flex-col gap-1">
          {confirmed.map((line) => (
            <li key={line.text} className="flex gap-1.5 text-[15px] text-ink" data-kind="confirmed"><CircleCheck className="mt-0.5 size-4 shrink-0 text-ready" />{line.text}</li>
          ))}
          {mismatch.map((line) => (
            <li key={line.text} className="flex gap-1.5 text-[15px] text-danger" data-kind="mismatch"><AlertTriangle className="mt-0.5 size-4 shrink-0" />{line.text}</li>
          ))}
        </ul>
      </div>
      <div className="rounded-lg bg-hold-soft/60 px-3 py-2">
        <p className="text-sm font-semibold text-hold">추가 확인 필요</p>
        <ul className="mt-1 flex flex-col gap-1">
          {needs.map((line) => (
            <li key={line.text} className="flex gap-1.5 text-[15px] text-ink" data-kind="needs-check"><CircleHelp className="mt-0.5 size-4 shrink-0 text-hold" />{line.text}</li>
          ))}
        </ul>
      </div>
    </div>
  );
}
