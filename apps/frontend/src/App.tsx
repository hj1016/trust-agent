import { Navigate, Route, Routes } from "react-router";
import { RequireSession } from "@/auth/RequireSession";
import { RoleGate } from "@/auth/RoleGate";
import { homePath, useSession } from "@/auth/session";
import { AppShell } from "@/components/AppShell";
import { ConsultationsPage } from "@/pages/ConsultationsPage";
import { LoginPage } from "@/pages/LoginPage";
import { ReviewsPage } from "@/pages/ReviewsPage";

function Home() {
  const { data } = useSession();
  return data ? <Navigate to={homePath(data)} replace /> : null;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireSession />}>
        <Route element={<AppShell />}>
          <Route index element={<Home />} />
          <Route path="/consultations" element={<RoleGate role="STAFF"><ConsultationsPage /></RoleGate>} />
          <Route path="/consultations/:consultationId" element={<RoleGate role="STAFF"><ConsultationsPage /></RoleGate>} />
          <Route path="/reviews" element={<RoleGate role="REVIEWER"><ReviewsPage /></RoleGate>} />
          <Route path="*" element={<Home />} />
        </Route>
      </Route>
    </Routes>
  );
}
