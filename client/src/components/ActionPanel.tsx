import type { LegalAction } from "../api/contracts";
import { isBoardAction } from "./actionPresentation";

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

function growthPaymentLabel(action: LegalAction, index: number) {
  const contribution = action.options?.[0];
  if (!Array.isArray(contribution)) return `Food plan ${index + 1} · ${action.cost ?? 0} food`;
  const sources = contribution.flatMap((entry) => {
    if (!Array.isArray(entry) || entry.length !== 2 || typeof entry[1] !== "number") return [];
    return [`${coordinateLabel(entry[0])} ×${entry[1]}`];
  });
  const cost = typeof action.cost === "number" ? action.cost : 0;
  return cost === 0
    ? "Grow without spending food"
    : `Spend ${cost} food${sources.length > 0 ? ` · ${sources.join(", ")}` : ""}`;
}

function buttonLabel(action: LegalAction, index: number) {
  if (action.kind === "introduce") {
    return `Starting position ${index + 1} · near ${coordinateLabel(action.targets?.[0])}`;
  }
  if (action.kind === "grow-from") {
    return growthPaymentLabel(action, index);
  }
  return action.label ?? action.kind;
}

export function ActionPanel({
  actions,
  selectedAction,
  onAction,
  onBack,
  previousChoice,
  state,
  error,
}: {
  actions: LegalAction[];
  selectedAction?: LegalAction;
  onAction: (actions: LegalAction[]) => void;
  onBack?: () => void;
  previousChoice?: LegalAction;
  state: "ready" | "submitting" | "error";
  error?: string;
}) {
  const kind = actions[0]?.kind;
  const destinationCount = selectedAction?.nextActions?.length ?? 0;
  const nextKind = selectedAction?.nextActions?.[0]?.kind;
  const choiceName = nextKind === "eat-from" ? "food source" : "destination";
  const prompt = destinationCount > 0
    ? prompts[nextKind ?? ""] ?? "Choose the next step"
    : kind ? prompts[kind] ?? "Choose an action" : "Waiting for the next choice";
  const boardActions = actions.filter((action) => isBoardAction(action, actions));
  const buttons = actions.filter((action) => !isBoardAction(action, actions));

  return (
    <section className="action-panel surface" aria-label="Your turn">
      <div className="section-label">Your turn</div>
      <h2>{prompt}</h2>
      {boardActions.length > 0 ? (
        <p className="action-panel__hint">
          {kind === "grow-from"
            ? `${boardActions.length === 1 ? "The available component is" : `${boardActions.length} available components are`} highlighted on the board.`
            : destinationCount > 0
            ? `${destinationCount} legal ${choiceName}${destinationCount === 1 ? " is" : "s are"} highlighted on the board.`
            : `${actions.length === 1 ? "The available space is" : `${actions.length} available spaces are`} highlighted on the board.`}
        </p>
      ) : null}
      {buttons.length > 0 ? (
        <div className="action-list">
          {buttons.map((action, index) => (
            <button
              type="button"
              key={action.actionId}
              disabled={state === "submitting"}
              onClick={() => onAction([action])}
            >
              {buttonLabel(action, index)}
            </button>
          ))}
        </div>
      ) : null}
      {onBack && previousChoice ? (
        <button className="action-panel__back" type="button" disabled={state === "submitting"} onClick={onBack}>
          {previousChoice.kind === "grow-from" ? "Change payment" : "Change growth choice"}
        </button>
      ) : null}
      <div className={`action-feedback action-feedback--${state}`} aria-live="polite">
        {state === "submitting" ? "Applying action…" : null}
        {state === "error" ? error ?? "That action could not be applied." : null}
      </div>
    </section>
  );
}
