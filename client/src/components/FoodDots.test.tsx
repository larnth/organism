import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { FoodDots } from "./FoodDots";

describe("FoodDots", () => {
  it("renders small individual food markers like the legacy pieces", () => {
    const { container } = render(
      <svg><FoodDots count={3} radius={36} /></svg>,
    );

    expect(container.querySelectorAll("[data-food-dot]")).toHaveLength(3);
    expect(container.querySelectorAll("path.food-seed")).toHaveLength(3);
    expect(container.querySelectorAll("circle[data-food-dot]")).toHaveLength(0);
    expect(container.querySelector("text")).toBeNull();
  });

  it("collapses unusually large stores to a readable count", () => {
    const { container } = render(
      <svg><FoodDots count={9} radius={36} /></svg>,
    );

    expect(container.querySelectorAll("[data-food-dot]")).toHaveLength(1);
    expect(container.querySelector("text")).toHaveTextContent("9");
  });
});
