import type { LegalAction } from "../api/contracts";
import { isSpatialAction } from "./actionPresentation";

const prompts: Record<string, string> = {
  introduce: "Place your starting organism",
  "choose-organism": "Choose an organism",
  "choose-action-type": "Choose an action plan",
  "choose-action": "Choose the next action",
  "eat-to": "Choose the element that will eat",
  "eat-from": "Choose food to take",
  "grow-element": "Choose what to grow",
  "grow-from": "Choose food for growth",
  "grow-to": "Choose where to grow",
  "move-from": "Choose the element to move",
  "move-to": "Choose its destination",
  "circulate-from": "Choose the food source",
  "circulate-to": "Choose the receiving element",
  pass: "Finish this organism",
};

function coordinateLabel(value: unknown) {
  return Array.isArray(value) && value.length === 2
    ? `${String(value[0])} ${String(value[1])}`
    : "the board edge";
}

function buttonLabel(action: LegalAction, index: number) {
  if (action.kind === "introduce") {
    return `Starting position ${index + 1} · near ${coordinateLabel(action.targets?.[0])}`;
  }
  if (action.kind === "grow-from") {
    return `Food plan ${index + 1} · ${action.cost ?? 0} food`;
  }
  return action.label ?? action.kind;
}

export function ActionPanel({
  actions,
  onAction,
  state,
  error,
}: {
  actions: LegalAction[];
  onAction: (action: LegalAction) => void;
  state: "ready" | "submitting" | "error";
  error?: string;
}) {
  const kind = actions[0]?.kind;
  const prompt = kind ? prompts[kind] ?? "Choose an action" : "Waiting for the next choice";
  const buttons = actions.filter((action) => !isSpatialAction(action.kind));

  return (
    <section className="action-panel surface" aria-label="Your turn">
      <div className="section-label">Your turn</div>
      <h2>{prompt}</h2>
      {isSpatialAction(kind ?? "") ? (
        <p className="action-panel__hint">
          {actions.length === 1 ? "The available space is" : `${actions.length} available spaces are`} highlighted on the board.
        </p>
      ) : null}
      {buttons.length > 0 ? (
        <div className="action-list">
          {buttons.map((action, index) => (
            <button
              type="button"
              key={action.actionId}
              disabled={state === "submitting"}
              onClick={() => onAction(action)}
            >
              {buttonLabel(action, index)}
            </button>
          ))}
        </div>
      ) : null}
      <div className={`action-feedback action-feedback--${state}`} aria-live="polite">
        {state === "submitting" ? "Applying action…" : null}
        {state === "error" ? error ?? "That action could not be applied." : null}
      </div>
    </section>
  );
}
