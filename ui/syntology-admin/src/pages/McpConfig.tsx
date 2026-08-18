import { useEffect, useState } from "react";
import { getMcpCapabilities } from "../services/adminApi";
import type { McpTool } from "../services/adminApi";

export function McpConfig() {
  const [tools, setTools] = useState<McpTool[]>([]);
  const [protocolVersion, setProtocolVersion] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getMcpCapabilities()
      .then((caps) => {
        setTools(caps.tools ?? []);
        setProtocolVersion(caps.protocolVersion ?? "");
      })
      .catch(() => setError("Could not reach synanton-mcp. Make sure the service is running on port 8091."))
      .finally(() => setLoading(false));
  }, []);

  const mcpUrl = `${window.location.origin}/mcp`;
  const claudeDesktopConfig = JSON.stringify(
    {
      mcpServers: {
        synanton: {
          url: mcpUrl,
          headers: { Authorization: "Bearer <your-api-key>" },
        },
      },
    },
    null,
    2
  );

  return (
    <div className="max-w-4xl mx-auto p-6 space-y-8">
      <h1 className="text-2xl font-bold text-slate-900">MCP Configuration</h1>

      <section className="rounded-xl border border-slate-200 bg-white p-6 space-y-4">
        <h2 className="text-lg font-semibold text-slate-800">Server Info</h2>
        <dl className="grid grid-cols-2 gap-2 text-sm">
          <dt className="text-slate-500">Endpoint</dt>
          <dd className="font-mono">{mcpUrl}</dd>
          <dt className="text-slate-500">Protocol</dt>
          <dd className="font-mono">{protocolVersion || "-"}</dd>
          <dt className="text-slate-500">Auth</dt>
          <dd>Bearer API key (<code className="bg-slate-100 px-1 rounded text-xs">syn_…</code>)</dd>
        </dl>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-6 space-y-4">
        <h2 className="text-lg font-semibold text-slate-800">Claude Desktop Config</h2>
        <p className="text-sm text-slate-500">
          Add this to your <code className="bg-slate-100 px-1 rounded">claude_desktop_config.json</code> file, replacing{" "}
          <code className="bg-slate-100 px-1 rounded">&lt;your-api-key&gt;</code> with a key generated in the API Keys tab.
        </p>
        <pre className="bg-slate-900 text-slate-100 rounded-lg p-4 text-xs overflow-x-auto whitespace-pre-wrap select-all">
          {claudeDesktopConfig}
        </pre>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-6 space-y-4">
        <h2 className="text-lg font-semibold text-slate-800">Available Tools</h2>
        {loading && <p className="text-sm text-slate-400">Loading…</p>}
        {error && <p className="text-sm text-red-600">{error}</p>}
        {!loading && !error && tools.length === 0 && (
          <p className="text-sm text-slate-500">No tools found.</p>
        )}
        <div className="space-y-4">
          {tools.map((tool) => (
            <div key={tool.name} className="rounded-lg border border-slate-100 bg-slate-50 p-4">
              <div className="flex items-center gap-2 mb-1">
                <span className="font-mono text-sm font-semibold text-brand-700">{tool.name}</span>
              </div>
              <p className="text-sm text-slate-600">{tool.description}</p>
              {tool.inputSchema && (
                <details className="mt-2">
                  <summary className="text-xs text-slate-400 cursor-pointer hover:text-slate-600">Input schema</summary>
                  <pre className="mt-1 text-xs bg-white rounded border border-slate-200 p-2 overflow-x-auto">
                    {JSON.stringify(tool.inputSchema, null, 2)}
                  </pre>
                </details>
              )}
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
