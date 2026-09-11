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
    expect(onAction).toHaveBeenCalledWith(optionAction);
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
