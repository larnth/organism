import { describe, expect, it, vi } from "vitest";

import { fetchCatchUp, fetchGame, gameSocketUrl, submitCommand } from "./client";

const projection = {
  gameId: "pond-life",
  revision: 4,
  status: "active",
  viewer: { player: "alice", role: "player", canAct: true },
  invocation: { players: ["alice", "bob"] },
  game: { state: { round: 2 } },
  legalActions: [],
  historySummary: { entries: 5, currentIndex: 4 },
  chat: [],
};

describe("modern game API", () => {
  it("loads a recipient-scoped game projection", async () => {
    const request = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(projection), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(fetchGame("pond life", request)).resolves.toEqual(projection);
    expect(request).toHaveBeenCalledWith(
      "/api/v1/organism/games/pond%20life",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("surfaces safe API errors", async () => {
    const request = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: "game-not-found" }), {
        status: 404,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(fetchGame("missing", request)).rejects.toThrow("game-not-found");
  });

  it("builds a websocket URL from the current browser origin", () => {
    expect(gameSocketUrl("pond life", new URL("https://play.example/game"))).toBe(
      "wss://play.example/ws/organism/play/pond%20life",
    );
    expect(gameSocketUrl("pond", new URL("http://localhost:5173"))).toBe(
      "ws://localhost:5173/ws/organism/play/pond",
    );
  });

  it("requests ordered changes after the current revision", async () => {
    const catchUp = {
      gameId: "pond-life",
      fromRevision: 4,
      toRevision: 5,
      events: [{ type: "game.updated", version: 1, revision: 5, projection }],
    };
    const request = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(catchUp), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(fetchCatchUp("pond-life", 4, request)).resolves.toEqual(catchUp);
    expect(request).toHaveBeenCalledWith(
      "/api/v1/organism/games/pond-life?afterRevision=4",
      expect.objectContaining({ headers: { Accept: "application/json" } }),
    );
  });

  it("submits one revision-safe idempotent command", async () => {
    const request = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ...projection, revision: 5 }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await submitCommand("pond life", "move-action", 4, request, "command-123");

    expect(request).toHaveBeenCalledWith(
      "/api/v1/organism/games/pond%20life/commands",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          actionId: "move-action",
          expectedRevision: 4,
          commandId: "command-123",
        }),
      }),
    );
  });
});
