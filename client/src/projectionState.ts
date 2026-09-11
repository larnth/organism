import type { GameProjection } from "./api/contracts";

export function selectNewerProjection(
  current: GameProjection,
  candidate: GameProjection,
): GameProjection {
  return candidate.revision > current.revision ? candidate : current;
}
