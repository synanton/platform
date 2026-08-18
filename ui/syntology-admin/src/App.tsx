import { Navigate, Route, Routes } from "react-router-dom";
import type { ReactNode } from "react";
import { Layout } from "./components/Layout/Layout";
import { OntologyProvider } from "./context/OntologyContext";
import { Admin } from "./pages/Admin";
import { Dashboard } from "./pages/Dashboard";
import { GrantsView } from "./pages/GrantsView";
import { Login } from "./pages/Login";
import { McpConfig } from "./pages/McpConfig";
import { OntologyViewer } from "./pages/OntologyViewer";
import { Chat } from "./pages/Chat";
import { isAuthenticated } from "./services/auth";

function ProtectedRoute({ children }: { children: ReactNode }) {
  if (!isAuthenticated()) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        element={
          <ProtectedRoute>
            <OntologyProvider>
              <Layout />
            </OntologyProvider>
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/viewer" element={<OntologyViewer />} />
        <Route path="/admin" element={<Admin />} />
        <Route path="/grants" element={<GrantsView />} />
        <Route path="/mcp-config" element={<McpConfig />} />
        <Route path="/settings" element={<Settings />} />
        <Route path="/chat" element={<Chat />} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
