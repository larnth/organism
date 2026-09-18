import type { JsonValue, LegalAction } from "../api/contracts";

const spatialActionKinds = new Set([
  "eat-to",
  "eat-from",
  "grow-to",
  "move-from",
  "move-to",
  "circulate-from",
  "circulate-to",
]);

export function isSpatialAction(kind: string) {
  return spatialActionKinds.has(kind);
}

export function growPaymentSource(action: LegalAction): JsonValue | undefined {
  if (action.kind !== "grow-from") return undefined;
  const contribution = action.options?.[0];
  if (!Array.isArray(contribution) || contribution.length !== 1) return undefined;
  const entry = contribution[0];
  return Array.isArray(entry) && entry.length === 2 ? entry[0] : undefined;
}

export function isBoardAction(action: LegalAction, siblings: LegalAction[]) {
  if (isSpatialAction(action.kind)) return true;
  const source = growPaymentSource(action);
  if (source === undefined) return false;
  const key = JSON.stringify(source);
  return siblings.filter((candidate) =>
    JSON.stringify(growPaymentSource(candidate)) === key
  ).length === 1;
}
