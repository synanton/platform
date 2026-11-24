import { FormEvent, useState } from "react";
import {
  DocumentGraph,
  ExtractionDocument,
  SourceStatus,
  SourceVersion,
  ensureUploadSource,
  fetchDocumentGraph,
  fetchExtractionDocument,
  fetchSourceStatus,
  fetchSourceVersions,
  promoteGeneration,
  submitDocument,
} from "../services/workflowApi";

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export function WorkflowPage() {
  const [sourceId, setSourceId] = useState("demo-workflow");
  const [resourceId, setResourceId] = useState("workflow/document.txt");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [status, setStatus] = useState<SourceStatus | null>(null);
  const [version, setVersion] = useState<SourceVersion | null>(null);
  const [document, setDocument] = useState<ExtractionDocument | null>(null);
  const [graph, setGraph] = useState<DocumentGraph | null>(null);

  async function loadDetails(selected: SourceVersion) {
    const extraction = await fetchExtractionDocument(selected.source_identity, selected.version_id);
    setDocument(extraction);
    try {
      setGraph(await fetchDocumentGraph(selected.version_id));
    } catch {
      setGraph(null);
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!file || !sourceId.trim() || !resourceId.trim()) {
      setError("Source id, resource id, and file are required.");
      return;
    }
    setBusy(true);
    setError(null);
    setMessage(null);
    setStatus(null);
    setVersion(null);
    setDocument(null);
    setGraph(null);
    try {
      await ensureUploadSource(sourceId.trim());
      await submitDocument(sourceId.trim(), resourceId.trim(), file);
      let latest: SourceStatus | null = null;
      for (let attempt = 0; attempt < 60; attempt += 1) {
        latest = await fetchSourceStatus(sourceId.trim());
        setStatus(latest);
        if (latest.extraction_status === "KNOWLEDGE_READY"
            || latest.extraction_status === "EXTRACTION_FAILED") break;
        await sleep(2000);
      }
      const versions = await fetchSourceVersions(sourceId.trim());
      const selected = versions
        .filter((item) => item.source_identity.resource_id === resourceId.trim())
        .sort((a, b) => b.observed_at.localeCompare(a.observed_at))[0];
      if (!selected) throw new Error("No source version was created for this resource.");
      setVersion(selected);
      if (latest?.extraction_status === "KNOWLEDGE_READY") {
        await loadDetails(selected);
        setMessage("Processing complete. Results, provenance, and graph are available.");
      } else {
        setMessage("Processing did not reach KNOWLEDGE_READY. Inspect status and retry.");
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Workflow failed.");
    } finally {
      setBusy(false);
    }
  }

  async function promote(generationId: string) {
    if (!version) return;
    setBusy(true);
    setError(null);
    try {
      await promoteGeneration(version.source_identity, version.version_id, generationId);
      await loadDetails(version);
      setMessage(`Generation ${generationId} promoted.`);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Promotion failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">Document Workflow</h1>
        <p className="mt-1 text-sm text-slate-500">
          Submit a document, follow processing, inspect extraction results/provenance, and explicitly promote a generation.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4 rounded-lg border border-slate-200 bg-white p-6">
        <div className="grid gap-4 md:grid-cols-3">
          <label className="text-sm">
            <span className="font-medium">Source id</span>
            <input className="mt-1 w-full rounded border border-slate-300 px-3 py-2"
              value={sourceId} onChange={(event) => setSourceId(event.target.value)} />
          </label>
          <label className="text-sm">
            <span className="font-medium">Resource id</span>
            <input className="mt-1 w-full rounded border border-slate-300 px-3 py-2"
              value={resourceId} onChange={(event) => setResourceId(event.target.value)} />
          </label>
          <label className="text-sm">
            <span className="font-medium">Document</span>
            <input type="file" className="mt-1 w-full text-sm"
              onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
          </label>
        </div>
        <button type="submit" disabled={busy}
          className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">
          {busy ? "Working…" : "Submit document"}
        </button>
        {error && <p className="text-sm text-red-600">{error}</p>}
        {message && <p className="text-sm text-emerald-700">{message}</p>}
      </form>

      {status && (
        <section className="rounded-lg border border-slate-200 bg-white p-6">
          <h2 className="text-lg font-semibold">Processing</h2>
          <dl className="mt-3 grid gap-3 text-sm md:grid-cols-4">
            <Stat label="Status" value={status.status} />
            <Stat label="Extraction" value={status.extraction_status} />
            <Stat label="Graph" value={status.graph_status} />
            <Stat label="Chunks" value={String(status.chunk_count)} />
          </dl>
        </section>
      )}

      {version && (
        <section className="rounded-lg border border-slate-200 bg-white p-6">
          <h2 className="text-lg font-semibold">Document identity</h2>
          <p className="mt-2 break-all font-mono text-xs text-slate-500">
            source_version_id={version.source_version_id}
          </p>
        </section>
      )}

      {document && (
        <div className="grid gap-6 lg:grid-cols-2">
          <section className="rounded-lg border border-slate-200 bg-white p-6">
            <h2 className="text-lg font-semibold">Results</h2>
            <p className="mt-1 text-sm text-slate-500">
              {document.results.reduce((total, item) => total + item.entities.length, 0)} mentions,
              {" "}{document.results.reduce((total, item) => total + item.relations.length, 0)} candidate relations,
              {" "}{document.quarantine.length} quarantined lines.
            </p>
            <div className="mt-4 space-y-3">
              {document.results.map((result) => (
                <div key={result.chunkOrdinal} className="rounded border border-slate-100 bg-slate-50 p-3 text-sm">
                  <div className="font-semibold">Chunk {result.chunkOrdinal}</div>
                  <div className="mt-1 text-slate-600">
                    {result.entities.map((entity) => entity.surfaceForm ?? "entity").join(", ") || "No accepted mentions"}
                  </div>
                  <div className="mt-1 text-xs text-slate-500">
                    generation {result.extractionGeneration ?? "legacy"}
                  </div>
                </div>
              ))}
            </div>
          </section>

          <section className="rounded-lg border border-slate-200 bg-white p-6">
            <h2 className="text-lg font-semibold">Provenance</h2>
            <div className="mt-4 space-y-4">
              {document.generations.map((generation) => (
                <div key={generation.generationId} className="rounded border border-slate-100 bg-slate-50 p-3 text-xs">
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-mono">{generation.generationId}</span>
                    <span className={`rounded px-2 py-1 ${document.currentGeneration === generation.generationId ? "bg-emerald-100 text-emerald-800" : "bg-slate-200 text-slate-700"}`}>
                      {document.currentGeneration === generation.generationId ? "ACTIVE" : generation.status}
                    </span>
                  </div>
                  <div className="mt-2 text-slate-600">ontology release {generation.ontologyReleaseId ?? "legacy"}</div>
                  <div className="break-all text-slate-500">digest {generation.ontologyReleaseDigest ?? "null"}</div>
                  <div className="mt-1 text-slate-500">prompt {generation.promptVersion ?? "unknown"}</div>
                  {document.currentGeneration !== generation.generationId && (
                    <button type="button" disabled={busy} onClick={() => promote(generation.generationId)}
                      className="mt-3 rounded bg-brand-600 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50">
                      Promote to ACTIVE
                    </button>
                  )}
                </div>
              ))}
            </div>
          </section>

          <section className="rounded-lg border border-slate-200 bg-white p-6 lg:col-span-2">
            <h2 className="text-lg font-semibold">Quarantine</h2>
            {document.quarantine.length === 0 ? (
              <p className="mt-2 text-sm text-slate-500">No quarantined lines.</p>
            ) : (
              <ul className="mt-3 space-y-2 text-sm">
                {document.quarantine.slice(0, 50).map((item, index) => (
                  <li key={`${item.generationId}-${item.chunkOrdinal}-${item.lineNumber}-${index}`}
                    className="rounded border border-amber-200 bg-amber-50 p-3">
                    <span className="font-mono text-xs">{item.reasonCode}</span>
                    <span className="ml-2 text-slate-600">chunk {item.chunkOrdinal}, line {item.lineNumber}</span>
                    <div className="mt-1 text-xs text-slate-500">{item.detail}</div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {graph && (
            <section className="rounded-lg border border-slate-200 bg-white p-6 lg:col-span-2">
              <h2 className="text-lg font-semibold">Document graph</h2>
              <p className="mt-1 text-sm text-slate-500">
                {graph.nodes.length} mentions, {graph.edges.length} edges{graph.truncated ? " (truncated)" : ""}.
              </p>
              <div className="mt-3 grid gap-3 md:grid-cols-2">
                {graph.edges.slice(0, 50).map((edge) => (
                  <div key={edge.relation_id} className="rounded border border-slate-100 bg-slate-50 p-3 text-xs">
                    <div className="font-mono">{edge.subject_mention_id} → {edge.predicate} → {edge.object_mention_id}</div>
                    <div className="mt-1 text-slate-500">promotion {edge.promotion_state}</div>
                    <div className="text-slate-500">extraction {edge.extraction_generation ?? "legacy"}</div>
                  </div>
                ))}
              </div>
            </section>
          )}
        </div>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-1 font-semibold text-slate-800">{value}</dd>
    </div>
  );
}
