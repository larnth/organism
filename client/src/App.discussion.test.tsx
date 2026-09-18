import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import type { GameProjection } from "./api/contracts";
import { observerProjection } from "./test/fixtures";

class Socket {
  static current: Socket;
  onmessage: ((event: { data: string }) => void) | null = null;
  close = vi.fn();
  constructor() { Socket.current = this; }
  snapshot(projection: GameProjection) {
    this.onmessage?.({ data: JSON.stringify({ type: "snapshot", version: 1, revision: projection.revision, projection }) });
  }
}
const waiting: GameProjection = {
  ...observerProjection, instanceId: "original-table", status: "waiting", game: null, revision: 0,
  viewer: { player: "alice", role: "player", canAct: false },
  lobby: { owner: "alice", visibility: "private", member: true, readiness: {}, canStart: false },
};
const active: GameProjection = { ...observerProjection, instanceId: waiting.instanceId, revision: 0, viewer: waiting.viewer, lobby: null };
const text = "Keep this through launch";
let serverProjection = waiting;
const loadGame = async (gameId: string) => ({ ...serverProjection, gameId });
const request = vi.fn<typeof fetch>();
const messageInput = () => screen.getByRole("textbox", { name: "Message" });
const draft = () => fireEvent.change(messageInput(), { target: { value: text } });
const openDetails = () => fireEvent.click(screen.getByRole("button", { name: "Scores, history, help and discussion" }));
const snapshot = (projection: GameProjection) => act(async () => {
  serverProjection = projection;
  Socket.current.snapshot(projection);
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}
function acknowledgement() {
  const call = request.mock.calls.find(([url]) => String(url).endsWith("/chat"))!;
  const body = JSON.parse(String(call[1]?.body));
  return { id: "durable", player: "alice", time: 123, message: body.message, "client-id": body.clientId };
}
async function openLobby() {
  const view = render(<App gameId={waiting.gameId} loadGame={loadGame} />);
  await act(async () => Socket.current.snapshot(waiting));
  return view;
}
beforeEach(() => {
  serverProjection = waiting;
  vi.stubGlobal("WebSocket", Socket);
  request.mockReset().mockImplementation(async () => new Response(JSON.stringify({
    player: "alice", defaults: { "player-count": 2, "ring-count": 6, players: ["alice", "bob"], visibility: "private" }, bots: [],
    limits: { playerCounts: [2], ringCounts: [6], gameNameMaxLength: 120, chatMaxLength: 1000 },
  })));
  vi.stubGlobal("fetch", request);
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("App discussion across launch", () => {
  it("retires a same-name waiting table even when account and membership are unchanged", async () => {
    await openLobby();
    const response = deferred<Response>();
    request.mockReturnValueOnce(response.promise);
    draft();
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    const ack = acknowledgement();
    await snapshot({ ...waiting, instanceId: "replacement-table" });
    expect(messageInput()).toHaveValue("");
    expect(messageInput()).not.toHaveAttribute("readonly");
    await act(async () => response.resolve(new Response(JSON.stringify({ message: ack }))));
    expect(screen.queryByText(text)).not.toBeInTheDocument();
  });
  it("preserves an unsent draft across the real waiting-to-active branch", async () => {
    const store = vi.spyOn(Storage.prototype, "setItem");
    await openLobby();
    draft();
    await snapshot(active);
    expect(screen.queryByRole("button", { name: "Ready up" })).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Pond-life game board" })).toBeInTheDocument();
    openDetails();
    expect(messageInput()).toHaveValue(text);
    expect(request.mock.calls.filter(([url]) => String(url).endsWith("/chat"))).toHaveLength(0);
    expect(store).not.toHaveBeenCalled();
  });

  it("preserves an unresolved send across launch and accepts its exact HTTP acknowledgement", async () => {
    await openLobby();
    const response = deferred<Response>();
    request.mockReturnValueOnce(response.promise);
    draft();
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    const ack = acknowledgement();
    await snapshot(active);
    openDetails();
    expect(messageInput()).toHaveValue(text);
    expect(messageInput()).toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    await act(async () => response.resolve(new Response(JSON.stringify({ message: ack }))));
    expect(messageInput()).toHaveValue("");
    expect(screen.getByText("Message sent.")).toBeInTheDocument();
    expect(screen.getAllByText(text)).toHaveLength(1);
    await snapshot({ ...active, chat: [ack, ack] });
    expect(screen.getAllByText(text)).toHaveLength(1);
  });

  it("keeps the original timeout and retries the exact delivery after launch", async () => {
    vi.useFakeTimers();
    await openLobby();
    const response = deferred<Response>();
    request.mockReturnValueOnce(response.promise);
    draft();
    fireEvent.submit(screen.getByRole("form", { name: "Send a message" }));
    const ack = acknowledgement();
    await act(async () => { await vi.advanceTimersByTimeAsync(6000); });
    await snapshot(active);
    openDetails();
    await act(async () => { await vi.advanceTimersByTimeAsync(4000); });
    expect(messageInput()).toHaveValue(text);
    expect(screen.getByText("Confirmation is taking longer than expected. Retry this message safely.")).toBeInTheDocument();
    request.mockResolvedValueOnce(new Response(JSON.stringify({ message: ack })));
    await act(async () => fireEvent.click(screen.getByRole("button", { name: "Retry message" })));
    const posts = request.mock.calls.filter(([url]) => String(url).endsWith("/chat"));
    expect(posts).toHaveLength(2);
    expect(posts[1]).toEqual(posts[0]);
    expect(messageInput()).toHaveValue("");
    await act(async () => response.resolve(new Response(JSON.stringify({ message: ack }))));
    expect(screen.getAllByText(text)).toHaveLength(1);
  });

  it("requires the exact live client ID after launch and ignores a later failed request", async () => {
    await openLobby();
    const response = deferred<Response>();
    request.mockReturnValueOnce(response.promise);
    draft();
    fireEvent.submit(screen.getByRole("form", { name: "Send a message" }));
    const ack = acknowledgement();
    await snapshot({ ...active, chat: [{ ...ack, "client-id": "other-delivery" }] });
    openDetails();
    expect(messageInput()).toHaveValue(text);
    await snapshot({ ...active, chat: [ack, ack] });
    expect(messageInput()).toHaveValue("");
    expect(screen.getAllByText(text)).toHaveLength(1);
    fireEvent.change(messageInput(), { target: { value: "Next draft" } });
    await act(async () => response.resolve(new Response(JSON.stringify({ error: "offline" }), { status: 503 })));
    expect(messageInput()).toHaveValue("Next draft");
    expect(screen.queryByRole("button", { name: "Retry message" })).not.toBeInTheDocument();
  });

  it.each(["before", "after"])("retains a send rejected %s launch and retries the same ID", async timing => {
    await openLobby();
    const response = deferred<Response>();
    request.mockReturnValueOnce(response.promise);
    draft();
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    const ack = acknowledgement();
    const fail = () => act(async () => response.resolve(new Response(JSON.stringify({ error: "offline" }), { status: 503 })));
    if (timing === "before") await fail();
    await snapshot(active);
    openDetails();
    if (timing === "after") await fail();
    expect(messageInput()).toHaveValue(text);
    expect(screen.getByText("offline")).toBeInTheDocument();
    request.mockResolvedValueOnce(new Response(JSON.stringify({ message: ack })));
    await act(async () => fireEvent.click(screen.getByRole("button", { name: "Retry message" })));
    const posts = request.mock.calls.filter(([url]) => String(url).endsWith("/chat"));
    expect(posts).toHaveLength(2);
    expect(posts[1]).toEqual(posts[0]);
    expect(messageInput()).toHaveValue("");
  });

  it.each(["membership", "account", "game"])("discards an unsent draft at the %s boundary", async boundary => {
    const view = await openLobby();
    draft();
    if (boundary === "game") {
      view.rerender(<App gameId="another-table" loadGame={loadGame} />);
      await snapshot({ ...waiting, gameId: "another-table" });
      view.rerender(<App gameId={waiting.gameId} loadGame={loadGame} />);
    } else {
      await snapshot({ ...waiting,
        viewer: { ...waiting.viewer, player: boundary === "account" ? "bob" : "alice" },
        lobby: { ...waiting.lobby!, member: boundary === "account" },
      });
    }
    expect(screen.queryByDisplayValue(text)).not.toBeInTheDocument();
    await snapshot(waiting);
    expect(messageInput()).toHaveValue("");
    expect(request.mock.calls.filter(([url]) => String(url).endsWith("/chat"))).toHaveLength(0);
  });

  it.each(["membership", "posting", "account", "logout", "game"] as const)(
    "discards private cached chat and pending delivery at the %s boundary, including on return",
    async boundary => {
      const view = await openLobby();
      request.mockImplementationOnce(async (_url, init) => {
        const body = JSON.parse(String(init?.body));
        return new Response(JSON.stringify({ message: { id: "private", player: "alice", time: 120, message: body.message, "client-id": body.clientId } }));
      });
      draft();
      await act(async () => fireEvent.click(screen.getByRole("button", { name: "Send" })));
      expect(screen.getByText(text)).toBeInTheDocument();
      const response = deferred<Response>();
      request.mockReturnValueOnce(response.promise);
      fireEvent.change(messageInput(), { target: { value: "Unconfirmed private message" } });
      fireEvent.click(screen.getByRole("button", { name: "Send" }));
      const posts = request.mock.calls.filter(([url]) => String(url).endsWith("/chat"));
      const body = JSON.parse(String(posts[1][1]?.body));

      if (boundary === "game") {
        serverProjection = { ...active, gameId: "another-table" };
        view.rerender(<App gameId="another-table" loadGame={loadGame} />);
        await snapshot({ ...active, gameId: "another-table" });
        openDetails();
      } else if (boundary === "posting") {
        await snapshot({ ...active, viewer: { ...active.viewer, role: "observer" } });
        openDetails();
      } else {
        await snapshot({ ...waiting,
          viewer: { player: boundary === "logout" ? null : boundary === "account" ? "bob" : "alice", role: boundary === "account" ? "player" : "observer", canAct: false },
          lobby: { ...waiting.lobby!, member: boundary === "account" },
        });
      }
      expect(screen.queryByText(text)).not.toBeInTheDocument();
      expect(screen.queryByDisplayValue("Unconfirmed private message")).not.toBeInTheDocument();
      if (boundary === "membership" || boundary === "posting" || boundary === "logout") expect(screen.queryByRole("textbox", { name: "Message" })).not.toBeInTheDocument();

      // Returning to the original identity must not resurrect its old state.
      if (boundary === "game") {
        serverProjection = waiting;
        view.rerender(<App gameId={waiting.gameId} loadGame={loadGame} />);
      }
      await snapshot(waiting);
      expect(messageInput()).toHaveValue("");
      fireEvent.change(messageInput(), { target: { value: "New scope draft" } });
      await act(async () => response.resolve(new Response(JSON.stringify({ message: {
        id: "late", player: "alice", time: 124, message: body.message, "client-id": body.clientId,
      } }))));
      expect(messageInput()).toHaveValue("New scope draft");
      expect(screen.queryByText("Unconfirmed private message")).not.toBeInTheDocument();
      expect(screen.queryByText(text)).not.toBeInTheDocument();
      request.mockImplementationOnce(async (_url, init) => {
        const next = JSON.parse(String(init?.body));
        expect(next.clientId).not.toBe(body.clientId);
        return new Response(JSON.stringify({ message: { id: "new", player: "alice", time: 125, message: next.message, "client-id": next.clientId } }));
      });
      await act(async () => fireEvent.click(screen.getByRole("button", { name: "Send" })));
      expect(messageInput()).toHaveValue("");
      expect(screen.getByText("New scope draft")).toBeInTheDocument();
    },
  );
});
