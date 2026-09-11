import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

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
});
