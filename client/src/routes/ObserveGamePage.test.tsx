import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ObserveGamePage } from "./ObserveGamePage";
import { completedProjection, observerProjection } from "../test/fixtures";

describe("observer game page", () => {
  it("makes the board inert while a command is unresolved", () => {
    const source = { actionId: "source", kind: "move-from", label: "Select mover", source: ["purple", 3], nextActions: [{ actionId: "target", kind: "move-to", label: "Confirm move", targets: [["green", 3]] }] };
    const onAction = vi.fn();
    const projection = { ...observerProjection, viewer: { player: "alice", role: "player" as const, canAct: true }, legalActions: [source] };
    const { rerender } = render(<ObserveGamePage projection={projection} onAction={onAction} />);
    fireEvent.click(screen.getByRole("button", { name: "Select mover" }));
    rerender(<ObserveGamePage projection={projection} onAction={onAction} actionState="submitting" />);
    expect(screen.queryByRole("button", { name: "Confirm move" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Select mover" })).not.toBeInTheDocument();
    expect(onAction).not.toHaveBeenCalled();
  });

  it("clears source selection when ownership changes at the same revision", () => {
    const source = { actionId: "source", kind: "move-from", label: "Select mover", source: ["purple", 3], nextActions: [{ actionId: "target", kind: "move-to", label: "Confirm move", targets: [["green", 3]] }] };
    const projection = { ...observerProjection, viewer: { player: "alice", role: "player" as const, canAct: true }, legalActions: [source] };
    const { rerender } = render(<ObserveGamePage projection={projection} onAction={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Select mover" }));
    rerender(<ObserveGamePage projection={{ ...projection, viewer: { ...projection.viewer, canAct: false } }} onAction={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Confirm move" })).not.toBeInTheDocument();
    rerender(<ObserveGamePage projection={projection} onAction={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Confirm move" })).not.toBeInTheDocument();
  });
  it("keeps the shared board primary and omits player controls", () => {
    render(<ObserveGamePage projection={observerProjection} />);

    expect(screen.getByRole("heading", { name: "ORGANISM" })).toBeInTheDocument();
    expect(screen.getByText("Alice is choosing an action")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Pond-life game board" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /move/i })).not.toBeInTheDocument();

    expect(screen.queryByRole("complementary", { name: "Player standings" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Scores, history, help and discussion" }));
    const colony = screen.getByRole("complementary", { name: "Player standings" });
    expect(within(colony).getByText("Alice")).toBeInTheDocument();
    expect(within(colony).getByText("Bob")).toBeInTheDocument();
    expect(within(colony).getByText("Cy")).toBeInTheDocument();
  });

  it("renders an explicit completed-game state", () => {
    render(<ObserveGamePage projection={completedProjection} />);
    expect(screen.getByText("Alice completed the organism")).toBeInTheDocument();
  });

  it("renders a waiting room before the board exists", () => {
    render(
      <ObserveGamePage
        projection={{
          ...observerProjection,
          status: "waiting",
          game: null,
          viewer: { player: "alice", role: "player", canAct: false },
        }}
      />,
    );

    expect(screen.getByRole("heading", { name: "Waiting for players" })).toBeInTheDocument();
    expect(screen.getByText("Player view · Alice")).toBeInTheDocument();
    expect(screen.queryByRole("img", { name: /game board/i })).not.toBeInTheDocument();
  });

  it("replaces observer guidance with projected player choices", () => {
    const action = {
      actionId: "plan-move",
      kind: "choose-action-type",
      label: "Plan move actions",
      actor: "alice",
      options: ["move"],
      targets: [],
      consequences: [],
    };
    const onAction = vi.fn();
    render(
      <ObserveGamePage
        projection={{
          ...observerProjection,
          viewer: { player: "alice", role: "player", canAct: true },
          legalActions: [action],
        }}
        onAction={onAction}
        actionState="ready"
      />,
    );

    expect(screen.getByLabelText("Your turn")).toBeInTheDocument();
    expect(screen.getByText("Player view · Alice")).toBeInTheDocument();
    expect(screen.queryByRole("complementary", { name: "Player standings" })).not.toBeInTheDocument();
    expect(screen.queryByText("Watching the shared board. Only the active player can act.")).not.toBeInTheDocument();
  });

  it("updates the turn guidance when a movable component is selected", () => {
    const target = {
      actionId: "target",
      kind: "move-to",
      label: "Move to green 4",
      actor: "alice",
      targets: [["green", 4]],
      options: [],
      consequences: [],
    };
    const source = {
      actionId: "source",
      kind: "move-from",
      label: "Move element at purple 4",
      actor: "alice",
      source: ["purple", 4],
      targets: [],
      options: [],
      consequences: [],
      nextActions: [target],
    };
    render(
      <ObserveGamePage
        projection={{
          ...observerProjection,
          viewer: { player: "alice", role: "player", canAct: true },
          legalActions: [source],
        }}
        onAction={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: source.label }));
    expect(screen.getByText("Choose its destination")).toBeInTheDocument();
  });

  it("keeps a grow choice and payment reversible until a destination is submitted", () => {
    const destination = {
      actionId: "destination",
      kind: "grow-to",
      label: "Grow into purple 5",
      actor: "alice",
      targets: [["purple", 5]],
      options: [],
      consequences: [],
    };
    const payment = {
      actionId: "payment",
      kind: "grow-from",
      label: "Choose food for growth",
      actor: "alice",
      targets: [],
      options: [[[["purple", 3], 1]]],
      cost: 1,
      consequences: [],
      nextActions: [destination],
    };
    const element = {
      actionId: "element",
      kind: "grow-element",
      label: "Grow an eat element",
      actor: "alice",
      targets: [],
      options: ["eat"],
      consequences: [],
      nextActions: [payment],
    };
    const onAction = vi.fn();
    render(
      <ObserveGamePage
        projection={{
          ...observerProjection,
          viewer: { player: "alice", role: "player", canAct: true },
          legalActions: [element],
        }}
        onAction={onAction}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: element.label }));
    expect(onAction).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Spend 1 food from purple 3" }));
    expect(onAction).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: destination.label })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Change payment" }));
    expect(screen.queryByRole("button", { name: destination.label })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Spend 1 food from purple 3" }));
    fireEvent.click(screen.getByRole("button", { name: destination.label }));
    expect(onAction).toHaveBeenCalledWith([element, payment, destination]);
  });
});
