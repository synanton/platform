import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import type { GraphNode, OntologyGraph } from "../types/ontology";

interface OntologyContextValue {
  version: string;
  setVersion: (version: string) => void;
  graph: OntologyGraph | null;
  setGraph: (graph: OntologyGraph | null) => void;
  selectedNode: GraphNode | null;
  setSelectedNode: (node: GraphNode | null) => void;
  searchTerm: string;
  setSearchTerm: (term: string) => void;
}

const OntologyContext = createContext<OntologyContextValue | undefined>(undefined);

export function OntologyProvider({ children }: { children: ReactNode }) {
  const [version, setVersion] = useState("active");
  const [graph, setGraph] = useState<OntologyGraph | null>(null);
  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);
  const [searchTerm, setSearchTerm] = useState("");

  const value = useMemo(
    () => ({
      version,
      setVersion,
      graph,
      setGraph,
      selectedNode,
      setSelectedNode,
      searchTerm,
      setSearchTerm,
    }),
    [version, graph, selectedNode, searchTerm],
  );

  return <OntologyContext.Provider value={value}>{children}</OntologyContext.Provider>;
}

export function useOntologyContext(): OntologyContextValue {
  const ctx = useContext(OntologyContext);
  if (!ctx) {
    throw new Error("useOntologyContext must be used within OntologyProvider");
  }
  return ctx;
}
