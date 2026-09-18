import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { LegalAction } from "../api/contracts";
import { observerProjection } from "../test/fixtures";
import { GameBoard } from "./GameBoard";

describe("GameBoard", () => {
  it("uses the legacy circle grid and element silhouettes", () => {
    const { container } = render(<GameBoard projection={observerProjection} />);

    expect(container.querySelectorAll("circle.board-space")).toHaveLength(91);
    expect(container.querySelector("polygon.board-space")).toBeNull();
    expect(container.querySelector('[data-shape="eat-star"]')).not.toBeNull();
    expect(container.querySelector('[data-shape="grow-clover"]')).not.toBeNull();
    expect(container.querySelector('[data-shape="move-spiral"]')).not.toBeNull();
  });

  it("fills the gaps behind the circle lattice with the legacy ring field", () => {
    const { container } = render(<GameBoard projection={observerProjection} />);
    const backgrounds = [...container.querySelectorAll("circle.board-background-ring")];

    expect(backgrounds).toHaveLength(6);
    expect(backgrounds.map((circle) => Number(circle.getAttribute("r")))).toEqual([
      468.72,
      386.478,
      314.908,
      243.338,
      171.768,
      100.19800000000002,
    ]);
  });

  it("uses the server topology instead of inventing notched spaces", () => {
    const projection = {
      ...observerProjection,
      game: {
        ...observerProjection.game,
        adjacencies: [
          [["yellow", 0], [["red", 0]]],
          [["red", 0], [["yellow", 0]]],
        ],
      },
    };
    const { container } = render(<GameBoard projection={projection} />);

    expect(container.querySelectorAll("[data-coordinate]")).toHaveLength(2);
  });

  it("lets players switch movers locally before confirming a legal destination", () => {
    const targetA: LegalAction = {
      actionId: "target-a",
      kind: "move-to",
      label: "Move to green 2",
      actor: "alice",
      targets: [["green", 2]],
      options: [],
      consequences: [],
    };
    const targetB: LegalAction = {
      ...targetA,
      actionId: "target-b",
      label: "Move to green 4",
      targets: [["green", 4]],
    };
    const sourceA: LegalAction = {
      actionId: "source-a",
      kind: "move-from",
      label: "Move element at purple 3",
      actor: "alice",
      source: ["purple", 3],
      targets: [],
      options: [],
      consequences: [],
      nextActions: [targetA],
    };
    const sourceB: LegalAction = {
      ...sourceA,
      actionId: "source-b",
      label: "Move element at purple 4",
      source: ["purple", 4],
      nextActions: [targetB],
    };
    const onAction = vi.fn();
    render(
      <GameBoard
        projection={{ ...observerProjection, legalActions: [sourceA, sourceB] }}
        onAction={onAction}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: sourceA.label }));
    expect(onAction).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: sourceA.label })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: targetA.label })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: sourceB.label }));
    expect(screen.queryByRole("button", { name: targetA.label })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: sourceB.label })).toHaveAttribute("aria-pressed", "true");

    const destination = screen.getByRole("button", { name: targetB.label });
    fireEvent.keyDown(destination, { key: "Enter" });
    expect(onAction).toHaveBeenCalledWith([sourceB, targetB]);
  });

  it("lets players choose an eater before confirming its legal food source", () => {
    const foodSource: LegalAction = {
      actionId: "food-source",
      kind: "eat-from",
      label: "Take food from green 2",
      actor: "alice",
      targets: [["green", 2]],
      options: [],
      consequences: [],
    };
    const eater: LegalAction = {
      actionId: "eater",
      kind: "eat-to",
      label: "Eat with element at purple 2",
      actor: "alice",
      source: ["purple", 2],
      targets: [],
      options: [],
      consequences: [],
      nextActions: [foodSource],
    };
    const onAction = vi.fn();
    render(
      <GameBoard
        projection={{ ...observerProjection, legalActions: [eater] }}
        onAction={onAction}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: eater.label }));
    expect(onAction).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: foodSource.label }));
    expect(onAction).toHaveBeenCalledWith([eater, foodSource]);
  });

  it("submits a projected grow destination from the board", () => {
    const destination: LegalAction = {
      actionId: "grow-target",
      kind: "grow-to",
      label: "Grow into green 3",
      actor: "alice",
      targets: [["green", 3]],
      options: [],
      consequences: [],
    };
    const onAction = vi.fn();
    render(
      <GameBoard
        projection={{ ...observerProjection, legalActions: [destination] }}
        onAction={onAction}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: destination.label }));
    expect(onAction).toHaveBeenCalledWith([destination]);
  });

  it("selects an unambiguous grow payment from its component on the board", () => {
    const payment: LegalAction = {
      actionId: "grow-payment",
      kind: "grow-from",
      label: "Choose food for growth",
      actor: "alice",
      targets: [],
      options: [[[["purple", 3], 1]]],
      cost: 1,
      consequences: [],
    };
    const onAction = vi.fn();
    render(
      <GameBoard
        projection={{ ...observerProjection, legalActions: [payment] }}
        onAction={onAction}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Spend 1 food from purple 3" }));
    expect(onAction).toHaveBeenCalledWith([payment]);
  });

  it("does not turn multi-space introduction previews into ambiguous board actions", () => {
    const action = {
      actionId: "introduction-a",
      kind: "introduce",
      label: "Place starting elements",
      actor: "alice",
      targets: [["purple", 2], ["purple", 3], ["purple", 4]],
      options: [],
      consequences: [],
    };
    render(<GameBoard projection={{ ...observerProjection, legalActions: [action] }} onAction={vi.fn()} />);

    expect(screen.queryByRole("button", { name: action.label })).not.toBeInTheDocument();
  });
});
