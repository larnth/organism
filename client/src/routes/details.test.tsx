import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ObserveGamePage } from "./ObserveGamePage";
import { observerProjection } from "../test/fixtures";

const player = { ...observerProjection, viewer: { player: "alice", role: "player" as const, canAct: true, canUndo: true }, legalActions: [{ actionId: "plan", kind: "choose-action-type", label: "Plan move actions" }] };
const details = () => fireEvent.click(screen.getByRole("button", { name: "Scores, history, help and discussion" }));

describe("board-first details", () => {
  it("keeps standings behind an accessible dialog with real help links and restored focus", () => {
    render(<ObserveGamePage projection={player} onAction={vi.fn()} />);
    expect(screen.queryByRole("complementary", { name: "Player standings" })).not.toBeInTheDocument();
    const trigger = screen.getByRole("button", { name: "Scores, history, help and discussion" });
    trigger.focus(); details();
    const dialog = screen.getByRole("dialog", { name: "Game details" });
    expect(screen.getByRole("complementary", { name: "Player standings" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Learn to play" })).toHaveAttribute("href", "/organism/learn");
    fireEvent(dialog, new Event("cancel", { bubbles: true, cancelable: true }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
  it("loads actual history and keeps a separate live head during replay", async () => {
    const history = { ...player, historyCursor: 0, viewer: { ...player.viewer, canAct: false, canUndo: false }, legalActions: [], game: { ...player.game, state: { round: 1, "player-turn": { player: "bob" } } } };
    const loadHistory = vi.fn().mockResolvedValue(history);
    const { rerender } = render(<ObserveGamePage projection={player} onAction={vi.fn()} loadHistory={loadHistory} />);
    details(); fireEvent.click(screen.getByRole("button", { name: "Replay from start" }));
    await screen.findByText("Replay · position 1");
    expect(loadHistory).toHaveBeenCalledWith("pond-life", 0);
    expect(screen.queryByRole("button", { name: "Plan move actions" })).not.toBeInTheDocument();
    rerender(<ObserveGamePage projection={{ ...player, revision: 12 }} onAction={vi.fn()} loadHistory={loadHistory} />);
    expect(screen.getByText("Replay · position 1")).toBeInTheDocument();
    expect(screen.getByText("Bob is choosing an action")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Back to live" }));
    expect(screen.getByText(/Revision 12/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Plan move actions" })).toBeInTheDocument();
  });
  it("surfaces history failures and honors only the authority's undo permission", async () => {
    const onUndo = vi.fn();
    const { rerender } = render(<ObserveGamePage projection={{ ...player, viewer: { ...player.viewer, canUndo: false } }} onUndo={onUndo} loadHistory={async () => { throw new Error("History unavailable"); }} />);
    details();
    expect(screen.getByRole("button", { name: "Undo last action" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Replay from start" }));
    await screen.findByText("History unavailable");
    rerender(<ObserveGamePage projection={player} onUndo={onUndo} />);
    fireEvent.click(screen.getByRole("button", { name: "Undo last action" }));
    expect(onUndo).toHaveBeenCalledTimes(1);
  });
  it("uses projected starting-space assignments instead of hundreds of ambiguous buttons", async () => {
    const intro = (id: string, first: string, second: string) => ({ actionId: id, kind: "introduce", label: "Place starting elements", targets: [["purple", 2], ["purple", 3], ["purple", 4]], options: [{ organism: 0, spaces: [[["purple", 2], first], [["purple", 3], second], [["purple", 4], "move"]] }] });
    const actions = [intro("one", "eat", "grow"), intro("two", "grow", "eat")];
    const onAction = vi.fn();
    render(<ObserveGamePage projection={{ ...player, legalActions: actions }} onAction={onAction} />);
    expect(screen.queryByRole("button", { name: /Starting position/ })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "Element at purple 2" }), { target: { value: "grow" } });
    expect(onAction).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Place starting organism" }));
    await waitFor(() => expect(onAction).toHaveBeenCalledWith([actions[1]]));
  });
});
