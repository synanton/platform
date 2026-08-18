import api from "./apiClient";
import type { EntityType, OntologyGraph, OntologyVersion } from "../types/ontology";

export async function fetchVersions(): Promise<OntologyVersion[]> {
  const { data } = await api.get<OntologyVersion[]>("/versions");
  return data;
}

export async function fetchGraph(version: string): Promise<OntologyGraph> {
  const { data } = await api.get<OntologyGraph>("/graph", { params: { version } });
  return data;
}

export async function fetchEntity(label: string, version: string): Promise<EntityType> {
  const { data } = await api.get<EntityType>("/entities", { params: { label, version } });
  return data;
}

export async function uploadVersion(
  version: string,
  label: string,
  description: string,
  file: File,
): Promise<OntologyVersion> {
  const form = new FormData();
  form.append("version", version);
  form.append("label", label);
  form.append("description", description);
  form.append("file", file);
  const { data } = await api.post<OntologyVersion>("/versions", form, {
    headers: { "Content-Type": "multipart/form-data" },
  });
  return data;
}

export async function fetchCapabilities(): Promise<Record<string, unknown>> {
  const { data } = await api.get<Record<string, unknown>>("/capabilities");
  return data;
}
