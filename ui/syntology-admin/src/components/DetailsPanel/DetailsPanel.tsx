import type { GraphNode } from "../../types/ontology";

interface DetailsPanelProps {
  node: GraphNode | null;
}

export function DetailsPanel({ node }: DetailsPanelProps) {
  if (!node) {
    return (
      <aside className="w-80 border-l border-slate-200 bg-white p-4 text-sm text-slate-500">
        Select a node to view details.
      </aside>
    );
  }

  return (
    <aside className="w-80 border-l border-slate-200 bg-white p-4 overflow-y-auto">
      <h2 className="text-lg font-semibold text-slate-800 mb-3">{node.label}</h2>
      <dl className="space-y-3 text-sm">
        <div>
          <dt className="font-medium text-slate-500">Type</dt>
          <dd className="text-slate-800">{node.type}</dd>
        </div>
        <div>
          <dt className="font-medium text-slate-500">URI</dt>
          <dd className="text-slate-800 break-all">{node.id}</dd>
        </div>
      </dl>
    </aside>
  );
}
