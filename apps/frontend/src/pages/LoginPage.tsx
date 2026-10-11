import { useState, type FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { fetchSession, homePath, useSession, useSessionActions } from "@/auth/session";
import { ApiError } from "@/lib/api";
import { TEXT } from "@/lib/messages";

export function LoginPage() {
  const session = useSession();
  const actions = useSessionActions();
  const navigate = useNavigate();
  const location = useLocation();
  const [userId, setUserId] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (session.data) return <Navigate to={homePath(session.data)} replace />;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await actions.login(userId.trim(), password);
      const from = (location.state as { from?: string } | null)?.from;
      const next = await fetchSession();
      navigate(from && from !== "/login" ? from : next ? homePath(next) : "/", { replace: true });
    } catch (caught) {
      setError(caught instanceof ApiError && caught.code === "LOGIN_FAILED" ? "사용자 ID 또는 비밀번호가 맞지 않습니다." : "로그인하지 못했습니다. 잠시 뒤 다시 시도하세요.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="grid min-h-full place-items-center p-6">
      <div className="grid w-full max-w-[920px] overflow-hidden rounded-3xl border border-line bg-surface shadow-sm md:grid-cols-[1.1fr_1fr]">
        <section className="flex flex-col justify-between gap-10 bg-brand-ink p-10 text-white">
          <div>
            <span className="grid size-12 place-items-center rounded-2xl bg-brand text-2xl font-black text-brand-ink">T</span>
            <h1 className="mt-6 text-[28px] leading-tight font-extrabold">상담 건을 열면
              <br />확인할 규정이 먼저 보입니다</h1>
            <p className="mt-4 text-[15px] leading-relaxed text-white/75">적용 공문, 승인 checklist, 원문 근거를 상담 전에 확인하고 직원 확인을 기록합니다.</p>
          </div>
          <p className="text-sm leading-relaxed text-white/75">{TEXT.humanDecision}</p>
        </section>
        <form onSubmit={submit} className="flex flex-col gap-5 p-10" aria-label="로그인">
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold">로그인</h2>
            <Badge tone="brand">{TEXT.synthetic}</Badge>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="user-id">사용자 ID</Label>
            <input id="user-id" autoComplete="username" value={userId} onChange={(event) => setUserId(event.target.value)} required
              className="h-12 rounded-xl border border-line bg-surface px-4 text-[15px] outline-none focus:border-brand-ink" />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="password">비밀번호</Label>
            <input id="password" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required
              className="h-12 rounded-xl border border-line bg-surface px-4 text-[15px] outline-none focus:border-brand-ink" />
          </div>
          {error && <Alert tone="danger">{error}</Alert>}
          <Button type="submit" disabled={busy} className="h-12">{busy ? "확인 중…" : "로그인"}</Button>
          <p className="text-sm leading-relaxed text-ink-soft">시연용 합성 계정(SYN-STAFF-01, SYN-REVIEWER-01, SYN-STAFF-REVIEWER-01)으로 로그인합니다. 비밀번호는 실행 환경의 환경변수로만 설정됩니다.</p>
        </form>
      </div>
    </div>
  );
}
