import { useEffect, useRef, useState } from "react";
import { Link, Outlet, useNavigate } from "react-router-dom";
import { getSession, logout } from "../../services/auth";
import { getTenants } from "../../services/adminApi";
import type { Tenant } from "../../services/adminApi";
import { useTenant } from "../../hooks/useTenant";

const TENANT_KEY = "syntology-active-tenant";

function TenantSwitcher({ current }: { current: string }) {
  const [open, setOpen] = useState(false);
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [active, setActive] = useState(
    () => localStorage.getItem(TENANT_KEY) || current
  );
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    getTenants().then(setTenants).catch(() => {});
  }, []);

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);

  function select(tenantId: string) {
    localStorage.setItem(TENANT_KEY, tenantId);
    setActive(tenantId);
    setOpen(false);
  }

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 rounded bg-white/10 px-3 py-1 text-sm hover:bg-white/20"
      >
        <span className="opacity-70 text-xs">tenant</span>
        <span className="font-mono font-semibold">{active}</span>
        <span className="ml-1 opacity-60">▾</span>
      </button>
      {open && tenants.length > 0 && (
        <div className="absolute right-0 mt-1 w-52 rounded-lg bg-white shadow-lg border border-slate-200 py-1 z-50">
          {tenants.map((t) => (
            <button
              key={t.tenantId}
              type="button"
              onClick={() => select(t.tenantId)}
              className={`w-full text-left px-4 py-2 text-sm hover:bg-slate-50 ${
                t.tenantId === active ? "font-semibold text-brand-700" : "text-slate-700"
              }`}
            >
              <span className="font-mono">{t.tenantId}</span>
              {t.displayName && (
                <span className="ml-1 text-slate-400 text-xs">· {t.displayName}</span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

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
            <Link to="/admin" className="hover:text-brand-50 opacity-90">
              Admin
            </Link>
            <Link to="/grants" className="hover:text-brand-50 opacity-90">
              Grants
            </Link>
            <Link to="/mcp-config" className="hover:text-brand-50 opacity-90">
              MCP
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
          <TenantSwitcher current={tenant} />
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
