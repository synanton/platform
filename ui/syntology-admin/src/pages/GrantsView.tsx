import { useEffect, useState } from "react";
import { getSession } from "../services/auth";
import { getGrants } from "../services/topologyApi";
import type { AclGrant } from "../types/ontology";

export function GrantsView() {
  const session = getSession();
  const [grants, setGrants] = useState<AclGrant[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!session?.uid) {
      setLoading(false);
      return;
    }
    getGrants(session.uid)
      .then(setGrants)
      .catch(() => setError("Could not load grants."))
      .finally(() => setLoading(false));
  }, [session?.uid]);

  return (
    <div className="p-8 max-w-4xl mx-auto space-y-6">
      <h1 className="text-2xl font-bold text-slate-900">My Grants</h1>
      <p className="text-sm text-slate-500">
        ACL grants seeded from POSIX filesystem permissions for uid&nbsp;
        <span className="font-mono">{session?.uid}</span>.
      </p>

      {loading && <p className="text-sm text-slate-400">Loading…</p>}
      {error && (
        <p className="text-sm text-red-600">{error}</p>
      )}

      {!loading && !error && grants.length === 0 && (
        <p className="text-sm text-slate-400">No grants found.</p>
      )}

      {grants.length > 0 && (
        <div className="overflow-x-auto rounded-xl border border-slate-200">
          <table className="w-full text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-slate-600">
                <th className="px-4 py-3 font-medium">Resource</th>
                <th className="px-4 py-3 font-medium">Permission</th>
                <th className="px-4 py-3 font-medium">Source</th>
                <th className="px-4 py-3 font-medium">Type</th>
              </tr>
            </thead>
            <tbody>
              {grants.map((g) => (
                <tr key={g.grantId} className="border-t border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3 font-mono text-xs">{g.resourcePath}</td>
                  <td className="px-4 py-3">
                    <span
                      className={`rounded px-2 py-0.5 text-xs font-medium ${
                        g.permission === "ADMIN"
                          ? "bg-red-100 text-red-700"
                          : g.permission === "WRITE"
                          ? "bg-yellow-100 text-yellow-700"
                          : "bg-blue-100 text-blue-700"
                      }`}
                    >
                      {g.permission}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-slate-500 text-xs">{g.source}</td>
                  <td className="px-4 py-3 text-slate-500 text-xs">{g.subjectType}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
