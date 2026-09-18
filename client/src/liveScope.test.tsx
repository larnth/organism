import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { GameApiError } from "./api/client";
import type { GameProjection } from "./api/contracts";
import { observerProjection } from "./test/fixtures";

class Socket {
  static all: Socket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  close = vi.fn();
  constructor() { Socket.all.push(this); }
  snapshot(projection: GameProjection) {
    this.onmessage?.({ data: JSON.stringify({ type: "snapshot", version: 1, revision: projection.revision, projection }) });
  }
}
const action = { actionId: "plan", kind: "choose-action-type", label: "Plan move actions" };
const alice: GameProjection = { ...observerProjection, viewer: { player: "alice", role: "player", canAct: true }, legalActions: [action] };
const bob: GameProjection = { ...alice, viewer: { ...alice.viewer, player: "bob" } };
const label = () => screen.getByRole("button", { name: action.label });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}
beforeEach(() => { vi.stubGlobal("WebSocket", Socket); });
afterEach(() => { vi.unstubAllGlobals(); Socket.all = []; sessionStorage.clear(); });

describe("authenticated live-table scope", () => {
  it("binds pending commands to the displayed incarnation, not a reusable name", async () => {
    let current = { ...alice, instanceId: "first-instance" };
    const submitAction = vi.fn().mockRejectedValue(new Error("offline"));
    render(<App gameId="pond-life" loadGame={async () => current} submitAction={submitAction} pollInterval={20} />);
    fireEvent.click(await screen.findByRole("button", { name: action.label }));
    await screen.findByRole("button", { name: "Retry same action" });
    expect(submitAction.mock.calls[0][4]).toBe("first-instance");
    current = { ...alice, revision: 0, instanceId: "replacement-instance" };
    await waitFor(() => expect(screen.getByText(/Revision 0/)).toBeInTheDocument());
    expect(label()).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Retry same action" })).not.toBeInTheDocument();
  });

  it("retires the old account's socket after an HTTP identity change", async () => {
    const privateMessage = { id: "private", player: "alice", time: 123, message: "Alice-only discussion" };
    let current: GameProjection = { ...alice, chat: [privateMessage] };
    render(<App gameId="pond-life" loadGame={async () => current} pollInterval={20} />);
    await screen.findByRole("button", { name: action.label });
    fireEvent.click(screen.getByRole("button", { name: "Scores, history, help and discussion" }));
    expect(screen.getByText(privateMessage.message)).toBeInTheDocument();
    const old = Socket.all[0];
    const oldMessage = old.onmessage;
    current = { ...bob, chat: [] };
    await waitFor(() => expect(screen.queryByText(privateMessage.message)).not.toBeInTheDocument());
    act(() => oldMessage?.({ data: JSON.stringify({ type: "snapshot", version: 1, revision: 99, projection: { ...alice, revision: 99, chat: [privateMessage] } }) }));
    expect(screen.queryByText(privateMessage.message)).not.toBeInTheDocument();
    expect(old.close).toHaveBeenCalled();
    expect(Socket.all.length).toBeGreaterThan(1);
  });

  it("keeps an unresolved command only in its original account's storage", async () => {
    let current = alice;
    const submitAction = vi.fn().mockRejectedValue(new Error("offline"));
    render(<App gameId="pond-life" loadGame={async () => current} submitAction={submitAction} pollInterval={20} />);
    fireEvent.click(await screen.findByRole("button", { name: action.label }));
    await screen.findByRole("button", { name: "Retry same action" });
    const original = submitAction.mock.calls[0];
    current = bob;
    await waitFor(() => expect(label()).toBeEnabled());
    expect(screen.queryByRole("button", { name: "Retry same action" })).not.toBeInTheDocument();
    expect(sessionStorage.getItem("organism:pending:pond-life:alice")).not.toBeNull();
    expect(submitAction).toHaveBeenCalledTimes(1);
    current = alice;
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(submitAction).toHaveBeenCalledTimes(2));
    expect(submitAction.mock.calls[1]).toEqual(original);
  });

  it("rejects a late command response from the previous identity", async () => {
    let current = alice;
    const old = deferred<GameProjection>();
    const submitAction = vi.fn().mockReturnValueOnce(old.promise).mockRejectedValueOnce(new Error("offline"));
    render(<App gameId="pond-life" loadGame={async () => current} submitAction={submitAction} pollInterval={20} />);
    fireEvent.click(await screen.findByRole("button", { name: action.label }));
    current = bob;
    await waitFor(() => expect(label()).toBeEnabled());
    fireEvent.click(label());
    await screen.findByRole("button", { name: "Retry same action" });
    await act(async () => old.resolve({ ...alice, revision: 99 }));
    expect(screen.queryByText(/Revision 99/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry same action" })).toBeInTheDocument();
    expect(sessionStorage.getItem("organism:pending:pond-life:alice")).not.toBeNull();
    expect(sessionStorage.getItem("organism:pending:pond-life:bob")).not.toBeNull();
  });

  it("clears a deleted board and rejects late socket and command responses", async () => {
    let deleted = false;
    const old = deferred<GameProjection>();
    render(<App gameId="pond-life" loadGame={async () => {
      if (deleted) throw new GameApiError("not-found", 404);
      return alice;
    }} submitAction={() => old.promise} pollInterval={20} />);
    fireEvent.click(await screen.findByRole("button", { name: action.label }));
    const oldSocket = Socket.all[0];
    deleted = true;
    await screen.findByText("That game could not be found.");
    expect(screen.queryByRole("img", { name: "Pond-life game board" })).not.toBeInTheDocument();
    await act(async () => {
      oldSocket.snapshot({ ...alice, revision: 99 });
      old.resolve({ ...alice, revision: 99 });
    });
    expect(screen.queryByRole("button", { name: action.label })).not.toBeInTheDocument();
    expect(screen.queryByText(/Revision 99/)).not.toBeInTheDocument();
    expect(oldSocket.close).toHaveBeenCalled();
  });

  it("uses delayed socket projections only to refresh current HTTP authority", async () => {
    let current: GameProjection = { ...alice, viewer: { ...alice.viewer, canAct: false } };
    render(<App gameId="pond-life" loadGame={async () => current} pollInterval={10000} />);
    await screen.findByText(/Revision 8/);
    const stale = current;
    current = alice;
    await act(async () => Socket.all[0].snapshot(alice));
    expect(label()).toBeEnabled();
    await act(async () => Socket.all[0].snapshot(stale));
    expect(label()).toBeEnabled();
  });
});
