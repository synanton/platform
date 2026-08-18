import { FormEvent, useEffect, useState } from "react";
import { fetchVersions, uploadVersion } from "../services/ontologyApi";
import type { OntologyVersion } from "../types/ontology";
import {
  createTenant,
  generateApiKey,
  getApiKeys,
  getTenants,
  revokeApiKey,
} from "../services/adminApi";
import type { ApiKey, ApiKeyCreated, Tenant } from "../services/adminApi";

type Tab = "ontology" | "tenants" | "api-keys";

export function Admin() {
  const [tab, setTab] = useState<Tab>("ontology");

  return (
    <div className="max-w-5xl mx-auto p-6 space-y-6">
      <h1 className="text-2xl font-bold text-slate-900">Admin Panel</h1>
      <div className="flex gap-2 border-b border-slate-200">
        {(
          [
            { key: "ontology", label: "Ontology" },
            { key: "tenants", label: "Tenants" },
            { key: "api-keys", label: "API Keys" },
          ] as { key: Tab; label: string }[]
        ).map(({ key, label }) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${
              tab === key
                ? "border-brand-500 text-brand-700"
                : "border-transparent text-slate-500 hover:text-slate-800"
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      {tab === "ontology" && <OntologyTab />}
      {tab === "tenants" && <TenantsTab />}
      {tab === "api-keys" && <ApiKeysTab />}
    </div>
  );
}

function OntologyTab() {
  const [versions, setVersions] = useState<OntologyVersion[]>([]);
  const [version, setVersion] = useState("");
  const [label, setLabel] = useState("");
  const [description, setDescription] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  function reload() {
    fetchVersions().then(setVersions).catch(() => setVersions([]));
  }

  useEffect(() => { reload(); }, []);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!file || !version) { setError("Version and Turtle file are required."); return; }
    setError(null); setMessage(null);
    try {
      await uploadVersion(version, label || version, description, file);
      setMessage(`Version ${version} created.`);
      setVersion(""); setLabel(""); setDescription(""); setFile(null);
      reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Upload failed");
    }
  }

  return (
    <div className="space-y-8">
      <section>
        <h2 className="text-xl font-semibold text-slate-900 mb-4">Ontology Versions</h2>
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <table className="min-w-full text-sm">
            <thead className="bg-slate-50 text-left">
              <tr>
                <th className="px-4 py-2">Version</th>
                <th className="px-4 py-2">Label</th>
                <th className="px-4 py-2">Status</th>
                <th className="px-4 py-2">Created</th>
              </tr>
            </thead>
            <tbody>
              {versions.map((v) => (
                <tr key={v.versionId} className="border-t border-slate-100">
                  <td className="px-4 py-2 font-medium">{v.version}</td>
                  <td className="px-4 py-2">{v.label}</td>
                  <td className="px-4 py-2">{v.status}</td>
                  <td className="px-4 py-2">{new Date(v.createdAt).toLocaleString()}</td>
                </tr>
              ))}
              {versions.length === 0 && (
                <tr><td colSpan={4} className="px-4 py-6 text-center text-slate-500">No versions loaded.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
      <section>
        <h2 className="text-xl font-semibold text-slate-900 mb-4">Upload New Version</h2>
        <form onSubmit={handleSubmit} className="space-y-4 rounded-lg border border-slate-200 bg-white p-6">
          <label className="block text-sm">
            <span className="font-medium">Version</span>
            <input className="mt-1 w-full rounded border border-slate-300 px-3 py-2" value={version} onChange={(e) => setVersion(e.target.value)} placeholder="1.1.0" required />
          </label>
          <label className="block text-sm">
            <span className="font-medium">Label</span>
            <input className="mt-1 w-full rounded border border-slate-300 px-3 py-2" value={label} onChange={(e) => setLabel(e.target.value)} />
          </label>
          <label className="block text-sm">
            <span className="font-medium">Description</span>
            <textarea className="mt-1 w-full rounded border border-slate-300 px-3 py-2" value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
          </label>
          <label className="block text-sm">
            <span className="font-medium">Turtle file</span>
            <input type="file" accept=".ttl,.turtle" className="mt-1 block w-full text-sm" onChange={(e) => setFile(e.target.files?.[0] ?? null)} required />
          </label>
          {error && <p className="text-sm text-red-600">{error}</p>}
          {message && <p className="text-sm text-green-700">{message}</p>}
          <button type="submit" className="rounded-lg bg-brand-500 px-4 py-2 text-white font-medium hover:bg-brand-700">Create version</button>
        </form>
      </section>
    </div>
  );
}

function TenantsTab() {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [tenantId, setTenantId] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [ownerEmail, setOwnerEmail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  function reload() {
    setLoading(true);
    getTenants().then(setTenants).catch(() => setTenants([])).finally(() => setLoading(false));
  }

  useEffect(() => { reload(); }, []);

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setError(null); setMessage(null);
    try {
      await createTenant(tenantId, displayName, ownerEmail ? `user:${ownerEmail}` : "");
      setMessage(`Tenant "${tenantId}" created.`);
      setTenantId(""); setDisplayName(""); setOwnerEmail("");
      reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create tenant");
    }
  }

  return (
    <div className="space-y-8">
      <section>
        <h2 className="text-xl font-semibold text-slate-900 mb-4">Tenants</h2>
        {loading ? (
          <p className="text-sm text-slate-400">Loading…</p>
        ) : (
          <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
            <table className="min-w-full text-sm">
              <thead className="bg-slate-50 text-left">
                <tr>
                  <th className="px-4 py-2">Tenant ID</th>
                  <th className="px-4 py-2">Display Name</th>
                  <th className="px-4 py-2">Created</th>
                </tr>
              </thead>
              <tbody>
                {tenants.map((t) => (
                  <tr key={t.tenantId} className="border-t border-slate-100">
                    <td className="px-4 py-2 font-mono text-xs">{t.tenantId}</td>
                    <td className="px-4 py-2">{t.displayName}</td>
                    <td className="px-4 py-2">{new Date(t.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
                {tenants.length === 0 && (
                  <tr><td colSpan={3} className="px-4 py-6 text-center text-slate-500">No tenants found.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <section>
        <h2 className="text-xl font-semibold text-slate-900 mb-4">Create Tenant</h2>
        <form onSubmit={handleCreate} className="space-y-4 rounded-lg border border-slate-200 bg-white p-6">
          <label className="block text-sm">
            <span className="font-medium">Tenant ID</span>
            <input className="mt-1 w-full rounded border border-slate-300 px-3 py-2 font-mono" value={tenantId} onChange={(e) => setTenantId(e.target.value)} placeholder="acme-corp" required />
          </label>
          <label className="block text-sm">
            <span className="font-medium">Display Name</span>
            <input className="mt-1 w-full rounded border border-slate-300 px-3 py-2" value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Acme Corporation" required />
          </label>
          <label className="block text-sm">
            <span className="font-medium">Owner email (optional)</span>
            <input className="mt-1 w-full rounded border border-slate-300 px-3 py-2" value={ownerEmail} onChange={(e) => setOwnerEmail(e.target.value)} placeholder="owner@acme.com" type="email" />
          </label>
          {error && <p className="text-sm text-red-600">{error}</p>}
          {message && <p className="text-sm text-green-700">{message}</p>}
          <button type="submit" className="rounded-lg bg-brand-500 px-4 py-2 text-white font-medium hover:bg-brand-700">Create tenant</button>
        </form>
      </section>
    </div>
  );
}

function ApiKeysTab() {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [loading, setLoading] = useState(true);
  const [label, setLabel] = useState("");
  const [scopes, setScopes] = useState("");
  const [newKey, setNewKey] = useState<ApiKeyCreated | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<string | null>(null);

  function reload() {
    setLoading(true);
    getApiKeys().then(setKeys).catch(() => setKeys([])).finally(() => setLoading(false));
  }

  useEffect(() => { reload(); }, []);

  async function handleGenerate(e: FormEvent) {
    e.preventDefault();
    setError(null); setNewKey(null);
    const scopeList = scopes.split(",").map((s) => s.trim()).filter(Boolean);
    try {
      const created = await generateApiKey(label, scopeList);
      setNewKey(created);
      setLabel(""); setScopes("");
      reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to generate key");
    }
  }

  async function handleRevoke(keyId: string) {
    setRevoking(keyId);
    try {
      await revokeApiKey(keyId);
      reload();
    } catch {
      // silently ignore
    } finally {
      setRevoking(null);
    }
  }

  return (
    <div className="space-y-8">
      <section>
        <h2 className="text-xl font-semibold text-slate-900 mb-4">API Keys</h2>
        {loading ? (
          <p className="text-sm text-slate-400">Loading…</p>
        ) : (
          <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
            <table className="min-w-full text-sm">
              <thead className="bg-slate-50 text-left">
                <tr>
                  <th className="px-4 py-2">Label</th>
                  <th className="px-4 py-2">Key ID</th>
                  <th className="px-4 py-2">Scopes</th>
                  <th className="px-4 py-2">Created</th>
                  <th className="px-4 py-2"></th>
                </tr>
              </thead>
              <tbody>
                {keys.map((k) => (
                  <tr key={k.keyId} className="border-t border-slate-100">
                    <td className="px-4 py-2">{k.label || "-"}</td>
                    <td className="px-4 py-2 font-mono text-xs">{k.keyId}</td>
                    <td className="px-4 py-2 font-mono text-xs">{k.scopes.join(", ") || "-"}</td>
                    <td className="px-4 py-2">{new Date(k.createdAt).toLocaleString()}</td>
                    <td className="px-4 py-2">
                      <button
                        type="button"
                        onClick={() => handleRevoke(k.keyId)}
                        disabled={revoking === k.keyId}
                        className="text-red-600 hover:underline text-xs disabled:opacity-40"
                      >
                        Revoke
                      </button>
                    </td>
                  </tr>
                ))}
                {keys.length === 0 && (
                  <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No active API keys.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {newKey && (
        <div className="rounded-lg border border-green-200 bg-green-50 p-4 text-sm">
          <p className="font-semibold text-green-800 mb-1">New API key generated - copy it now, it won't be shown again:</p>
          <code className="block bg-white rounded border border-green-200 px-3 py-2 font-mono text-xs break-all select-all">
            {newKey.key}
          </code>
        </div>
      )}

      <section>
        <h2 className="text-xl font-semibold text-slate-900 mb-4">Generate API Key</h2>
        <form onSubmit={handleGenerate} className="space-y-4 rounded-lg border border-slate-200 bg-white p-6">
          <label className="block text-sm">
            <span className="font-medium">Label</span>
            <input className="mt-1 w-full rounded border border-slate-300 px-3 py-2" value={label} onChange={(e) => setLabel(e.target.value)} placeholder="My service key" />
          </label>
          <label className="block text-sm">
            <span className="font-medium">Scopes (comma-separated)</span>
            <input className="mt-1 w-full rounded border border-slate-300 px-3 py-2 font-mono" value={scopes} onChange={(e) => setScopes(e.target.value)} placeholder="read,write" />
          </label>
          {error && <p className="text-sm text-red-600">{error}</p>}
          <button type="submit" className="rounded-lg bg-brand-500 px-4 py-2 text-white font-medium hover:bg-brand-700">Generate key</button>
        </form>
      </section>
    </div>
  );
}
