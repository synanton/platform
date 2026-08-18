import { useNavigate } from "react-router-dom";
import { getSession, logout } from "../services/auth";

export function Settings() {
  const navigate = useNavigate();
  const session = getSession();

  function handleLogout() {
    logout();
    navigate("/login");
  }

  const expDate = session?.exp
    ? new Date(session.exp * 1000).toLocaleString()
    : "-";

  return (
    <div className="p-8 max-w-2xl mx-auto space-y-8">
      <h1 className="text-2xl font-bold text-slate-900">Settings</h1>

      <section className="rounded-xl border border-slate-200 p-6 space-y-4">
        <h2 className="font-semibold text-slate-700">Session</h2>
        <dl className="grid grid-cols-2 gap-2 text-sm">
          <dt className="text-slate-500">Subject</dt>
          <dd className="font-medium">{session?.username}</dd>
          <dt className="text-slate-500">Tenant</dt>
          <dd className="font-mono">{session?.tenantId}</dd>
          <dt className="text-slate-500">UID</dt>
          <dd className="font-mono">{session?.uid}</dd>
          <dt className="text-slate-500">GIDs</dt>
          <dd className="font-mono">{session?.gids?.join(", ") || "-"}</dd>
          <dt className="text-slate-500">Token expires</dt>
          <dd className="text-xs text-slate-600">{expDate}</dd>
        </dl>
      </section>

      <section className="rounded-xl border border-slate-200 p-6 space-y-4">
        <h2 className="font-semibold text-slate-700">Token</h2>
        <pre className="bg-slate-50 rounded p-3 text-xs break-all whitespace-pre-wrap text-slate-600">
          {session?.token}
        </pre>
      </section>

      <button
        type="button"
        onClick={handleLogout}
        className="rounded-lg bg-red-600 px-6 py-2.5 text-white font-medium hover:bg-red-700"
      >
        Sign out
      </button>
    </div>
  );
}
