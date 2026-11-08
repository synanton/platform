import { createHmac } from "node:crypto";
import { test, expect } from "@playwright/test";

const SYNFLUX_URL = process.env.SYNFLUX_BASE_URL ?? "http://localhost:8090";
const SECRET = process.env.SYNANTON_JWT_SECRET;
const TENANT = process.env.EXTRACTION_PREVIEW_TENANT ?? "demo";
const EXPECTED_ENGINE = process.env.EXTRACTION_EXPECT_ENGINE;

function base64url(value: string): string {
  return Buffer.from(value, "utf8").toString("base64url");
}

function mintJwt(tenant: string, secret: string): string {
  const header = base64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = base64url(
    JSON.stringify({
      sub: "e2e-extraction",
      tenant_id: tenant,
      exp: Math.floor(Date.now() / 1000) + 300,
    })
  );
  const signature = createHmac("sha256", secret)
    .update(`${header}.${payload}`)
    .digest("base64url");
  return `${header}.${payload}.${signature}`;
}

const TOKEN =
  process.env.EXTRACTION_PREVIEW_TOKEN ??
  (SECRET ? mintJwt(TENANT, SECRET) : undefined);

test.describe("extraction preview", () => {
  test.skip(
    !TOKEN,
    "set SYNANTON_JWT_SECRET (or EXTRACTION_PREVIEW_TOKEN) to run against synflux"
  );

  test("returns Halcyon-7 and the requires relation", async ({ request }) => {
    const response = await request.post(
      `${SYNFLUX_URL}/api/v1/extraction/preview`,
      {
        headers: { Authorization: `Bearer ${TOKEN}` },
        data: { text: "Halcyon-7 requires a helium leak check." },
      }
    );
    expect(response.status(), await response.text()).toBe(200);

    const body = await response.json();
    expect(
      body.preEntities.some((entity: { surfaceForm: string }) =>
        entity.surfaceForm.includes("Halcyon-7")
      )
    ).toBe(true);
    expect(
      body.relations.some(
        (relation: { predicate: string }) => relation.predicate === "requires"
      )
    ).toBe(true);
    if (EXPECTED_ENGINE) {
      expect(body.engine).toBe(EXPECTED_ENGINE);
    }
  });
});
