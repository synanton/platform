export interface LoginResponse {
  token: string;
  expires_in: number;
}

export async function loginApi(username: string, password: string): Promise<LoginResponse> {
  const resp = await fetch("/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!resp.ok) {
    throw new Error("Invalid username or password");
  }
  return resp.json();
}

export function parseJwtPayload(token: string): Record<string, unknown> {
  try {
    const payload = token.split(".")[1];
    return JSON.parse(atob(payload));
  } catch {
    return {};
  }
}
