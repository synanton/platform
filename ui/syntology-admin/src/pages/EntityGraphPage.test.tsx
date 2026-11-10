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
        canonical_relation_id: "rel-out",
      },
      {
        entity: { id: "beta", label: "Beta", type: "Organization" },
        relation: "supports",
        direction: "INCOMING",
        canonical_relation_id: "rel-in",
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
        canonical_relation_id: "rel-out",
      },
      {
        relation: "supports",
        neighbor_entity_id: "beta",
        chunk_id: "chunk-2",
        source_version: "version-2",
        offset_start: 20,
        offset_end: 28,
        canonical_relation_id: "rel-in",
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

    const outgoing = await screen.findByTestId("entity-edge-OUTGOING-rel-out-alpha");
    expect(outgoing).toHaveAttribute("tabindex", "0");
    expect(outgoing).toHaveAttribute("role", "button");
    fireEvent.click(outgoing);
    expect(outgoing).toHaveAttribute("data-selected", "true");
    expect(await screen.findByText("chunk-1")).toBeInTheDocument();
    expect(screen.getByText("source version-1")).toBeInTheDocument();

    const incoming = screen.getByTestId("entity-edge-INCOMING-rel-in-beta");
    fireEvent.click(incoming);
    expect(await screen.findByText("chunk-2")).toBeInTheDocument();
    expect(screen.getByText("source version-2")).toBeInTheDocument();
  });

  it("filters evidence by canonical relation for two relations between the same pair", async () => {
    const sameEntity = { id: "alpha", label: "Alpha", type: "Protocol" };
    mockFetch.mockResolvedValue(
      response({
        neighbors: [
          {
            entity: sameEntity,
            relation: "reports_to",
            direction: "OUTGOING",
            canonical_relation_id: "rel-reports",
          },
          {
            entity: sameEntity,
            relation: "works_for",
            direction: "OUTGOING",
            canonical_relation_id: "rel-works",
          },
        ],
        evidence: [
          {
            relation: "reports_to",
            neighbor_entity_id: "alpha",
            chunk_id: "chunk-reports",
            source_version: "v1",
            offset_start: 1,
            offset_end: 2,
            canonical_relation_id: "rel-reports",
          },
          {
            relation: "works_for",
            neighbor_entity_id: "alpha",
            chunk_id: "chunk-works",
            source_version: "v1",
            offset_start: 3,
            offset_end: 4,
            canonical_relation_id: "rel-works",
          },
        ],
      }),
    );
    renderPage();

    fireEvent.click(await screen.findByTestId("entity-edge-OUTGOING-rel-reports-alpha"));
    expect(await screen.findByText("chunk-reports")).toBeInTheDocument();
    expect(screen.queryByText("chunk-works")).not.toBeInTheDocument();

    fireEvent.keyDown(screen.getByTestId("entity-edge-OUTGOING-rel-works-alpha"), {
      key: "Enter",
    });
    expect(await screen.findByText("chunk-works")).toBeInTheDocument();
    expect(screen.queryByText("chunk-reports")).not.toBeInTheDocument();
  });

  it("renders the truncation notice for 50 neighbors", async () => {
    const neighbors = Array.from({ length: 50 }, (_, index) => ({
      entity: { id: `n-${index}`, label: `N ${index}`, type: "Concept" },
      relation: "relates_to",
      direction: "OUTGOING" as const,
      canonical_relation_id: `rel-${index}`,
    }));
    mockFetch.mockResolvedValue(response({ neighbors, evidence: [], truncated: true }));
    renderPage();
    expect(await screen.findByTestId("entity-graph-truncated")).toHaveTextContent(
      "Showing the first 50 neighbors; more are available.",
    );
  });
});
