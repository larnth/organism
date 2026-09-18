import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CreateGamePage } from "./CreateGamePage";
import { LobbyPage } from "./LobbyPage";
import type { GameProjection, Session } from "../api/contracts";
import { observerProjection } from "../test/fixtures";

const session: Session = { player: "alice", defaults: { "player-count": 2, "ring-count": 4, players: ["alice", ""], visibility: "open", description: "" }, limits: { playerCounts: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10], ringCounts: [3, 4, 5, 6, 7], gameNameMaxLength: 120, chatMaxLength: 1000 }, bots: [{ name: "helios", description: "Sun" }] };
const lobby: GameProjection = { ...observerProjection, status: "waiting", game: null, revision: 0, viewer: { player: "alice", role: "player", canAct: false }, invocation: session.defaults, lobby: { owner: "alice", visibility: "open", member: true, readiness: {}, canStart: false, bots: [], blocker: "Waiting for players", availableBots: [{ name: "helios", player: "helios-2", description: "Sun" }] } };
const loadSession = async () => session;

describe("create and lobby", () => {
  it("binds existing-lobby operations to the rendered lifetime", async () => {
    const command = vi.fn().mockResolvedValue(undefined);
    render(<LobbyPage projection={{ ...lobby, instanceId: "original-table" }} loadSession={loadSession} command={command} />);
    fireEvent.click(screen.getByRole("button", { name: "Ready up" }));
    await waitFor(() => expect(command).toHaveBeenCalledWith("ready", { ready: true, expectedInstanceId: "original-table" }));
  });
  it("requires the actual session before publishing a private editable draft", async () => {
    const write = vi.fn();
    render(<CreateGamePage loadSession={async () => ({ ...session, player: null })} write={write} onCreated={vi.fn()} />);
    expect(await screen.findByRole("link", { name: "Sign in to create a game" })).toHaveAttribute("href", "/login?redirect=%2Fmodern%2F%3Fview%3Dcreate");
    expect(screen.queryByRole("button", { name: "Launch lobby" })).not.toBeInTheDocument();
    expect(write).not.toHaveBeenCalled();
  });
  it("uses all authoritative settings and does not publish until successful POST", async () => {
    let resolve!: (p: GameProjection) => void;
    const write = vi.fn(() => new Promise<GameProjection>(r => { resolve = r; }));
    const onCreated = vi.fn();
    render(<CreateGamePage loadSession={loadSession} write={write} onCreated={onCreated} />);
    await screen.findByRole("heading", { name: "Create your game" });
    fireEvent.change(screen.getByLabelText("Game name"), { target: { value: "Friday night bloom" } });
    fireEvent.change(screen.getByLabelText("Players"), { target: { value: "10" } });
    fireEvent.change(screen.getByLabelText("Field size"), { target: { value: "3" } });
    expect(write).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Launch lobby" }));
    expect(write).toHaveBeenCalledWith("Friday night bloom", "configure", { createOnly: true, invocation: expect.objectContaining({ "player-count": 10, "ring-count": 3, players: ["alice", "", "", "", "", "", "", "", "", ""] }) });
    expect(onCreated).not.toHaveBeenCalled();
    await act(async () => resolve({ ...lobby, gameId: "Friday night bloom" }));
    expect(onCreated).toHaveBeenCalledWith("Friday night bloom");
  });
  it("preserves the named draft after a rejected creation without retaining a password", async () => {
    const write = vi.fn().mockRejectedValue(new Error("Name already in use"));
    render(<CreateGamePage loadSession={loadSession} write={write} onCreated={vi.fn()} />);
    await screen.findByLabelText("Game name");
    fireEvent.change(screen.getByLabelText("Game name"), { target: { value: "Named table" } });
    fireEvent.click(screen.getByLabelText("Private game"));
    fireEvent.change(screen.getByLabelText("Lobby password"), { target: { value: "test-only-admission" } });
    fireEvent.click(screen.getByRole("button", { name: "Launch lobby" }));
    await screen.findByText(/Name already in use/);
    expect(screen.getByLabelText("Game name")).toHaveValue("Named table");
    expect(screen.getByLabelText("Lobby password")).toHaveValue("");
  });
  it("uses exact projected bot identities and never makes account seats editable", async () => {
    const command = vi.fn().mockResolvedValue(undefined);
    render(<LobbyPage projection={lobby} loadSession={loadSession} command={command} />);
    expect(screen.queryByRole("textbox", { name: /alice/ })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Bot for seat 2"), { target: { value: "helios-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Add bot to seat 2" }));
    await waitFor(() => expect(command).toHaveBeenCalledWith("seat", { index: 1, player: "helios-2" }));
    expect(screen.getByRole("button", { name: "Start game" })).toBeDisabled();
  });
  it("reflects equal-revision roster/readiness updates and posts explicit readiness", async () => {
    const command = vi.fn().mockResolvedValue(undefined);
    const { rerender } = render(<LobbyPage projection={lobby} loadSession={loadSession} command={command} />);
    fireEvent.click(screen.getByRole("button", { name: "Ready up" }));
    await waitFor(() => expect(command).toHaveBeenCalledWith("ready", { ready: true }));
    rerender(<LobbyPage projection={{ ...lobby, lobby: { ...lobby.lobby!, readiness: { alice: true }, canStart: true } }} loadSession={loadSession} command={command} />);
    expect(screen.getByRole("button", { name: "Not ready" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Start game" }));
    await waitFor(() => expect(command).toHaveBeenCalledWith("start", {}));
  });
  it("changes settings without replacing the roster or privacy policy", async () => {
    const command = vi.fn().mockResolvedValue(undefined);
    render(<LobbyPage projection={lobby} loadSession={loadSession} command={command} />);
    fireEvent.click(screen.getByText("Edit settings"));
    fireEvent.change(await screen.findByLabelText("Field size"), { target: { value: "7" } });
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
    await waitFor(() => expect(command).toHaveBeenCalledWith("configure", { invocation: expect.objectContaining({ players: ["alice", ""], "player-count": 2, "ring-count": 7, visibility: "open" }) }));
  });
  it("admits private outsiders only through an authenticated join and keeps start owner-only", async () => {
    const command = vi.fn().mockResolvedValue(undefined);
    render(<LobbyPage projection={{ ...lobby, viewer: { player: "bob", role: "observer", canAct: false }, lobby: { ...lobby.lobby!, owner: null, visibility: "private", member: false }, invocation: { ...lobby.invocation, players: ["Occupied", ""] } }} loadSession={loadSession} command={command} />);
    fireEvent.change(screen.getByLabelText("Lobby password"), { target: { value: "test-only-admission" } });
    fireEvent.click(screen.getByRole("button", { name: "Join seat 2" }));
    await waitFor(() => expect(command).toHaveBeenCalledWith("join", { index: 1, password: "test-only-admission" }));
    expect(screen.queryByRole("button", { name: "Start game" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Lobby password")).toHaveValue("");
  });
});
