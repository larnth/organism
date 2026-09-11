import { describe, expect, it } from "vitest";

import { observerProjection } from "./test/fixtures";
import { selectNewerProjection } from "./projectionState";

describe("selectNewerProjection", () => {
  it("does not let a delayed response roll the board backward", () => {
    const current = { ...observerProjection, revision: 10 };
    const delayed = { ...observerProjection, revision: 9 };

    expect(selectNewerProjection(current, delayed)).toBe(current);
  });

  it("accepts a projection with a newer revision", () => {
    const current = { ...observerProjection, revision: 9 };
    const newer = { ...observerProjection, revision: 10 };

    expect(selectNewerProjection(current, newer)).toBe(newer);
  });
});
