import { useEffect, useRef } from "react";
import cytoscape, { type Core } from "cytoscape";
// @ts-expect-error no types published for cola extension
import cola from "cytoscape-cola";
import { useOntologyContext } from "../../context/OntologyContext";
import { nodeColor, toCytoscapeElements } from "../../utils/graphTransformer";

cytoscape.use(cola);

export function GraphCanvas() {
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<Core | null>(null);
  const { graph, setSelectedNode, searchTerm } = useOntologyContext();

  useEffect(() => {
    if (!containerRef.current || !graph) {
      return;
    }

    if (cyRef.current) {
      cyRef.current.destroy();
    }

    const cy = cytoscape({
      container: containerRef.current,
      elements: toCytoscapeElements(graph),
      style: [
        {
          selector: "node",
          style: {
            label: "data(label)",
            "background-color": (ele) => nodeColor(ele.data("type")),
            color: "#fff",
            "text-valign": "center",
            "text-halign": "center",
            "font-size": 10,
            width: 40,
            height: 40,
          },
        },
        {
          selector: "node.highlight",
          style: {
            "border-width": 3,
            "border-color": "#f59e0b",
          },
        },
        {
          selector: "node.faded",
          style: { opacity: 0.25 },
        },
        {
          selector: "edge",
          style: {
            label: "data(label)",
            width: 2,
            "line-color": "#94a3b8",
            "target-arrow-color": "#94a3b8",
            "target-arrow-shape": "triangle",
            "curve-style": "bezier",
            "font-size": 8,
          },
        },
      ],
      layout: { name: "cola", animate: true, randomize: true, maxSimulationTime: 3000 },
    });

    cy.on("tap", "node", (event) => {
      const data = event.target.data();
      setSelectedNode({ id: data.id, label: data.label, type: data.type });
    });

    cyRef.current = cy;

    return () => {
      cy.destroy();
      cyRef.current = null;
    };
  }, [graph, setSelectedNode]);

  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) {
      return;
    }
    const term = searchTerm.trim().toLowerCase();
    cy.nodes().forEach((node) => {
      node.removeClass("highlight faded");
      if (!term) {
        return;
      }
      const label = String(node.data("label")).toLowerCase();
      const id = String(node.data("id")).toLowerCase();
      if (label.includes(term) || id.includes(term)) {
        node.addClass("highlight");
      } else {
        node.addClass("faded");
      }
    });
  }, [searchTerm]);

  return (
    <div
      ref={containerRef}
      className="flex-1 min-h-[480px] bg-slate-100 border border-slate-200 rounded-lg"
    />
  );
}
