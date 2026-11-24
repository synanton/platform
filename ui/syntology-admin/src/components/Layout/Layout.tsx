
import { Link, Outlet, useNavigate } from "react-router-dom";
import { getSession, logout } from "../../services/auth";
import { useTenant } from "../../hooks/useTenant";

export function Layout() {
  const navigate = useNavigate();
  const session = getSession();
  const tenant = useTenant();

  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-brand-900 text-white px-6 py-4 flex items-center justify-between shadow">
        <div className="flex items-center gap-6">
          <Link to="/viewer" className="text-lg font-semibold tracking-tight">
            Syntology Admin
          </Link>
          <nav className="flex gap-4 text-sm">
            <Link to="/dashboard" className="hover:text-brand-50 opacity-90">
              Dashboard
            </Link>
            <Link to="/viewer" className="hover:text-brand-50 opacity-90">
              Viewer
            </Link>
            <Link to="/entity-graph" className="hover:text-brand-50 opacity-90">
              Entity Graph
            </Link>
            <Link to="/admin" className="hover:text-brand-50 opacity-90">
              Admin
            </Link>
            <Link to="/grants" className="hover:text-brand-50 opacity-90">
              Grants
            </Link>
            <Link to="/mcp-config" className="hover:text-brand-50 opacity-90">
              MCP
            </Link>
            <Link to="/workflow" className="hover:text-brand-50 opacity-90">
              Workflow
            </Link>
            <Link to="/chat" className="hover:text-brand-50 opacity-90">
              Chat
            </Link>
            <Link to="/settings" className="hover:text-brand-50 opacity-90">
              Settings
            </Link>
          </nav>
        </div>
        <div className="flex items-center gap-3 text-sm">
          <span className="opacity-80">{session?.username}</span>
          <span className="rounded bg-white/10 px-3 py-1 font-mono text-xs" title="Tenant is fixed by the signed-in token">
            tenant {tenant || "unknown"}
          </span>
          <button
            type="button"
            className="rounded bg-white/10 px-3 py-1 hover:bg-white/20"
            onClick={() => {
              logout();
              navigate("/login");
            }}
          >
            Logout
          </button>
        </div>
      </header>
      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  );
}
