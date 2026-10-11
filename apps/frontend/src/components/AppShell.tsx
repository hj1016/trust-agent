import { NavLink, Outlet, useNavigate } from "react-router";
import { LogOut } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ROLE_NAME } from "@/auth/RoleGate";
import { homePath, useSession, useSessionActions } from "@/auth/session";
import { TEXT } from "@/lib/messages";
import type { Role } from "@/lib/types";
import { cn } from "@/lib/utils";

export function AppShell() {
  const { data: session } = useSession();
  const actions = useSessionActions();
  const navigate = useNavigate();
  if (!session) return null;
  const switchTo = async (role: Role) => {
    const next = await actions.switchRole(role);
    navigate(homePath(next));
  };
  return (
    <div className="flex h-full flex-col">
      <header className="flex h-16 shrink-0 items-center gap-4 border-b border-line bg-surface px-5">
        <div className="flex items-center gap-2.5">
          <span aria-hidden className="grid size-9 place-items-center rounded-xl bg-brand-ink text-lg font-black text-brand">T</span>
          <span className="text-[17px] font-extrabold tracking-tight">TrustAgent 업무지원</span>
        </div>
        <Badge tone="brand" title={TEXT.syntheticNotice}>{TEXT.synthetic}</Badge>
        <nav aria-label="주 메뉴" className="ml-4 flex gap-1">
          {session.activeRole === "STAFF" && <MenuLink to="/consultations">상담 건</MenuLink>}
          {session.activeRole === "REVIEWER" && <MenuLink to="/reviews">검수 목록(읽기 전용)</MenuLink>}
        </nav>
        <div className="ml-auto flex items-center gap-3">
          {session.roles.length > 1 && (
            <div role="group" aria-label="활성 역할" className="flex rounded-xl bg-muted p-1" title={TEXT.roleOverlap}>
              {session.roles.map((role) => (
                <button
                  key={role}
                  type="button"
                  aria-pressed={session.activeRole === role}
                  onClick={() => session.activeRole !== role && switchTo(role)}
                  className={cn(
                    "h-9 rounded-lg px-3 text-sm font-semibold",
                    session.activeRole === role ? "bg-surface text-ink shadow-sm" : "text-ink-soft hover:text-ink",
                  )}
                >
                  {ROLE_NAME[role]}
                </button>
              ))}
            </div>
          )}
          {session.roles.length === 1 && <Badge tone="info">{ROLE_NAME[session.activeRole]}</Badge>}
          <span className="text-sm font-semibold text-ink" data-testid="principal">{session.principal}</span>
          <Button variant="ghost" size="sm" onClick={async () => { await actions.logout(); navigate("/login"); }}>
            <LogOut /> 로그아웃
          </Button>
        </div>
      </header>
      <main className="min-h-0 flex-1">
        <Outlet />
      </main>
    </div>
  );
}

function MenuLink({ to, children }: { to: string; children: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) => cn("rounded-lg px-3 py-2 text-[15px] font-semibold", isActive ? "bg-brand-soft text-brand-ink" : "text-ink-soft hover:bg-muted")}
    >
      {children}
    </NavLink>
  );
}
