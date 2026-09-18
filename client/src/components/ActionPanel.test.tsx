import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { LegalAction } from "../api/contracts";
import { ActionPanel } from "./ActionPanel";

const optionAction: LegalAction = {
  actionId: "plan-move",
  kind: "choose-action-type",
  label: "Plan move actions",
  actor: "alice",
  options: ["move"],
  targets: [],
  consequences: [],
};

describe("ActionPanel", () => {
  it("submits non-spatial choices from a plain-language action panel", () => {
    const onAction = vi.fn();
    render(<ActionPanel actions={[optionAction]} onAction={onAction} state="ready" />);

    expect(screen.getByText("Choose an action plan")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Plan move actions" }));
    expect(onAction).toHaveBeenCalledWith([optionAction]);
  });

  it("keeps spatial choices on the board", () => {
    render(
      <ActionPanel
        actions={[{ ...optionAction, kind: "move-from", source: ["purple", 2] }]}
        onAction={vi.fn()}
        state="ready"
      />,
    );

    expect(screen.getByText("Choose the element to move")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("guides destination selection after a mover is selected", () => {
    const selected: LegalAction = {
      ...optionAction,
      kind: "move-from",
      source: ["purple", 4],
      nextActions: [
        { ...optionAction, actionId: "target-1", kind: "move-to", targets: [["green", 3]] },
        { ...optionAction, actionId: "target-2", kind: "move-to", targets: [["green", 4]] },
      ],
    };
    render(
      <ActionPanel
        actions={[selected]}
        selectedAction={selected}
        onAction={vi.fn()}
        state="ready"
      />,
    );

    expect(screen.getByText("Choose its destination")).toBeInTheDocument();
    expect(screen.getByText("2 legal destinations are highlighted on the board.")).toBeInTheDocument();
  });

  it("guides food-source selection after an eater is selected", () => {
    const selected: LegalAction = {
      ...optionAction,
      kind: "eat-to",
      source: ["purple", 2],
      nextActions: [{
        ...optionAction,
        actionId: "food-source",
        kind: "eat-from",
        targets: [["green", 2]],
      }],
    };
    render(
      <ActionPanel
        actions={[selected]}
        selectedAction={selected}
        onAction={vi.fn()}
        state="ready"
      />,
    );

    expect(screen.getByText("Choose food to take")).toBeInTheDocument();
    expect(screen.getByText("1 legal food source is highlighted on the board.")).toBeInTheDocument();
  });

  it("identifies the growers and amounts in each food-payment option", () => {
    const payment: LegalAction = {
      ...optionAction,
      actionId: "payment",
      kind: "grow-from",
      label: "Choose food for growth",
      cost: 2,
      options: [[
        [["purple", 3], 1],
        [["purple", 7], 1],
      ]],
    };
    render(
      <ActionPanel
        actions={[payment]}
        onAction={vi.fn()}
        state="ready"
      />,
    );

    expect(screen.getByRole("button", {
      name: "Spend 2 food · purple 3 ×1, purple 7 ×1",
    })).toBeInTheDocument();
  });

  it("moves unambiguous single-component grow payments onto the board", () => {
    const payment: LegalAction = {
      ...optionAction,
      actionId: "payment",
      kind: "grow-from",
      label: "Choose food for growth",
      cost: 1,
      options: [[[["purple", 3], 1]]],
    };
    render(
      <ActionPanel
        actions={[payment]}
        onAction={vi.fn()}
        state="ready"
      />,
    );

    expect(screen.getByText("The available component is highlighted on the board.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Spend 1 food/ })).not.toBeInTheDocument();
  });

  it("gives repeated introduction choices distinct labels", () => {
    const introduction = {
      ...optionAction,
      kind: "introduce",
      label: "Place starting elements",
      options: [],
    };
    render(
      <ActionPanel
        actions={[
          { ...introduction, actionId: "intro-1", targets: [["purple", 2]] },
          { ...introduction, actionId: "intro-2", targets: [["purple", 12]] },
        ]}
        onAction={vi.fn()}
        state="ready"
      />,
    );

    expect(screen.getByRole("button", { name: "Starting position 1 · near purple 2" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Starting position 2 · near purple 12" })).toBeInTheDocument();
  });
});
