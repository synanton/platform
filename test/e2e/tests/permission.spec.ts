import { test, expect } from "@playwright/test";

const AUTH_URL = "/auth/login";
const VERSIONS_URL = "/api/v1/ontology/versions";

// Minimal valid Turtle ontology document used as the upload payload.
const TURTLE_PAYLOAD = Buffer.from(
  "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
    "<http://example.org/test> a owl:Ontology .\n"
);

async function getToken(
  request: import("@playwright/test").APIRequestContext,
  username: string,
  password: string
): Promise<string> {
  const resp = await request.post(AUTH_URL, {
    data: { username, password },
  });
  expect(resp.ok(), `login failed for ${username}: ${resp.status()}`).toBe(
    true
  );
  const body = await resp.json();
  return body.token as string;
}

test("alice can create an ontology version", async ({ request }) => {
  const token = await getToken(request, "alice", "alice123");

  const resp = await request.post(VERSIONS_URL, {
    headers: { Authorization: `Bearer ${token}` },
    multipart: {
      version: "e2e-2.0.0",
      label: "E2E Test Version",
      file: {
        name: "test.ttl",
        mimeType: "text/turtle",
        buffer: TURTLE_PAYLOAD,
      },
    },
  });

  expect(
    resp.status(),
    `expected 200 but got ${resp.status()}: ${await resp.text()}`
  ).toBe(200);
});

test("bob is denied write access (403 ERR_FS_PERMISSION)", async ({
  request,
}) => {
  const token = await getToken(request, "bob", "bob456");

  const resp = await request.post(VERSIONS_URL, {
    headers: { Authorization: `Bearer ${token}` },
    multipart: {
      version: "e2e-3.0.0",
      label: "Bob Forbidden Version",
      file: {
        name: "test.ttl",
        mimeType: "text/turtle",
        buffer: TURTLE_PAYLOAD,
      },
    },
  });

  expect(resp.status()).toBe(403);
  const body = await resp.json();
  expect(body.code).toBe("ERR_FS_PERMISSION");
});
