import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Chat } from "./Chat";

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe("Chat", () => {
  it("uses the canonical search API with the session token", async () => {
    sessionStorage.setItem("syntology-auth", JSON.stringify({
      username: "alice", tenantId: "demo", token: "jwt-token", uid: 1, gids: [], exp: 9999999999,
    }));
    const fetchMock = vi.fn().mockResolvedValue({
      status: 200,
      ok: true,
      json: async () => ({
        results: [{
          object_id: "ref:0", score: 1.25, title: "Halcyon-7",
          snippet: "Halcyon-7 requires a helium leak check.",
        }],
        metadata: { strategy: "bm25", configuration_generation: 1 },
      }),
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<Chat />);
    fireEvent.change(screen.getByPlaceholderText("Ask the corpus…"), {
      target: { value: "Halcyon" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));

    await screen.findByText("Halcyon-7");
    expect(fetchMock).toHaveBeenCalledWith("/synquest/v1/search", expect.objectContaining({
      method: "POST",
      headers: expect.objectContaining({ Authorization: "Bearer jwt-token" }),
    }));
    await waitFor(() => expect(screen.getByText(/ref:0/)).toBeInTheDocument());
  });
});
