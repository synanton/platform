import axios from "axios";
import type { AuthSession } from "../types/ontology";

const api = axios.create({
  baseURL: "/api/v1/ontology",
});

api.interceptors.request.use((config) => {
  const raw = sessionStorage.getItem("syntology-auth");
  if (raw) {
    const session = JSON.parse(raw) as AuthSession;
    config.headers["X-Tenant-ID"] = session.tenantId;
    if (session.token) {
      config.headers.Authorization = `Bearer ${session.token}`;
    }
  }
  return config;
});

export default api;
