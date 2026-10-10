import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const won = new Intl.NumberFormat("ko-KR");

export function formatWon(value: number): string {
  return `${won.format(value)}원`;
}

const dateTime = new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Seoul" });

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "-";
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : dateTime.format(parsed);
}

/** 업무일 기본값: 서울 기준 오늘(YYYY-MM-DD). */
export function seoulToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}

export function shortId(id: string, length = 8): string {
  const tail = id.includes(":") ? id.slice(id.lastIndexOf(":") + 1) : id;
  return tail.slice(0, length);
}
