import { FormEvent, useState } from "react";
import { SafeHtml } from "../components/SafeHtml/SafeHtml";

export function Chat() {
  const [query, setQuery] = useState("");
  const [answer, setAnswer] = useState("");
  const [error, setError] = useState("");

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      const response = await fetch("/search", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query, top_k: 10 }),
      });
      if (!response.ok) {
        setError("Search failed");
        return;
      }
      const body = await response.json();
      setAnswer(body.answer || JSON.stringify(body.hits || []));
    } catch {
      setError("Search unavailable");
    }
  }

  return (
    <div className="p-6 max-w-3xl mx-auto">
      <h1 className="text-xl font-semibold mb-4">Synanton Chat</h1>
      <form onSubmit={onSubmit} className="flex gap-2 mb-4">
        <input
          className="flex-1 border rounded px-3 py-2"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Ask the corpus…"
        />
        <button type="submit" className="bg-brand-900 text-white px-4 py-2 rounded">
          Search
        </button>
      </form>
      {error && <p className="text-red-600">{error}</p>}
      {answer && <SafeHtml html={answer} />}
    </div>
  );
}
