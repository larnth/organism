import type { GameProjection } from "../api/contracts";
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

export function ObserveGamePage({
  projection,
  connectionState = "connected",
}: {
  projection: GameProjection;
  connectionState?: "connected" | "syncing" | "reconnecting";
}) {
  return (
    <main className="game-shell">
      <header className="game-header surface">
        <div>
          <div className="section-label">Live specimen</div>
          <h1>ORGANISM</h1>
        </div>
        <div className="game-header__context">
          <strong>{displayName(projection.gameId)}</strong>
          <span className={`connection connection--${connectionState}`}>
            {connectionState === "connected" ? "Current" : "Reconnecting"}
          </span>
        </div>
      </header>
      <section className="game-stage">
        <GameBoard projection={projection} />
        <div className="game-sidebar">
          <GameStatus projection={projection} />
          <PlayerRail projection={projection} />
          <section className="observer-note surface">
            <div className="section-label">Observer view</div>
            <p>The shared board is live. Player controls remain on the active player’s view.</p>
          </section>
        </div>
      </section>
    </main>
  );
}
