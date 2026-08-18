import { DetailsPanel } from "../components/DetailsPanel/DetailsPanel";
import { GraphCanvas } from "../components/GraphCanvas/GraphCanvas";
import { SearchBar } from "../components/SearchBar/SearchBar";
import { VersionSelector } from "../components/VersionSelector/VersionSelector";
import { useOntologyContext } from "../context/OntologyContext";
import { useOntology } from "../hooks/useOntology";

export function OntologyViewer() {
  const { selectedNode } = useOntologyContext();
  const { loading, error } = useOntology();

  return (
    <div className="flex flex-col h-[calc(100vh-4rem)]">
      <div className="px-6 py-4 border-b border-slate-200 bg-white flex flex-wrap items-center gap-4 justify-between">
        <VersionSelector />
        <SearchBar />
      </div>
      {error && (
        <div className="mx-6 mt-4 rounded bg-red-50 text-red-700 px-4 py-2 text-sm">{error}</div>
      )}
      {loading ? (
        <div className="flex-1 flex items-center justify-center text-slate-500">Loading graph...</div>
      ) : (
        <div className="flex flex-1 min-h-0 p-4 gap-4">
          <GraphCanvas />
          <DetailsPanel node={selectedNode} />
        </div>
      )}
    </div>
  );
}
