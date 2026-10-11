/**
 * Core API 클라이언트. 브라우저는 Core만 부른다(ADR-014). 같은 출처 세션 쿠키를 쓰고,
 * 상태 변경 요청에는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보낸다. 토큰·grant 같은 내부 값은 다루지 않는다.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
  ) {
    super(detail || code);
  }
}

export function csrfToken(cookie: string = typeof document === "undefined" ? "" : document.cookie): string | undefined {
  const entry = cookie.split("; ").find((part) => part.startsWith("XSRF-TOKEN="));
  return entry ? decodeURIComponent(entry.slice("XSRF-TOKEN=".length)) : undefined;
}

type RequestOptions = { method?: string; json?: unknown; form?: Record<string, string> };

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? (options.json !== undefined || options.form ? "POST" : "GET");
  const headers: Record<string, string> = { Accept: "application/json" };
  let body: BodyInit | undefined;
  if (options.json !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.json);
  } else if (options.form) {
    headers["Content-Type"] = "application/x-www-form-urlencoded";
    body = new URLSearchParams(options.form).toString();
  }
  if (method !== "GET") {
    const token = csrfToken();
    if (token) headers["X-XSRF-TOKEN"] = token;
  }
  const response = await fetch(path, { method, headers, body, credentials: "same-origin" });
  const text = await response.text();
  const data = text ? safeJson(text) : null;
  if (!response.ok) {
    const problem = (data ?? {}) as { code?: string; detail?: string; title?: string };
    throw new ApiError(response.status, problem.code ?? `HTTP_${response.status}`, problem.detail ?? problem.title ?? "");
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
