import { describe, expect, it } from "vitest";
import { toCytoscapeElements } from "../utils/graphTransformer";

describe("graphTransformer", () => {
  it("should convert graph response to cytoscape elements", () => {
    const elements = toCytoscapeElements({
      nodes: [{ id: "http://ex#A", label: "A", type: "Class" }],
      edges: [{ source: "http://ex#A", target: "http://ex#B", label: "subClassOf" }],
    });
    expect(elements).toHaveLength(2);
    expect(elements[0].data?.id).toBe("http://ex#A");
    expect(elements[1].data?.source).toBe("http://ex#A");
  });
});
