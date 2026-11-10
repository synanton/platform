import { FormEvent, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { EntityNeighborhoodGraph, edgeKey } from "../components/EntityGraph/EntityNeighborhoodGraph";
import { fetchEntityNeighbors, EntityGraphError } from "../services/entityGraphApi";
import type { EntityNeighbor, EntityNeighborsResponse } from "../types/entityGraph";

function LoadingSkeleton() {
  return (
    <div
      data-testid="entity-graph-loading"
      className="flex-1 min-h-[420px] rounded-lg border border-slate-200 bg-slate-50 p-6"
    >
      <svg viewBox="0 0 680 360" className="h-full w-full animate-pulse" role="img" aria-label="Loading graph">
        <circle cx="340" cy="180" r="30" fill="#cbd5e1" />
        <line x1="370" y1="180" x2="600" y2="100" stroke="#cbd5e1" strokeWidth="3" />
        <line x1="370" y1="180" x2="600" y2="260" stroke="#cbd5e1" strokeWidth="3" />
        <line x1="310" y1="180" x2="80" y2="110" stroke="#cbd5e1" strokeWidth="3" />
        <circle cx="620" cy="100" r="20" fill="#cbd5e1" />
        <circle cx="620" cy="260" r="20" fill="#cbd5e1" />
        <circle cx="60" cy="110" r="20" fill="#cbd5e1" />
        <text x="340" y="225" textAnchor="middle" fontSize="12" fill="#64748b">
          Loading neighbors…
        </text>
      </svg>
    </div>
  );
}

function EntityCard({ entity }: { entity: EntityNeighborsResponse["entity"] }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="text-xs uppercase tracking-wide text-slate-500">Entity</div>
      <div className="mt-1 text-lg font-semibold text-slate-900">{entity.label ?? entity.id}</div>
      <div className="text-sm text-slate-500">{entity.type ?? "Unknown type"}</div>
      <div className="mt-2 break-all font-mono text-xs text-slate-400">{entity.id}</div>
    </div>
  );
}

function EvidencePanel({
  entity,
  selected,
  evidence,
}: {
  entity: EntityNeighborsResponse["entity"];
  selected: EntityNeighbor | null;
  evidence: EntityNeighborsResponse["evidence"];
}) {
  const matching = selected
    ? evidence.filter((item) =>
        selected.canonical_relation_id
          ? item.canonical_relation_id === selected.canonical_relation_id
          : item.relation === selected.relation &&
            item.neighbor_entity_id === selected.entity.id,
      )
    : [];

  return (
    <aside className="w-80 shrink-0 space-y-4 overflow-y-auto">
      <EntityCard entity={entity} />
      <div className="rounded-lg border border-slate-200 bg-white p-4">
        <h2 className="text-sm font-semibold text-slate-800">Evidence</h2>
        {!selected && (
          <p className="mt-2 text-sm text-slate-500">Select an edge to inspect its evidence.</p>
        )}
        {selected && (
          <p className="mt-2 text-xs text-slate-500">
            {selected.direction === "OUTGOING" ? "Outgoing" : "Incoming"}{" "}
            <span className="font-mono">{selected.relation}</span> →{" "}
            {selected.entity.label ?? selected.entity.id}
          </p>
        )}
        {selected && matching.length === 0 && (
          <p className="mt-3 text-sm text-slate-500">No evidence recorded for this relation.</p>
        )}
        {matching.map((item, index) => (
          <div
            key={`${item.chunk_id}-${item.offset_start}-${index}`}
            className="mt-3 rounded border border-slate-100 bg-slate-50 p-3 text-xs"
            data-testid="entity-evidence-item"
          >
            <div className="font-mono text-slate-700">{item.chunk_id}</div>
            <div className="mt-1 text-slate-500">source {item.source_version}</div>
            <div className="mt-1 text-slate-500">
              offsets {item.offset_start}–{item.offset_end}
            </div>
          </div>
        ))}
      </div>
    </aside>
  );
}

export function EntityGraphPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeId = (searchParams.get("entity") ?? "").trim();
  const [input, setInput] = useState(activeId);
  const [data, setData] = useState<EntityNeighborsResponse | null>(null);
  const [selected, setSelected] = useState<EntityNeighbor | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryToken, setRetryToken] = useState(0);

  useEffect(() => {
    setInput(activeId);
    setSelected(null);
    setData(null);
    setError(null);
    if (!activeId) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    fetchEntityNeighbors(activeId)
      .then((response) => {
        if (!cancelled) setData(response);
      })
      .catch((failure: Error) => {
        if (!cancelled) {
          setError(failure instanceof EntityGraphError ? failure.message : "Could not load neighbors.");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [activeId, retryToken]);

  function submit(event: FormEvent) {
    event.preventDefault();
    const id = input.trim();
    if (id) {
      setSearchParams({ entity: id });
    }
  }

  return (
    <div className="flex flex-col h-[calc(100vh-4rem)]">
      <div className="border-b border-slate-200 bg-white px-6 py-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-lg font-semibold text-slate-900">Entity Graph</h1>
            <p className="text-sm text-slate-500">
              Bounded one-hop canonical neighborhood with relationship evidence.
            </p>
          </div>
          <form onSubmit={submit} className="flex items-center gap-2">
            <input
              aria-label="Entity ID"
              className="w-72 rounded border border-slate-300 px-3 py-2 text-sm"
              placeholder="Entity ID, e.g. entity-1"
              value={input}
              onChange={(event) => setInput(event.target.value)}
            />
            <button
              type="submit"
              className="rounded bg-brand-500 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700"
            >
              Load
            </button>
          </form>
        </div>
      </div>

      {!activeId && !loading && !data && !error && (
        <div className="flex flex-1 items-center justify-center p-6 text-sm text-slate-500">
          Enter an entity ID to open its bounded one-hop graph.
        </div>
      )}

      {loading && (
        <div className="flex flex-1 p-4">
          <LoadingSkeleton />
        </div>
      )}

      {error && !loading && (
        <div className="flex flex-1 items-center justify-center p-6">
          <div className="max-w-md rounded-lg border border-red-200 bg-red-50 p-5 text-center">
            <p className="text-sm text-red-700" data-testid="entity-graph-error">
              {error}
            </p>
            <button
              type="button"
              onClick={() => setRetryToken((value) => value + 1)}
              className="mt-4 rounded border border-red-300 bg-white px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-100"
            >
              Retry
            </button>
          </div>
        </div>
      )}

      {data && !loading && !error && (
        <div className="flex flex-1 min-h-0 gap-4 p-4">
          <section className="flex min-w-0 flex-1 flex-col gap-3 overflow-auto">
            {data.truncated && (
              <div
                data-testid="entity-graph-truncated"
                className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"
              >
                Showing the first {data.neighbors.length} neighbors; more are available.
              </div>
            )}
            {data.neighbors.length === 0 ? (
              <div className="flex flex-1 flex-col items-center justify-center gap-3 rounded-lg border border-slate-200 bg-slate-50 p-8">
                <div className="text-sm text-slate-500" data-testid="entity-graph-empty">
                  No neighbors for this entity.
                </div>
              </div>
            ) : (
              <EntityNeighborhoodGraph
                entity={data.entity}
                neighbors={data.neighbors}
                selectedKey={selected ? edgeKey(selected) : null}
                onSelect={setSelected}
              />
            )}
          </section>
          <EvidencePanel entity={data.entity} selected={selected} evidence={data.evidence} />
        </div>
      )}
    </div>
  );
}
