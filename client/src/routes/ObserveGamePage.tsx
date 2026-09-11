import type { GameProjection, LegalAction } from "../api/contracts";
import { ActionPanel } from "../components/ActionPanel";
import { GameBoard } from "../components/GameBoard";
import { GameStatus } from "../components/GameStatus";
import { PlayerRail } from "../components/PlayerRail";
import "../styles/game.css";

function displayName(gameId: string) {
  return gameId
    .split("-")
    .filter(Boolean)
    .map((part) => part[0]?.toUpperCase() + part.slice(1))
    .join(" ");
}

function playerName(player: string | null) {
  return player ? player[0].toUpperCase() + player.slice(1) : "Player";
}

export function ObserveGamePage({
  projection,
  connectionState = "connected",
  onAction,
  actionState = "ready",
  actionError,
}: {
  projection: GameProjection;
  connectionState?: "connected" | "syncing" | "reconnecting";
  onAction?: (action: LegalAction) => void;
  actionState?: "ready" | "submitting" | "error";
  actionError?: string;
}) {
  return (
    <main className="game-shell">
      <header className="game-header surface">
        <div>
          <div className="section-label">
            {projection.viewer.role === "player"
              ? `Player view · ${playerName(projection.viewer.player)}`
              : "Live specimen"}
          </div>
          <h1>ORGANISM</h1>
        </div>
        <div className="game-header__context">
          <strong>{displayName(projection.gameId)}</strong>
          <span className={`connection connection--${connectionState}`}>
            {connectionState === "connected" ? "Current" : "Reconnecting"}
          </span>
        </div>
      </header>
      {projection.status === "waiting" ? (
        <section className="waiting-room surface">
          <div className="section-label">Game lobby</div>
          <h2>Waiting for players</h2>
          <p>The board will appear here when every seat is filled and the game begins.</p>
        </section>
      ) : (
        <section className="game-stage">
          <GameBoard projection={projection} onAction={onAction} />
          <div className="game-sidebar">
          <GameStatus projection={projection} />
          {projection.viewer.canAct && onAction ? (
            <ActionPanel
              actions={projection.legalActions}
              onAction={onAction}
              state={actionState}
              error={actionError}
            />
          ) : null}
          <PlayerRail projection={projection} />
          {!projection.viewer.canAct || !onAction ? (
            <section className="observer-note surface">
              <div className="section-label">
                {projection.viewer.role === "observer" ? "Observer view" : "Waiting"}
              </div>
              <p>
                {projection.viewer.role === "observer"
                  ? "The shared board is live. Player controls remain on the active player’s view."
                  : "The board is live. Your choices will appear here when it is your turn."}
              </p>
            </section>
          ) : null}
          </div>
        </section>
      )}
    </main>
  );
}
