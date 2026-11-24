import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { WorkflowPage } from "./WorkflowPage";
import {
  ensureUploadSource,
  fetchDocumentGraph,
  fetchExtractionDocument,
  fetchSourceStatus,
  fetchSourceVersions,
  promoteGeneration,
  submitDocument,
} from "../services/workflowApi";

vi.mock("../services/workflowApi", () => ({
  ensureUploadSource: vi.fn(),
  submitDocument: vi.fn(),
  fetchSourceStatus: vi.fn(),
  fetchSourceVersions: vi.fn(),
  fetchExtractionDocument: vi.fn(),
  fetchDocumentGraph: vi.fn(),
  promoteGeneration: vi.fn(),
}));

const identity = {
  tenant_id: "tenant-a",
  connector_id: "upload",
  source_id: "demo-workflow",
  resource_id: "workflow/document.txt",
};
const version = {
  source_identity: identity,
  version_id: "version-1",
  source_version_id: "version-1",
  content_digest: "digest",
  content_cache_reference: "ref",
  source_revision: "rev",
  observed_at: "2026-11-24T00:00:00Z",
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("WorkflowPage", () => {
  it("runs submit → processing → results → provenance and explicit promotion", async () => {
    vi.mocked(fetchSourceStatus).mockResolvedValue({
      source_id: "demo-workflow", status: "KNOWLEDGE_READY", indexed: true,
      chunk_count: 1, extraction_status: "KNOWLEDGE_READY", graph_status: "MENTIONS_COMMITTED",
      resolution_status: "PENDING",
    });
    vi.mocked(fetchSourceVersions).mockResolvedValue([version]);
    vi.mocked(fetchExtractionDocument).mockResolvedValue({
      tenantId: "tenant-a", connectorId: "upload", sourceId: "demo-workflow",
      resourceId: "workflow/document.txt", versionId: "version-1", contentRefId: "ref",
      currentGeneration: null,
      generations: [{
        generationId: "gen-1", status: "STAGED", ontologyReleaseId: "release-1",
        ontologyReleaseDigest: "a".repeat(64), promptVersion: "extraction-v2",
        promptCompiledDigest: "b".repeat(64), schemaDigest: null, modelId: "model",
        modelRevision: "rev", startedAt: "2026-11-24T00:00:00Z", extractionRunId: null,
      }],
      results: [{
        chunkOrdinal: 0,
        entities: [{ surfaceForm: "Acme", proposedType: "Supplier" }],
        relations: [], promptVersion: "extraction-v2", promptCompiledDigest: "b".repeat(64),
        promptRenderedDigest: null, extractionGeneration: "gen-1",
        extractedAt: "2026-11-24T00:00:00Z",
      }],
      quarantine: [{
        generationId: "gen-1", chunkOrdinal: 0, lineNumber: 1, reasonCode: "SCHEMA",
        category: "STRUCTURAL", detail: "bad line", disposition: "OPEN",
        retentionExpiresAt: "2026-12-24T00:00:00Z",
      }],
    });
    vi.mocked(fetchDocumentGraph).mockResolvedValue({
      document_id: "version-1", source_version_id: "version-1",
      nodes: [], edges: [{ relation_id: "rel-1", subject_mention_id: "m1",
        object_mention_id: "m2", predicate: "supplies", promotion_state: "UNPROMOTED",
        canonical_relation_id: null, extraction_generation: "gen-1" }],
      truncated: false,
    });

    render(<WorkflowPage />);
    fireEvent.change(screen.getByLabelText("Source id"), { target: { value: "demo-workflow" } });
    fireEvent.change(screen.getByLabelText("Resource id"), { target: { value: "workflow/document.txt" } });
    fireEvent.change(screen.getByLabelText("Document"), {
      target: { files: [new File(["hello"], "document.txt", { type: "text/plain" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Submit document" }));

    await waitFor(() => expect(ensureUploadSource).toHaveBeenCalledWith("demo-workflow"));
    expect(submitDocument).toHaveBeenCalled();
    await screen.findByText(/Processing complete/);
    expect(screen.getByText(/release-1/)).toBeInTheDocument();
    expect(screen.getByText("SCHEMA")).toBeInTheDocument();
    expect(screen.getByText(/promotion UNPROMOTED/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Promote to ACTIVE" }));
    await waitFor(() => expect(promoteGeneration).toHaveBeenCalledWith(
      identity, "version-1", "gen-1"));
  });
});
