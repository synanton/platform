import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { EntityGraphPage } from "./EntityGraphPage";
import { EntityGraphError, fetchEntityNeighbors } from "../services/entityGraphApi";
import type { EntityNeighborsResponse } from "../types/entityGraph";

vi.mock("../services/entityGraphApi", async () => {
  const actual = await vi.importActual<typeof import("../services/entityGraphApi")>(
    "../services/entityGraphApi",
  );
  return { ...actual, fetchEntityNeighbors: vi.fn() };
});

const mockFetch = vi.mocked(fetchEntityNeighbors);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/entity-graph?entity=entity-1"]}>
      <EntityGraphPage />
    </MemoryRouter>,
  );
}

function response(overrides: Partial<EntityNeighborsResponse> = {}): EntityNeighborsResponse {
  return {
    entity: { id: "entity-1", label: "Halcyon-7", type: "MaintenanceSystem" },
    neighbors: [
      {
        entity: { id: "alpha", label: "Alpha", type: "Protocol" },
        relation: "requires",
        direction: "OUTGOING",
      },
      {
        entity: { id: "beta", label: "Beta", type: "Organization" },
        relation: "supports",
        direction: "INCOMING",
      },
    ],
    evidence: [
      {
        relation: "requires",
        neighbor_entity_id: "alpha",
        chunk_id: "chunk-1",
        source_version: "version-1",
        offset_start: 4,
        offset_end: 12,
      },
      {
        relation: "supports",
        neighbor_entity_id: "beta",
        chunk_id: "chunk-2",
        source_version: "version-2",
        offset_start: 20,
        offset_end: 28,
      },
    ],
    truncated: false,
    ...overrides,
  };
}

describe("EntityGraphPage", () => {
  it("renders a loading skeleton", () => {
    mockFetch.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId("entity-graph-loading")).toBeInTheDocument();
  });

  it("renders the empty state with the entity card", async () => {
    mockFetch.mockResolvedValue(response({ neighbors: [], evidence: [] }));
    renderPage();
    expect(await screen.findByTestId("entity-graph-empty")).toHaveTextContent(
      "No neighbors for this entity.",
    );
    expect(screen.getByText("Halcyon-7")).toBeInTheDocument();
  });

  it("renders an error with retry", async () => {
    mockFetch.mockRejectedValue(new EntityGraphError("Could not load neighbors.", 503, "server"));
    renderPage();
    expect(await screen.findByTestId("entity-graph-error")).toHaveTextContent(
      "Could not load neighbors.",
    );
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });

  it("renders both directions and the evidence for the selected edge", async () => {
    mockFetch.mockResolvedValue(response());
    renderPage();

    const outgoing = await screen.findByTestId("entity-edge-OUTGOING-alpha");
    fireEvent.click(outgoing);
    expect(await screen.findByText("chunk-1")).toBeInTheDocument();
    expect(screen.getByText("source version-1")).toBeInTheDocument();

    const incoming = screen.getByTestId("entity-edge-INCOMING-beta");
    fireEvent.click(incoming);
    expect(await screen.findByText("chunk-2")).toBeInTheDocument();
    expect(screen.getByText("source version-2")).toBeInTheDocument();
  });

  it("renders the truncation notice for 50 neighbors", async () => {
    const neighbors = Array.from({ length: 50 }, (_, index) => ({
      entity: { id: `n-${index}`, label: `N ${index}`, type: "Concept" },
      relation: "relates_to",
      direction: "OUTGOING" as const,
    }));
    mockFetch.mockResolvedValue(response({ neighbors, evidence: [], truncated: true }));
    renderPage();
    expect(await screen.findByTestId("entity-graph-truncated")).toHaveTextContent(
      "Showing the first 50 neighbors; more are available.",
    );
  });
});
