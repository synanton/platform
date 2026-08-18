import { getSession } from "../services/auth";

export function useTenant(): string {
  return getSession()?.tenantId ?? "demo";
}
