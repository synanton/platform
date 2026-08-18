import type { ElementDefinition } from "cytoscape";
import type { GraphEdge, GraphNode, OntologyGraph } from "../types/ontology";

export function toCytoscapeElements(graph: OntologyGraph): ElementDefinition[] {
  const nodes: ElementDefinition[] = graph.nodes.map((node: GraphNode) => ({
    data: { id: node.id, label: node.label, type: node.type },
  }));
  const edges: ElementDefinition[] = graph.edges.map((edge: GraphEdge, index) => ({
    data: {
      id: `${edge.source}-${edge.target}-${index}`,
      source: edge.source,
      target: edge.target,
      label: edge.label,
    },
  }));
  return [...nodes, ...edges];
}

export function nodeColor(type: string): string {
  return type === "Property" ? "#059669" : "#2563eb";
}
