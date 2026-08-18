import axios from "axios";
import type { AclGrant, AuthSession } from "../types/ontology";

const topologyApi = axios.create({ baseURL: "/topology" });

topologyApi.interceptors.request.use((config) => {
  const raw = sessionStorage.getItem("syntology-auth");
  if (raw) {
    const session = JSON.parse(raw) as AuthSession;
    if (session.token) {
      config.headers.Authorization = `Bearer ${session.token}`;
    }
  }
  return config;
});

export async function getGrants(uid: number): Promise<AclGrant[]> {
  const resp = await topologyApi.get<AclGrant[]>(`/topology/users/${uid}/grants`);
  return resp.data;
}
