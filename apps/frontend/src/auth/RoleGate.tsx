import type { ReactNode } from "react";
import { roleLabel } from "@/lib/labels";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import type { Role } from "@/lib/types";
import { useSession, useSessionActions } from "./session";

const ROLE_NAME: Record<Role, string> = { STAFF: roleLabel("STAFF").text, REVIEWER: roleLabel("REVIEWER").text };

/** 활성 역할이 다르면 화면 대신 안내를 보인다. 실제 권한은 Core가 검사한다(보유 + 활성 역할). */
export function RoleGate({ role, children }: { role: Role; children: ReactNode }) {
  const { data: session } = useSession();
  const actions = useSessionActions();
  if (!session) return null;
  if (session.activeRole === role) return <>{children}</>;
  const held = session.roles.includes(role);
  return (
    <div className="mx-auto max-w-xl p-8">
      <Alert tone="hold">
        이 화면은 {ROLE_NAME[role]} 활성 역할에서 쓸 수 있습니다. 현재 활성 역할은 {ROLE_NAME[session.activeRole]}입니다.
        {held ? "" : " 이 계정은 해당 역할을 갖고 있지 않습니다."}
      </Alert>
      {held && (
        <Button className="mt-4" onClick={() => actions.switchRole(role)}>
          {ROLE_NAME[role]} 역할로 전환
        </Button>
      )}
    </div>
  );
}

export { ROLE_NAME };
