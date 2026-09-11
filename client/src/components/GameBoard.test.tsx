import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

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
});
