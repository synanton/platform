// Playwright tests use route interception to mock /api/v1/entities/{id}/neighbors.
// A real E2E against deployed Relix is deferred to #589 activation.

import { expect, test, type Page } from "@playwright/test";

const session = {
  username: "entity-graph-e2e",
  tenantId: "demo",
  token: "mock-ui-token",
  uid: 1,
  gids: [],
  exp: Math.floor(Date.now() / 1000) + 3600,
};

const center = { id: "entity-1", label: "Halcyon-7", type: "MaintenanceSystem" };
const neighbors = [
  {
    entity: { id: "alpha", label: "Alpha", type: "Protocol" },
    relation: "requires",
    direction: "OUTGOING",
    canonical_relation_id: "rel-out",
  },
  {
    entity: { id: "beta", label: "Beta", type: "Organization" },
    relation: "supports",
    direction: "INCOMING",
    canonical_relation_id: "rel-in",
  },
];
const evidence = [
  {
    relation: "requires",
    neighbor_entity_id: "alpha",
    chunk_id: "chunk-1",
    source_version: "version-1",
    offset_start: 4,
    offset_end: 12,
    canonical_relation_id: "rel-out",
  },
  {
    relation: "supports",
    neighbor_entity_id: "beta",
    chunk_id: "chunk-2",
    source_version: "version-2",
    offset_start: 20,
    offset_end: 28,
    canonical_relation_id: "rel-in",
  },
];

async function authenticate(page: Page) {
  await page.addInitScript((value) => {
    sessionStorage.setItem("syntology-auth", JSON.stringify(value));
  }, session);
}

async function mockNeighbors(page: Page, body: unknown, status = 200, delayMs = 0) {
  await page.route("**/api/v1/entities/**/neighbors", async (route) => {
    if (delayMs) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
    await route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
}

test.beforeEach(async ({ page }) => {
  await authenticate(page);
});

test("renders bidirectional edges and edge evidence", async ({ page }) => {
  await mockNeighbors(page, {
    entity: center,
    neighbors,
    evidence,
    truncated: false,
  });
  await page.goto("/entity-graph?entity=entity-1");

  await expect(page.getByRole("heading", { name: "Entity Graph" })).toBeVisible();
  await expect(page.getByTestId("entity-edge-OUTGOING-rel-out-alpha")).toBeVisible();
  await expect(page.getByTestId("entity-edge-INCOMING-rel-in-beta")).toBeVisible();

  await page.getByTestId("entity-edge-OUTGOING-rel-out-alpha").click();
  await expect(page.getByText("chunk-1")).toBeVisible();
  await expect(page.getByText("source version-1")).toBeVisible();

  await page.getByTestId("entity-edge-INCOMING-rel-in-beta").click();
  await expect(page.getByText("chunk-2")).toBeVisible();
  await expect(page.getByText("source version-2")).toBeVisible();
});

test("renders the empty state", async ({ page }) => {
  await mockNeighbors(page, {
    entity: center,
    neighbors: [],
    evidence: [],
    truncated: false,
  });
  await page.goto("/entity-graph?entity=entity-1");
  await expect(page.getByTestId("entity-graph-empty")).toHaveText("No neighbors for this entity.");
});

test("renders a retryable error state", async ({ page }) => {
  await mockNeighbors(page, { error: "boom" }, 503);
  await page.goto("/entity-graph?entity=entity-1");
  await expect(page.getByTestId("entity-graph-error")).toHaveText("Could not load neighbors.");
  await expect(page.getByRole("button", { name: "Retry" })).toBeVisible();
});

test("maps an unauthorized proxy response to the auth error", async ({ page }) => {
  await mockNeighbors(page, { error: "unauthorized" }, 401);
  await page.goto("/entity-graph?entity=entity-1");
  await expect(page.getByTestId("entity-graph-error")).toHaveText(
    "Sign in again to view the graph.",
  );
});

test("renders loading and 50-neighbor truncation", async ({ page }) => {
  const fifty = Array.from({ length: 50 }, (_, index) => ({
    entity: { id: `n-${index}`, label: `N ${index}`, type: "Concept" },
    relation: "relates_to",
    direction: "OUTGOING",
    canonical_relation_id: `rel-${index}`,
  }));
  await mockNeighbors(
    page,
    { entity: center, neighbors: fifty, evidence: [], truncated: true },
    200,
    800,
  );
  await page.goto("/entity-graph?entity=entity-1");
  await expect(page.getByTestId("entity-graph-loading")).toBeVisible();
  await expect(page.getByTestId("entity-graph-truncated")).toContainText(
    "Showing the first 50 neighbors; more are available.",
  );
});
