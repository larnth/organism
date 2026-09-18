import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { GameApiError } from "./api/client";
import { observerProjection } from "./test/fixtures";

class Socket {
  static all: Socket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  close = vi.fn();
  constructor(readonly url: string) { Socket.all.push(this); }
  snapshot(projection: typeof observerProjection) {
    this.onmessage?.({ data: JSON.stringify({ type: "snapshot", version: 1, revision: projection.revision, projection }) });
  }
}
const action = { actionId: "plan", kind: "choose-action-type", label: "Plan move actions" };
const player = { ...observerProjection, viewer: { player: "alice", role: "player" as const, canAct: true }, legalActions: [action] };
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
function socket() { return Socket.all.at(-1)!; }
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); Socket.all = []; sessionStorage.clear(); });

describe("live JSON table", () => {

  it("offers an exact retry when confirmation never arrives", async () => {
    vi.useFakeTimers();
    const submitAction = vi.fn(() => new Promise<typeof player>(() => undefined));
    render(<App gameId="pond-life" initialProjection={player} submitAction={submitAction} />);
    fireEvent.click(screen.getByRole("button", { name: action.label }));
    await act(async () => { await vi.advanceTimersByTimeAsync(15000); });
    fireEvent.click(screen.getByRole("button", { name: "Retry same action" }));
    expect(submitAction.mock.calls[1]).toEqual(submitAction.mock.calls[0]);
    expect(screen.getByRole("button", { name: action.label })).toBeDisabled();
  });
  it("recovers an unresolved command after remount without assigning a new revision or ID", async () => {
    const submitAction = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce({ ...player, revision: 9 });
    const first = render(<App gameId="pond-life" initialProjection={player} submitAction={submitAction} />);
    fireEvent.click(screen.getByRole("button", { name: action.label }));
    await screen.findByRole("button", { name: "Retry same action" });
    first.unmount();
    render(<App gameId="pond-life" initialProjection={{ ...player, revision: 10 }} submitAction={submitAction} />);
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(submitAction).toHaveBeenCalledTimes(2));
    expect(submitAction.mock.calls[1]).toEqual(submitAction.mock.calls[0]);
    expect(screen.getByText(/Revision 10/)).toBeInTheDocument();
  });
  it("automatically replaces every open lobby with the board on a launch snapshot", async () => {
    vi.stubGlobal("WebSocket", Socket);
    const waiting = { ...player, status: "waiting" as const, game: null, revision: 0, viewer: { ...player.viewer, canAct: false }, lobby: { owner: "alice", visibility: "open" as const, member: true, readiness: {}, canStart: false }, legalActions: [] };
    let current = waiting as typeof observerProjection;
    render(<App gameId="pond-life" loadGame={async () => current} />);
    await screen.findByRole("button", { name: "Ready up" });
    current = { ...player, revision: 0, lobby: null };
    await act(async () => socket().snapshot(current));
    expect(screen.getByRole("img", { name: "Pond-life game board" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ready up" })).not.toBeInTheDocument();
  });

  it("submits bounded Undo through the same pending-command boundary", async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...player, revision: 9, viewer: { ...player.viewer, canUndo: false } })));
    vi.stubGlobal("fetch", request);
    render(<App gameId="pond-life" initialProjection={{ ...player, viewer: { ...player.viewer, canUndo: true } }} />);
    fireEvent.click(screen.getByRole("button", { name: "Scores, history, help and discussion" }));
    fireEvent.click(screen.getByRole("button", { name: "Undo last action" }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
    expect(JSON.parse(request.mock.calls[0][1].body)).toEqual({ operation: "undo", expectedRevision: 8, commandId: expect.any(String) });
    expect(await screen.findByText(/Revision 9/)).toBeInTheDocument();
  });
  it("opens the JSON socket and keeps a fresh authority read over delayed initial HTTP", async () => {
    vi.stubGlobal("WebSocket", Socket);
    const old = deferred<typeof observerProjection>();
    const loadGame = vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue({ ...observerProjection, revision: 12 });
    render(<App gameId="pond-life" loadGame={loadGame} />);
    await waitFor(() => expect(Socket.all).toHaveLength(1));
    expect(socket().url).toContain("/api/v1/organism/games/pond-life/events");
    await act(async () => socket().snapshot({ ...observerProjection, revision: 12 }));
    await act(async () => old.resolve(observerProjection));
    expect(screen.getByText(/Revision 12/)).toBeInTheDocument();
  });

  it("applies equal-revision ownership metadata without accepting lower revisions", async () => {
    vi.stubGlobal("WebSocket", Socket);
    let current = player;
    render(<App gameId="pond-life" loadGame={async () => current} />);
    await screen.findByRole("button", { name: action.label });
    current = { ...player, viewer: { ...player.viewer, canAct: false } };
    await act(async () => socket().snapshot(current));
    expect(screen.queryByRole("button", { name: action.label })).not.toBeInTheDocument();
    current = { ...player, revision: 7 };
    await act(async () => socket().snapshot(current));
    expect(screen.queryByRole("button", { name: action.label })).not.toBeInTheDocument();
  });

  it("retains exact command identity across an ambiguous failure and a newer snapshot", async () => {
    vi.stubGlobal("WebSocket", Socket);
    const submitAction = vi.fn().mockRejectedValueOnce(new Error("Network lost")).mockResolvedValueOnce({ ...player, revision: 9 });
    let current = player;
    render(<App gameId="pond-life" loadGame={async () => current} submitAction={submitAction} />);
    fireEvent.click(await screen.findByRole("button", { name: action.label }));
    await screen.findByRole("button", { name: /retry same action/i });
    current = { ...player, revision: 10 };
    await act(async () => socket().snapshot(current));
    expect(screen.getByRole("button", { name: action.label })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: /retry same action/i }));
    await waitFor(() => expect(submitAction).toHaveBeenCalledTimes(2));
    expect(submitAction.mock.calls[1]).toEqual(submitAction.mock.calls[0]);
    expect(submitAction.mock.calls[0][2]).toBe(8);
    expect(submitAction.mock.calls[0][3]).toEqual(expect.any(String));
    expect(screen.getByText(/Revision 10/)).toBeInTheDocument();
  });

  it("does not let a delayed conflict refresh overwrite a newer socket snapshot", async () => {
    vi.stubGlobal("WebSocket", Socket);
    const stale = deferred<typeof player>();
    const loadGame = vi.fn().mockResolvedValueOnce(player).mockReturnValueOnce(stale.promise).mockResolvedValue({ ...player, revision: 12 });
    render(<App gameId="pond-life" loadGame={loadGame} submitAction={async () => { throw new GameApiError("revision-conflict", 409); }} />);
    fireEvent.click(await screen.findByRole("button", { name: action.label }));
    await waitFor(() => expect(loadGame).toHaveBeenCalledTimes(2));
    await act(async () => socket().snapshot({ ...player, revision: 12 }));
    await act(async () => stale.resolve({ ...player, revision: 9 }));
    expect(screen.getByText(/Revision 12/)).toBeInTheDocument();
  });

  it("ignores callbacks from a retired socket and cleans up on unmount", async () => {
    vi.stubGlobal("WebSocket", Socket);
    let currentProjection = observerProjection;
    const { unmount } = render(<App gameId="pond-life" loadGame={async () => currentProjection} pollInterval={10} />);
    await screen.findByText(/Revision 8/);
    const old = socket();
    const oldMessage = old.onmessage;
    act(() => old.onclose?.());
    await waitFor(() => expect(Socket.all.length).toBeGreaterThan(1));
    currentProjection = { ...observerProjection, revision: 10 };
    await act(async () => socket().snapshot(currentProjection));
    act(() => oldMessage?.({ data: JSON.stringify({ type: "snapshot", version: 1, revision: 99, projection: { ...observerProjection, revision: 99 } }) }));
    expect(screen.getByText(/Revision 10/)).toBeInTheDocument();
    const current = socket();
    unmount();
    expect(current.close).toHaveBeenCalled();
  });
});
