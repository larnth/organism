import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

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

  it("turns a legal source space into a keyboard-accessible action", () => {
    const action = {
      actionId: "move-source",
      kind: "move-from",
      label: "Move element at purple 2",
      actor: "alice",
      source: ["purple", 2],
      targets: [],
      options: [],
      consequences: [],
    };
    const onAction = vi.fn();
    render(
      <GameBoard
        projection={{ ...observerProjection, legalActions: [action] }}
        onAction={onAction}
      />,
    );

    const space = screen.getByRole("button", { name: action.label });
    fireEvent.keyDown(space, { key: "Enter" });
    expect(onAction).toHaveBeenCalledWith(action);
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
