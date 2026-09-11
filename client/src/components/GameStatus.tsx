import type { GameProjection, JsonValue } from "../api/contracts";

function object(value: JsonValue | undefined): Record<string, JsonValue> {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function string(value: JsonValue | undefined): string | null {
  return typeof value === "string" ? value : null;
}

function name(value: string) {
  return value[0]?.toUpperCase() + value.slice(1);
}

export function GameStatus({ projection }: { projection: GameProjection }) {
  const game = projection.game ?? {};
  const state = object(game.state);
  const turn = object(state["player-turn"]);
  const currentPlayer = string(turn.player);
  const winner = string(state.winner);
  const round = typeof state.round === "number" ? state.round : 0;

  let message = "Waiting for players";
  if (projection.status === "completed") {
    message = winner ? `${name(winner)} completed the organism` : "Game completed";
  } else if (currentPlayer) {
    message = `${name(currentPlayer)} is choosing an action`;
  }

  return (
    <div className="game-status" aria-live="polite">
      <span>{message}</span>
      <span className="game-status__meta">
        Round {round} · Revision {projection.revision}
      </span>
    </div>
  );
}
