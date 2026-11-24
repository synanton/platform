import { FormEvent, useState } from "react";
import { getSession } from "../services/auth";

interface SearchHit {
  object_id: string;
  score: number;
  title: string;
  snippet: string;
  provenance_ref?: string;
}

export function Chat() {
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    setHits([]);
    const session = getSession();
    if (!session?.token) {
      setError("Sign in again to search.");
      return;
    }
    setLoading(true);
    try {
      const response = await fetch("/synquest/v1/search", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${session.token}`,
        },
        body: JSON.stringify({ query }),
      });
      if (response.status === 503) {
        setError("Search index is updating. Try again in a few seconds.");
        return;
      }
      if (!response.ok) {
        setError(`Search failed (HTTP ${response.status}).`);
        return;
      }
      const body = await response.json();
      setHits(body.results ?? []);
      if ((body.results ?? []).length === 0) setError("No results found.");
    } catch {
      setError("Search unavailable.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      <h1 className="text-xl font-semibold mb-1">Synanton Search</h1>
      <p className="mb-4 text-sm text-slate-500">
        Search the canonical knowledge index through the public search API.
      </p>
      <form onSubmit={onSubmit} className="flex gap-2 mb-4">
        <input
          className="flex-1 rounded border px-3 py-2"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Ask the corpus…"
        />
        <button type="submit" disabled={loading}
          className="rounded bg-brand-900 px-4 py-2 text-white disabled:opacity-60">
          {loading ? "Searching…" : "Search"}
        </button>
      </form>
      {error && <p className="text-sm text-amber-700">{error}</p>}
      <div className="space-y-3">
        {hits.map((hit) => (
          <article key={hit.object_id} className="rounded-lg border border-slate-200 bg-white p-4">
            <div className="flex items-start justify-between gap-3">
              <h2 className="font-semibold text-slate-900">{hit.title || hit.object_id}</h2>
              <span className="text-xs text-slate-400">score {hit.score.toFixed(3)}</span>
            </div>
            <p className="mt-1 text-sm text-slate-600">{hit.snippet}</p>
            <div className="mt-2 flex flex-wrap gap-3 text-xs text-slate-400">
              <span className="font-mono">{hit.object_id}</span>
              {hit.provenance_ref && <span className="font-mono">{hit.provenance_ref}</span>}
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}
