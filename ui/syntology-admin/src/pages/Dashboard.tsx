import { useEffect, useState } from "react";
import { getSession } from "../services/auth";
import api from "../services/apiClient";
import type { OntologyVersion } from "../types/ontology";

export function Dashboard() {
  const session = getSession();
  const [versions, setVersions] = useState<OntologyVersion[]>([]);
  const [capabilities, setCapabilities] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.get<OntologyVersion[]>("/versions"),
      api.get<Record<string, unknown>>("/capabilities"),
    ])
      .then(([vResp, cResp]) => {
        setVersions(vResp.data);
        setCapabilities(cResp.data);
      })
      .finally(() => setLoading(false));
  }, []);

  const active = versions.find((v) => v.status === "ACTIVE");

  return (
    <div className="p-8 max-w-4xl mx-auto space-y-8">
      <h1 className="text-2xl font-bold text-slate-900">Dashboard</h1>

      <section className="rounded-xl border border-slate-200 p-6 space-y-3">
        <h2 className="font-semibold text-slate-700">Current User</h2>
        <dl className="grid grid-cols-2 gap-2 text-sm">
          <dt className="text-slate-500">Username</dt>
          <dd className="font-medium">{session?.username}</dd>
          <dt className="text-slate-500">UID</dt>
          <dd className="font-mono">{session?.uid}</dd>
          <dt className="text-slate-500">GIDs</dt>
          <dd className="font-mono">{session?.gids?.join(", ") || "-"}</dd>
          <dt className="text-slate-500">Tenant</dt>
          <dd className="font-mono">{session?.tenantId}</dd>
        </dl>
      </section>

      <section className="rounded-xl border border-slate-200 p-6 space-y-3">
        <h2 className="font-semibold text-slate-700">Ontology</h2>
        {loading ? (
          <p className="text-sm text-slate-400">Loading…</p>
        ) : (
          <dl className="grid grid-cols-2 gap-2 text-sm">
            <dt className="text-slate-500">Total versions</dt>
            <dd className="font-medium">{versions.length}</dd>
            <dt className="text-slate-500">Active version</dt>
            <dd className="font-medium">{active?.version ?? "-"}</dd>
            <dt className="text-slate-500">Active label</dt>
            <dd>{active?.label ?? "-"}</dd>
          </dl>
        )}
      </section>

      {capabilities && (
        <section className="rounded-xl border border-slate-200 p-6 space-y-3">
          <h2 className="font-semibold text-slate-700">Capabilities</h2>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-slate-500">
                <th className="pb-2 font-medium">Feature</th>
                <th className="pb-2 font-medium">Support</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(
                (capabilities.features as Record<string, string>) ?? {}
              ).map(([k, v]) => (
                <tr key={k} className="border-t border-slate-100">
                  <td className="py-1.5 font-mono text-xs">{k}</td>
                  <td className="py-1.5">
                    <span
                      className={`rounded px-2 py-0.5 text-xs font-medium ${
                        v === "NATIVE"
                          ? "bg-green-100 text-green-800"
                          : "bg-slate-100 text-slate-500"
                      }`}
                    >
                      {v}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}
