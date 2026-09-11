import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ElementGlyph } from "./ElementGlyph";

describe("ElementGlyph", () => {
  it.each([
    ["eat", "8.657 5.005 90 90"],
    ["grow", "96.934 4.956 90 90"],
    ["move", "48.732 88.967 90 90"],
  ])("centers the %s silhouette by its visible bounds", (type, expectedViewBox) => {
    const { container } = render(
      <svg><ElementGlyph type={type} color="#fff" food={0} radius={40} /></svg>,
    );
    const path = container.querySelector<SVGPathElement>("[data-shape]")!;

    expect(path.ownerSVGElement).toHaveAttribute("viewBox", expectedViewBox);
  });
});
