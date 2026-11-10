import axios, { AxiosError } from "axios";
import type { EntityNeighborsResponse } from "../types/entityGraph";
import { getSession } from "./auth";

export type EntityGraphErrorKind = "auth" | "not_found" | "server";

export class EntityGraphError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    public readonly kind: EntityGraphErrorKind = "server",
  ) {
    super(message);
    this.name = "EntityGraphError";
  }
}

const relixApi = axios.create({ baseURL: "/api" });

export async function fetchEntityNeighbors(
  entityId: string,
): Promise<EntityNeighborsResponse> {
  const session = getSession();
  if (!session?.token) {
    throw new EntityGraphError("Sign in again to view the graph.", 401, "auth");
  }
  try {
    const { data } = await relixApi.get<EntityNeighborsResponse>(
      `/v1/entities/${encodeURIComponent(entityId)}/neighbors`,
      { headers: { Authorization: `Bearer ${session.token}` } },
    );
    return data;
  } catch (error) {
    if (axios.isAxiosError(error)) {
      const status = (error as AxiosError).response?.status;
      if (status === 401 || status === 403) {
        throw new EntityGraphError("Sign in again to view the graph.", status, "auth");
      }
      if (status === 404) {
        throw new EntityGraphError("Entity not found.", status, "not_found");
      }
      throw new EntityGraphError("Could not load neighbors.", status, "server");
    }
    throw new EntityGraphError("Could not connect to the graph.", undefined, "server");
  }
}
