import { describe, expect, it } from "vitest";

import {
  demoSubmitAction,
  eatProjection,
  eatSubmitAction,
  growProjection,
  growSubmitAction,
  playerProjection,
} from "./fixtures";

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
    expect(coordinates(moveFromFour.nextActions ?? [], "targets")).toEqual([
      "green:4",
      "purple:5",
      "green:3",
    ]);
    expect(coordinates(moveFromFour.nextActions ?? [], "targets")).not.toContain("green:5");

    const moved = await demoSubmitAction(
      "pond-life",
      [moveFromFour.actionId, moveFromFour.nextActions![0].actionId],
      10,
    );
    expect(moved.revision).toBe(12);
  });

  it("replays the engine-generated eat sequence", async () => {
    const plan = eatProjection.legalActions.find(({ label }) => label === "Plan eat actions")!;
    const planned = await eatSubmitAction("eat-lab", plan.actionId, 0);
    const useEat = planned.legalActions.find(({ label }) => label === "Use eat")!;
    const selectingEater = await eatSubmitAction("eat-lab", useEat.actionId, 1);
    const eater = selectingEater.legalActions.find(({ source }) =>
      Array.isArray(source) && source[0] === "orange" && source[1] === 2,
    )!;
    const foodSource = eater.nextActions!.find(({ targets }) =>
      targets?.some((target) => Array.isArray(target) && target[0] === "orange" && target[1] === 3),
    )!;

    expect(selectingEater.legalActions).toHaveLength(2);
    expect(eater.nextActions).toHaveLength(3);
    const finished = await eatSubmitAction(
      "eat-lab",
      [eater.actionId, foodSource.actionId],
      2,
    );
    expect(finished.revision).toBe(3);
  });

  it("replays element, payment, and destination choices for grow", async () => {
    const plan = growProjection.legalActions.find(({ label }) => label === "Plan grow actions")!;
    const planned = await growSubmitAction("grow-lab", plan.actionId, 0);
    const useGrow = planned.legalActions.find(({ label }) => label === "Use grow")!;
    const choosingElement = await growSubmitAction("grow-lab", useGrow.actionId, 1);
    const growEat = choosingElement.legalActions.find(({ label }) => label === "Grow an eat element")!;
    const payments = growEat.nextActions!;

    expect(payments).toHaveLength(2);
    expect(payments.map(({ cost }) => cost)).toEqual([1, 1]);
    const payment = payments.find(({ options }) =>
      JSON.stringify(options).includes('"blue",0'),
    )!;
    const destination = payment.nextActions!.find(({ targets }) =>
      targets?.some((target) => Array.isArray(target) && target[0] === "blue" && target[1] === 1),
    )!;
    const finished = await growSubmitAction(
      "grow-lab",
      [growEat.actionId, payment.actionId, destination.actionId],
      2,
    );

    expect(finished.revision).toBe(3);
    expect(JSON.stringify(finished.game)).toContain('"space":["blue",1],"type":"eat"');
  });

  it("returns the board state for the exact Grow path selected", async () => {
    const plan = growProjection.legalActions.find(({ label }) => label === "Plan grow actions")!;
    const planned = await growSubmitAction("grow-lab", plan.actionId, 0);
    const useGrow = planned.legalActions.find(({ label }) => label === "Use grow")!;
    const choosingElement = await growSubmitAction("grow-lab", useGrow.actionId, 1);
    const growMove = choosingElement.legalActions.find(({ label }) => label === "Grow an move element")!;
    const payment = growMove.nextActions!.find(({ options }) =>
      JSON.stringify(options).includes('"blue",0'),
    )!;
    const destination = payment.nextActions!.find(({ targets }) =>
      targets?.some((target) => Array.isArray(target) && target[0] === "orange" && target[1] === 17),
    )!;

    const finished = await growSubmitAction(
      "grow-lab",
      [growMove.actionId, payment.actionId, destination.actionId],
      2,
    );

    expect(JSON.stringify(finished.game)).toContain('"space":["orange",17],"type":"move"');
    expect(JSON.stringify(finished.game)).not.toContain('"space":["blue",1],"type":"eat"');
  });

  it("offers only destinations adjacent to the selected Grow contributor", async () => {
    const plan = growProjection.legalActions.find(({ label }) => label === "Plan grow actions")!;
    const planned = await growSubmitAction("grow-lab", plan.actionId, 0);
    const useGrow = planned.legalActions.find(({ label }) => label === "Use grow")!;
    const choosingElement = await growSubmitAction("grow-lab", useGrow.actionId, 1);
    const growEat = choosingElement.legalActions.find(({ label }) => label === "Grow an eat element")!;
    const bottomGrower = growEat.nextActions!.find(({ options }) =>
      JSON.stringify(options).includes('"orange",1'),
    )!;

    expect(coordinates(bottomGrower.nextActions ?? [], "targets")).toEqual(["blue:1"]);
  });

  it("supports Grow paths whose only legal payment is forced by the engine", async () => {
    const plan = growProjection.legalActions.find(({ label }) => label === "Plan grow actions")!;
    const planned = await growSubmitAction("grow-lab", plan.actionId, 0);
    const useGrow = planned.legalActions.find(({ label }) => label === "Use grow")!;
    const choosingElement = await growSubmitAction("grow-lab", useGrow.actionId, 1);
    const growGrow = choosingElement.legalActions.find(({ label }) => label === "Grow an grow element")!;
    const destination = growGrow.nextActions!.find(({ targets }) =>
      targets?.some((target) => Array.isArray(target) && target[0] === "blue" && target[1] === 11),
    )!;

    const finished = await growSubmitAction(
      "grow-lab",
      [growGrow.actionId, destination.actionId],
      2,
    );

    expect(JSON.stringify(finished.game)).toContain('"space":["blue",11],"type":"grow"');
  });
});
