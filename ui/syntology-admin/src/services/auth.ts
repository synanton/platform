import type { AuthSession } from "../types/ontology";
import { loginApi, parseJwtPayload } from "./authApi";

const STORAGE_KEY = "syntology-auth";

export async function loginWithCredentials(username: string, password: string): Promise<AuthSession> {
  const { token, expires_in } = await loginApi(username, password);
  const payload = parseJwtPayload(token);
  const session: AuthSession = {
    username: (payload.sub as string) || username,
    tenantId: (payload.tenant_id as string) || "demo",
    token,
    uid: (payload.uid as number) || 0,
    gids: (payload.gid as number[]) || [],
    exp: (payload.exp as number) || Math.floor(Date.now() / 1000) + expires_in,
  };
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  return session;
}

export function logout(): void {
  sessionStorage.removeItem(STORAGE_KEY);
}

export function getSession(): AuthSession | null {
  const raw = sessionStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  return JSON.parse(raw) as AuthSession;
}

export function isAuthenticated(): boolean {
  return getSession() !== null;
}
