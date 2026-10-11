import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, request } from "@/lib/api";
import type { Role, SessionSummary } from "@/lib/types";

export const SESSION_KEY = ["session"] as const;

/** 현재 세션. 401이면 null(로그인 전). 이 호출이 XSRF-TOKEN 쿠키도 받아 온다. */
export async function fetchSession(): Promise<SessionSummary | null> {
  try {
    return await request<SessionSummary>("/api/v1/session");
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
}

export function useSession() {
  return useQuery({ queryKey: SESSION_KEY, queryFn: fetchSession, staleTime: 30_000 });
}

export async function login(userId: string, password: string): Promise<void> {
  await fetchSession(); // CSRF 쿠키 확보
  await request("/login", { form: { username: userId, password } });
}

export function useSessionActions() {
  const client = useQueryClient();
  return {
    async login(userId: string, password: string) {
      await login(userId, password);
      client.clear();
      await client.fetchQuery({ queryKey: SESSION_KEY, queryFn: fetchSession });
    },
    async logout() {
      try {
        await request("/logout", { method: "POST" });
      } finally {
        client.clear();
        client.setQueryData(SESSION_KEY, null);
      }
    },
    async switchRole(role: Role) {
      const summary = await request<SessionSummary>("/api/v1/session/active-role", { json: { role } });
      client.removeQueries({ predicate: (query) => query.queryKey[0] !== "session" });
      client.setQueryData(SESSION_KEY, summary);
      return summary;
    },
  };
}

export function homePath(session: SessionSummary): string {
  return session.activeRole === "REVIEWER" ? "/reviews" : "/consultations";
}
