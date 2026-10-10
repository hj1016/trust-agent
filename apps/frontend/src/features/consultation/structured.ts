import type { StructuredChange } from "@/lib/types";

const UNIT: Record<string, string> = { PERCENT: "%", BOOLEAN: "", KRW: "원" };

/** 값 + 단위 표기(Core 값 그대로, 단위 기호만 붙인다). */
export function formatValue(value: unknown, unit: string | undefined): string {
  const suffix = unit ? UNIT[unit] ?? ` ${unit}` : "";
  return `${String(value)}${suffix}`;
}

/** 구조화 값을 사람이 읽을 줄로 바꾼다. 값은 Core 응답 그대로이며 해석을 더하지 않는다. */
export function describeChange(change: StructuredChange | null): string[] {
  if (!change) return [];
  const lines: string[] = [];
  const unit = change.unit ? UNIT[change.unit] ?? ` ${change.unit}` : "";
  if (change.unit !== "BOOLEAN" && change.after_value !== undefined && change.after_value !== null) {
    const before = change.before_value === null || change.before_value === undefined ? "" : `${String(change.before_value)}${unit} → `;
    lines.push(`변경 값: ${before}${String(change.after_value)}${unit}`);
  }
  if (change.effective_on) lines.push(`시행일: ${change.effective_on}`);
  if (Array.isArray(change.conditions) && change.conditions.length) lines.push(`조건: ${change.conditions.join(" / ")}`);
  if (Array.isArray(change.exceptions) && change.exceptions.length) lines.push(`예외: ${change.exceptions.join(" / ")}`);
  return lines;
}
