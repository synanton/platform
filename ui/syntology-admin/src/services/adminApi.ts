import axios from "axios";
import type { InternalAxiosRequestConfig } from "axios";
import type { AuthSession } from "../types/ontology";

const authAxios = axios.create({ baseURL: "/auth" });
const topologyAxios = axios.create({ baseURL: "/topology" });
const mcpAxios = axios.create({ baseURL: "" });

function attachAuth(config: InternalAxiosRequestConfig) {
  const raw = sessionStorage.getItem("syntology-auth");
  if (raw) {
    const session = JSON.parse(raw) as AuthSession;
    if (session.token) config.headers.Authorization = `Bearer ${session.token}`;
  }
  return config;
}

authAxios.interceptors.request.use(attachAuth);
topologyAxios.interceptors.request.use(attachAuth);
mcpAxios.interceptors.request.use(attachAuth);

export interface Tenant {
  tenantId: string;
  displayName: string;
  createdAt: string;
}

export interface ApiKey {
  keyId: string;
  tenantId: string;
  subjectId: string;
  label: string;
  scopes: string[];
  createdAt: string;
}

export interface ApiKeyCreated extends ApiKey {
  key: string;
}

export interface McpTool {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export async function getTenants(): Promise<Tenant[]> {
  const r = await topologyAxios.get<Tenant[]>("/tenants");
  return r.data;
}

export async function createTenant(
  tenantId: string,
  displayName: string,
  ownerSubjectId: string
): Promise<Tenant> {
  const r = await topologyAxios.post<Tenant>("/tenants", {
    tenantId,
    displayName,
    ownerSubjectId,
  });
  return r.data;
}

export async function getApiKeys(): Promise<ApiKey[]> {
  const r = await authAxios.get<ApiKey[]>("/api-keys");
  return r.data;
}

export async function generateApiKey(
  label: string,
  scopes: string[]
): Promise<ApiKeyCreated> {
  const r = await authAxios.post<ApiKeyCreated>("/api-keys", { label, scopes });
  return r.data;
}

export async function revokeApiKey(keyId: string): Promise<void> {
  await authAxios.delete(`/api-keys/${keyId}`);
}

export async function getMcpCapabilities(): Promise<{ tools: McpTool[]; protocolVersion?: string }> {
  const r = await mcpAxios.get<{ tools: McpTool[]; protocolVersion?: string }>("/mcp");
  return r.data;
}
