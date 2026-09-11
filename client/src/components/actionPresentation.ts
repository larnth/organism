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
