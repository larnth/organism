import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ObserveGamePage } from "./ObserveGamePage";
import { completedProjection, observerProjection } from "../test/fixtures";

describe("observer game page", () => {
  it("keeps the shared board primary and omits player controls", () => {
    render(<ObserveGamePage projection={observerProjection} />);

    expect(screen.getByRole("heading", { name: "ORGANISM" })).toBeInTheDocument();
    expect(screen.getByText("Alice is choosing an action")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Pond-life game board" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /move/i })).not.toBeInTheDocument();

    const colony = screen.getByLabelText("Player standings");
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

    expect(screen.getByText("Waiting for players")).toBeInTheDocument();
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
    const actionPanel = screen.getByLabelText("Your turn");
    const standings = screen.getByRole("complementary", { name: "Player standings" });
    expect(actionPanel.compareDocumentPosition(standings) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByText("Observer view")).not.toBeInTheDocument();
  });
});
