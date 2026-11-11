// #599 live Entity Graph journey against a running local Compose stack.
//
// Preconditions:
//   - local-real-demo Compose is up with extraction/graph/resolution/promotion enabled
//   - canonical fixture entities are seeded and projected
//   - VITE_RELIX_URL points at Relix (default http://127.0.0.1:18084)
//
// Run from test/e2e: npm run test:entity-graph-live

import { expect, test, type APIRequestContext } from "@playwright/test";
import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const SECRET = process.env.SYNANTON_JWT_SECRET ?? "local-real-demo-secret-at-least-32-bytes";
const SYNFLUX_URL = process.env.SYNFLUX_URL ?? "http://127.0.0.1:18090";
const TENANT = process.env.LOCAL_REAL_DEMO_TENANT ?? "local-real-demo";
const DOC = resolve(__dirname, "../../../evaluation/corpus/halcyon-7-directive.txt");
const SOURCE = `entity-graph-live-${Date.now()}`;

function base64url(value: string | Buffer): string {
  const buffer = typeof value === "string" ? Buffer.from(value) : value;
  return buffer.toString("base64url");
}

function token(): string {
  const header = base64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = base64url(
    JSON.stringify({
      sub: "entity-graph-live",
      tenant_id: TENANT,
      exp: Math.floor(Date.now() / 1000) + 3600,
    }),
  );
  const signature = createHmac("sha256", SECRET).update(`${header}.${payload}`).digest();
  return `${header}.${payload}.${base64url(signature)}`;
}

function authHeaders(): Record<string, string> {
  return { Authorization: `Bearer ${token()}` };
}

async function createSource(request: APIRequestContext): Promise<void> {
  const response = await request.post(`${SYNFLUX_URL}/v1/sources`, {
    headers: authHeaders(),
    data: {
      source_id: SOURCE,
      connector_id: "manual-upload",
      connector_type: "upload",
      source_endpoint: "upload://manual-upload",
      capability_flags: [],
    },
  });
  expect(response.status(), await response.text()).toBe(201);
}

async function uploadDocument(request: APIRequestContext): Promise<void> {
  const response = await request.post(`${SYNFLUX_URL}/v1/ingestion-runs`, {
    headers: {
      ...authHeaders(),
      "Idempotency-Key": `entity-graph-live-${SOURCE}`,
    },
    multipart: {
      connector_id: "manual-upload",
      source_id: SOURCE,
      resource_id: "halcyon-7-directive.txt",
      file: {
        name: "halcyon-7-directive.txt",
        mimeType: "text/plain",
        buffer: readFileSync(DOC),
      },
    },
  });
  expect(response.status(), await response.text()).toBe(202);
}

async function waitForPipeline(request: APIRequestContext): Promise<void> {
  const deadline = Date.now() + 300_000;
  let last = "";
  while (Date.now() < deadline) {
    const response = await request.get(`${SYNFLUX_URL}/v1/sources/${SOURCE}/status`, {
      headers: authHeaders(),
    });
    expect(response.status(), await response.text()).toBe(200);
    const body = await response.json();
    last = JSON.stringify(body);
    if (
      body.extraction_status === "KNOWLEDGE_READY" &&
      body.graph_status === "GRAPH_COMMITTED" &&
      body.resolution_status === "COMPLETED"
    ) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 3_000));
  }
  throw new Error(`pipeline did not complete within 300s; last status: ${last}`);
}

test("live Entity Graph journey from upload to evidence", async ({ page, request }) => {
  await createSource(request);
  await uploadDocument(request);
  await waitForPipeline(request);

  const session = {
    username: "entity-graph-live",
    tenantId: TENANT,
    token: token(),
    uid: 1,
    gids: [],
    exp: Math.floor(Date.now() / 1000) + 3600,
  };
  await page.addInitScript((value) => {
    sessionStorage.setItem("syntology-auth", JSON.stringify(value));
  }, session);

  await page.goto("/entity-graph?entity=entity-auxiliary-coolant-loop");
  await expect(page.getByRole("heading", { name: "Entity Graph" })).toBeVisible();
  await expect(page.getByRole("img", { name: /Entity graph for auxiliary coolant loop/ })).toBeVisible();

  const edge = page.getByRole("button", {
    name: /Outgoing tested_every to ninety days/,
  });
  await expect(edge).toBeVisible();
  await edge.click();

  const evidence = page.getByTestId("entity-evidence-item").first();
  await expect(evidence).toBeVisible();
  await expect(evidence).toContainText(/[0-9a-f-]{36}:0/);
  await expect(evidence).toContainText(/source [0-9a-f-]{36}/);
  await expect(evidence).toContainText("offsets");
});
