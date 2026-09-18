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
  it("fences a command to the displayed game incarnation", async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify(projection)));
    await submitCommand("pond-life", "move-action", 4, request, "command-123", "opaque-instance");
    expect(JSON.parse(request.mock.calls[0][1].body).expectedInstanceId).toBe("opaque-instance");
  });

  it("loads a recipient-scoped game projection", async () => {
    const request = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ...projection, gameId: "pond life" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(fetchGame("pond life", request)).resolves.toEqual({ ...projection, gameId: "pond life" });
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
      "wss://play.example/api/v1/organism/games/pond%20life/events",
    );
    expect(gameSocketUrl("pond", new URL("http://localhost:5173"))).toBe(
      "ws://localhost:5173/api/v1/organism/games/pond/events",
    );
  });

  it("requests ordered changes after the current revision", async () => {
    const catchUp = {
      gameId: "pond-life",
      fromRevision: 4,
      toRevision: 5,
      events: [{ type: "game.updated", version: 1, revision: 5, projection: { ...projection, revision: 5 } }],
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
      new Response(JSON.stringify({ ...projection, gameId: "pond life", revision: 5 }), {
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

  it("submits a compound source and destination as one atomic command", async () => {
    const request = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ...projection, gameId: "pond life", revision: 5 }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    await submitCommand(
      "pond life",
      ["move-source", "move-destination"],
      4,
      request,
      "command-456",
    );

    expect(request).toHaveBeenCalledWith(
      "/api/v1/organism/games/pond%20life/commands",
      expect.objectContaining({
        body: JSON.stringify({
          actionId: ["move-source", "move-destination"],
          expectedRevision: 4,
          commandId: "command-456",
        }),
      }),
    );
  });

  it.each([
    { revision: -1 },
    { revision: 1.5 },
    { status: "unknown" },
    { viewer: { player: "alice", role: "player", canAct: "yes" } },
    { invocation: [] },
    { legalActions: "move" },
    { legalActions: [{ actionId: "move", kind: "move-from", nextActions: [{ actionId: 7 }] }] },
    { historySummary: { entries: 5, currentIndex: -1 } },
    { chat: {} },
  ])("rejects malformed projection data before rendering: %j", async (invalid) => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...projection, ...invalid })));
    await expect(fetchGame("pond-life", request)).rejects.toThrow(/invalid.*game.*data/i);
  });

  it("rejects a valid-looking projection for a different game", async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...projection, gameId: "another-game" })));
    await expect(fetchGame("pond-life", request)).rejects.toThrow(/invalid.*game.*data/i);
  });

  it("rejects a catch-up event with mismatched revision", async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      gameId: "pond-life", fromRevision: 3, toRevision: 4,
      events: [{ type: "game.updated", version: 1, revision: 4, projection: { ...projection, revision: 3 } }],
    })));
    await expect(fetchCatchUp("pond-life", 3, request)).rejects.toThrow(/invalid.*game.*data/i);
  });

  it("rejects non-JSON success pages instead of treating them as game data", async () => {
    const request = vi.fn().mockResolvedValue(new Response("<html>Sign in</html>"));
    await expect(fetchGame("pond-life", request)).rejects.toThrow(/invalid.*game.*data/i);
  });
});
