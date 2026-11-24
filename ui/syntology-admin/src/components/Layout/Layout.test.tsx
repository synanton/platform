import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { Layout } from "./Layout";

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

describe("Layout regression", () => {
  it("keeps every #860 existing view plus Workflow in the navigation", () => {
    sessionStorage.setItem("syntology-auth", JSON.stringify({
      username: "alice", tenantId: "tenant-a", token: "token", uid: 1, gids: [], exp: 9999999999,
    }));

    render(
      <MemoryRouter>
        <Layout />
      </MemoryRouter>,
    );

    for (const label of ["Dashboard", "Viewer", "Entity Graph", "Admin", "Grants", "MCP", "Chat", "Settings", "Workflow"]) {
      expect(screen.getByRole("link", { name: label })).toBeInTheDocument();
    }
    expect(screen.getByTitle(/Tenant is fixed/)).toHaveTextContent("tenant tenant-a");
  });
});
