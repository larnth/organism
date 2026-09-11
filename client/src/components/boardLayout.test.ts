import { describe, expect, it } from "vitest";

import { buildBoardLayout } from "./boardLayout";

describe("legacy board geometry", () => {
  it("builds concentric hex-lattice rings", () => {
    const layout = buildBoardLayout(["yellow", "red", "blue", "orange"], 6);

    expect(layout.spaces).toHaveLength(37);
    expect(layout.byCoordinate.has("yellow:0")).toBe(true);
    expect([...layout.byCoordinate.keys()].filter((key) => key.startsWith("orange:"))).toHaveLength(18);
  });

  it("places adjacent cells border-to-border", () => {
    const layout = buildBoardLayout(["yellow", "red", "blue"], 6);
    const center = layout.byCoordinate.get("yellow:0")!;
    const neighbor = layout.byCoordinate.get("red:0")!;
    const distance = Math.hypot(center.x - neighbor.x, center.y - neighbor.y);

    expect(distance).toBeCloseTo(layout.cellRadius * 2.1, 4);
    expect(distance - layout.cellRadius * 2).toBeLessThanOrEqual(4);
  });

  it("reproduces the live six-ring board coordinates", () => {
    const layout = buildBoardLayout(["A", "B", "C", "D", "E", "F"], 6, 40, 2.1);

    expect(layout.size).toBe(1008);
    expect(layout.byCoordinate.get("B:0")).toMatchObject({
      x: 576.7461339178928,
      y: 546,
    });
    expect(layout.byCoordinate.get("F:29")).toBeDefined();
  });
});
