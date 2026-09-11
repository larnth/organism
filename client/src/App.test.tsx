import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { App } from "./App";
import { GameApiError } from "./api/client";
import { observerProjection } from "./test/fixtures";

describe("App", () => {
  it("shows a loading state before the projection arrives", () => {
    const loadGame = vi.fn(() => new Promise<typeof observerProjection>(() => undefined));
    render(<App gameId="pond-life" loadGame={loadGame} />);
    expect(screen.getByText("Preparing the specimen…")).toBeInTheDocument();
  });

  it("renders a fetched observer projection", async () => {
    const loadGame = vi.fn().mockResolvedValue(observerProjection);
    render(<App gameId="pond-life" loadGame={loadGame} />);
    expect(await screen.findByText("Alice is choosing an action")).toBeInTheDocument();
  });

  it("renders a missing-game state", async () => {
    const loadGame = vi.fn().mockRejectedValue(new GameApiError("not-found", 404));
    render(<App gameId="lost-pond" loadGame={loadGame} />);
    expect(await screen.findByText("That game could not be found.")).toBeInTheDocument();
  });

  it("keeps the board visible while reconnecting", async () => {
    const loadGame = vi.fn().mockResolvedValue(observerProjection);
    const loadUpdates = vi.fn().mockRejectedValue(new Error("offline"));
    render(
      <App
        gameId="pond-life"
        loadGame={loadGame}
        loadUpdates={loadUpdates}
        pollInterval={1}
      />,
    );

    expect(await screen.findByText("Reconnecting")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Pond-life game board" })).toBeInTheDocument();
  });

  it("applies a projected action and replaces the projection", async () => {
    const action = {
      actionId: "plan-move",
      kind: "choose-action-type",
      label: "Plan move actions",
      actor: "alice",
      options: ["move"],
      targets: [],
      consequences: [],
    };
    const playerProjection = {
      ...observerProjection,
      viewer: { player: "alice", role: "player" as const, canAct: true },
      legalActions: [action],
    };
    const submitAction = vi.fn().mockResolvedValue({
      ...playerProjection,
      revision: 9,
      legalActions: [],
    });
    render(
      <App
        gameId="pond-life"
        initialProjection={playerProjection}
        submitAction={submitAction}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: action.label }));
    expect(await screen.findByText(/Revision 9/)).toBeInTheDocument();
    expect(submitAction).toHaveBeenCalledWith("pond-life", action.actionId, 8);
  });
});
