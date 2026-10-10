import { Navigate, Outlet, useLocation } from "react-router";
import { useSession } from "./session";

export function RequireSession() {
  const session = useSession();
  const location = useLocation();
  if (session.isPending) {
    return <p className="p-8 text-ink-soft">세션을 확인하는 중입니다…</p>;
  }
  if (session.isError) {
    return <p className="p-8 text-danger">업무 시스템에 연결하지 못했습니다. 잠시 뒤 다시 시도하세요.</p>;
  }
  if (!session.data) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}
