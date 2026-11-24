import axios from "axios";
import { getSession } from "./auth";

export interface SourceIdentity {
  tenant_id: string;
  connector_id: string;
  source_id: string;
  resource_id: string;
}

export interface SourceVersion {
  source_identity: SourceIdentity;
  version_id: string;
  source_version_id: string;
  content_digest: string;
  content_cache_reference: string;
  source_revision: string;
  observed_at: string;
}

export interface SourceStatus {
  source_id: string;
  status: string;
  indexed: boolean;
  chunk_count: number;
  extraction_status: string;
  graph_status: string;
  resolution_status: string;
}

export interface GenerationView {
  generationId: string;
  status: string;
  ontologyReleaseId: string | null;
  ontologyReleaseDigest: string | null;
  promptVersion: string | null;
  promptCompiledDigest: string | null;
  schemaDigest: string | null;
  modelId: string | null;
  modelRevision: string | null;
  startedAt: string;
  extractionRunId: string | null;
}

export interface ResultView {
  chunkOrdinal: number;
  entities: Array<{ surfaceForm?: string; proposedType?: string }>;
  relations: Array<{ predicate?: string }>;
  promptVersion: string | null;
  promptCompiledDigest: string | null;
  promptRenderedDigest: string | null;
  extractionGeneration: string | null;
  extractedAt: string;
}

export interface QuarantineView {
  generationId: string;
  chunkOrdinal: number;
  lineNumber: number;
  reasonCode: string;
  category: string;
  detail: string;
  disposition: string;
  retentionExpiresAt: string;
}

export interface ExtractionDocument {
  tenantId: string;
  connectorId: string;
  sourceId: string;
  resourceId: string;
  versionId: string;
  contentRefId: string;
  currentGeneration: string | null;
  generations: GenerationView[];
  results: ResultView[];
  quarantine: QuarantineView[];
}

export interface DocumentGraph {
  document_id: string;
  source_version_id: string;
  nodes: Array<{ mention_id: string; surface_form: string; proposed_type: string; resolution_state: string; extraction_generation: string | null }>;
  edges: Array<{ relation_id: string; subject_mention_id: string; object_mention_id: string; predicate: string; promotion_state: string; canonical_relation_id: string | null; extraction_generation: string | null }>;
  truncated: boolean;
}

const api = axios.create({ baseURL: "/synflux" });

function authHeaders() {
  const session = getSession();
  if (!session?.token) throw new Error("Sign in again.");
  return { Authorization: `Bearer ${session.token}` };
}

export async function ensureUploadSource(sourceId: string): Promise<void> {
  try {
    await axios.post("/synflux/v1/sources", {
      source_id: sourceId,
      connector_id: "upload",
      connector_type: "upload",
      source_endpoint: "",
      capability_flags: [],
    }, { headers: authHeaders() });
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 409) return;
    throw error;
  }
}

export async function submitDocument(sourceId: string, resourceId: string, file: File): Promise<void> {
  const form = new FormData();
  form.append("connector_id", "upload");
  form.append("source_id", sourceId);
  form.append("resource_id", resourceId);
  form.append("file", file);
  await api.post("/v1/ingestion-runs", form, { headers: authHeaders() });
}

export async function fetchSourceStatus(sourceId: string): Promise<SourceStatus> {
  const { data } = await api.get(`/v1/sources/${encodeURIComponent(sourceId)}/status`, {
    headers: authHeaders(),
  });
  return data;
}

export async function fetchSourceVersions(sourceId: string): Promise<SourceVersion[]> {
  const { data } = await api.get(`/v1/sources/${encodeURIComponent(sourceId)}/versions`, {
    headers: authHeaders(),
  });
  return data;
}

export async function fetchExtractionDocument(
  identity: SourceIdentity,
  versionId: string,
): Promise<ExtractionDocument> {
  const { data } = await api.get("/v1/extraction/documents", {
    params: {
      connectorId: identity.connector_id,
      sourceId: identity.source_id,
      resourceId: identity.resource_id,
      versionId,
    },
    headers: authHeaders(),
  });
  return data;
}

export async function promoteGeneration(
  identity: SourceIdentity,
  versionId: string,
  generationId: string,
): Promise<void> {
  await api.post("/v1/extraction/documents/promote", {
    connectorId: identity.connector_id,
    sourceId: identity.source_id,
    resourceId: identity.resource_id,
    versionId,
    generationId,
    reason: "ui promotion",
  }, { headers: authHeaders() });
}

export async function fetchDocumentGraph(versionId: string): Promise<DocumentGraph> {
  const { data } = await axios.get(`/api/v1/documents/${encodeURIComponent(versionId)}/graph`, {
    headers: authHeaders(),
  });
  return data;
}
