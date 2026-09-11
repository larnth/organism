import { describe, expect, it } from "vitest";

import { demoSubmitAction, playerProjection } from "./fixtures";

function coordinates(actions: typeof playerProjection.legalActions, field: "source" | "targets") {
  return actions.flatMap((action) => {
    const values = field === "source" ? [action.source] : action.targets ?? [];
    return values.filter(Array.isArray).map((value) => `${value[0]}:${value[1]}`);
  });
}

describe("player interaction fixture", () => {
  it("replays the engine-derived legal move sequence", async () => {
    const planned = await demoSubmitAction("pond-life", playerProjection.legalActions[0].actionId, 8);
    expect(planned.legalActions.map(({ label }) => label)).toEqual(["Use move"]);

    const selectingSource = await demoSubmitAction("pond-life", planned.legalActions[0].actionId, 9);
    expect(coordinates(selectingSource.legalActions, "source")).toEqual(["purple:3", "purple:4"]);

    const moveFromFour = selectingSource.legalActions.find(({ source }) =>
      Array.isArray(source) && source[0] === "purple" && source[1] === 4,
    )!;
    const selectingTarget = await demoSubmitAction("pond-life", moveFromFour.actionId, 10);
    expect(coordinates(selectingTarget.legalActions, "targets")).toEqual([
      "green:4",
      "purple:5",
      "green:3",
    ]);
    expect(coordinates(selectingTarget.legalActions, "targets")).not.toContain("green:5");
  });
});
