import { describe, expect, it, vi } from "vitest";
import { fetchHistory, fetchSession, postChat, updateLobby, submitUndo } from "./client";
import { decodeGameEvent, decodeProjection } from "./decode";
import { observerProjection } from "../test/fixtures";

const response = (value: unknown) => vi.fn().mockResolvedValue(new Response(JSON.stringify(value)));
const message = { type: "chat", id: "durable", player: "alice", time: 123, message: "Hello", "client-id": "delivery" };

describe("JSON lifecycle adapters", () => {
  it("accepts the engine's empty coordinate maps before initial placement", () => {
    const initial = { ...observerProjection, game: {
      rings: ["A", "B", "C"], adjacencies: {},
      state: { elements: {}, food: {}, "player-turn": { player: "orb" } },
    } };
    expect(decodeProjection(initial, "pond-life")).toEqual(initial);
    expect(() => decodeProjection({ ...initial, game: { state: { elements: { invalid: 1 } } } }, "pond-life")).toThrow(/invalid/i);
  });
  it.each([
    { game: { rings: [2] } },
    { game: { state: [] } },
    { game: { state: { elements: [[ ["red", 0], { type: 42 } ]] } } },
    { game: { adjacencies: [["red", []]] } },
    { invocation: { players: [null] } },
  ])("rejects malformed board fields instead of silently drawing an empty board: %j", fields => {
    expect(() => decodeProjection({ ...observerProjection, ...fields }, "pond-life")).toThrow(/invalid/i);
  });
  it("validates historical cursor and read-only authority", async () => {
    const history = { ...observerProjection, historyCursor: 2, viewer: { ...observerProjection.viewer, canUndo: false } };
    const request = response(history);
    await expect(fetchHistory("pond-life", 2, request)).resolves.toEqual(history);
    expect(request).toHaveBeenCalledWith("/api/v1/organism/games/pond-life/history/2", expect.objectContaining({ credentials: "same-origin" }));
    await expect(fetchHistory("pond-life", 2, response({ ...history, historyCursor: 3 }))).rejects.toThrow(/invalid/i);
    await expect(fetchHistory("pond-life", 2, response({ ...history, viewer: { ...history.viewer, canAct: true } }))).rejects.toThrow(/invalid/i);
  });
  it("uses the undo operation with a stable delivery ID", async () => {
    const request = response(observerProjection);
    await submitUndo("pond-life", 8, "undo-id", request);
    expect(JSON.parse(request.mock.calls[0][1].body)).toEqual({ operation: "undo", expectedRevision: 8, commandId: "undo-id" });
  });
  it("requires a matching durable chat acknowledgement", async () => {
    const request = response({ message });
    await expect(postChat("pond-life", "Hello", "delivery", request)).resolves.toEqual(message);
    expect(JSON.parse(request.mock.calls[0][1].body)).toEqual({ message: "Hello", clientId: "delivery" });
    await expect(postChat("pond-life", "Hello", "other", response({ message }))).rejects.toThrow(/invalid/i);
    await expect(postChat("pond-life", "Hello", "delivery", response({ message: { ...message, id: null } }))).rejects.toThrow(/invalid/i);
  });
  it("binds a chat delivery to its original opaque table lifetime", async () => {
    const request = response({ message });
    await postChat("pond-life", "Hello", "delivery", request, "original-table");
    expect(JSON.parse(request.mock.calls[0][1].body)).toEqual({
      message: "Hello", clientId: "delivery", expectedInstanceId: "original-table",
    });
  });
  it("posts lobby mutations to their exact suffix", async () => {
    const request = response(observerProjection);
    await updateLobby("pond-life", "seat", { index: 1, player: "helios-2" }, request);
    expect(request).toHaveBeenCalledWith("/api/v1/organism/lobbies/pond-life/seat", expect.objectContaining({ method: "POST", body: JSON.stringify({ index: 1, player: "helios-2" }) }));
  });
  it("validates authoritative session defaults and ranges", async () => {
    const session = { player: "alice", defaults: { "player-count": 2, "ring-count": 4, players: ["alice", ""], visibility: "open" }, limits: { playerCounts: [1, 2, 10], ringCounts: [3, 4, 7], gameNameMaxLength: 120, chatMaxLength: 1000 }, bots: [{ name: "helios", description: "Sun" }] };
    await expect(fetchSession(response(session))).resolves.toEqual(session);
    await expect(fetchSession(response({ ...session, limits: { ...session.limits, playerCounts: ["2"] } }))).rejects.toThrow(/invalid/i);
  });
  it("rejects malformed lobby permissions and chat rows", () => {
    const lobby = { owner: "alice", visibility: "open", member: true, readiness: {}, canStart: false, bots: [], blocker: null, availableBots: [] };
    expect(() => decodeProjection({ ...observerProjection, lobby: { ...lobby, availableBots: [{ player: 2 }] } }, "pond-life")).toThrow(/invalid/i);
    expect(() => decodeProjection({ ...observerProjection, chat: [{ id: "x", player: 2, message: [] }] }, "pond-life")).toThrow(/invalid/i);
    expect(decodeGameEvent({ type: "snapshot.unavailable", version: 1 }, "pond-life").type).toBe("snapshot.unavailable");
  });
});
