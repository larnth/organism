import { useEffect, useRef, useState } from "react";

import type { GameProjection, LegalAction } from "../api/contracts";
import { ActionPanel } from "../components/ActionPanel";
import { GameBoard } from "../components/GameBoard";
import { GameStatus } from "../components/GameStatus";
import { PlayerRail } from "../components/PlayerRail";
import { DetailsDialog } from "../components/DetailsDialog";
import { Discussion } from "../components/Discussion";
import { StartingPlacement } from "../components/StartingPlacement";
import type { Placement } from "../components/StartingPlacement";
import { fetchHistory } from "../api/client";
import "../styles/canvas.css";

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
  loadHistory = fetchHistory,
  onUndo,
  feedback,
}: {
  projection: GameProjection;
  connectionState?: "connected" | "syncing" | "reconnecting";
  onAction?: (actions: LegalAction[]) => void;
  actionState?: "ready" | "submitting" | "error";
  actionError?: string;
  loadHistory?: (gameId: string, cursor: number) => Promise<GameProjection>;
  onUndo?: () => void;
  feedback?: React.ReactNode;
}) {
  const [selectedAction, setSelectedAction] = useState<LegalAction | undefined>();
  const [provisionalPath, setProvisionalPath] = useState<LegalAction[]>([]);
  const [details, setDetails] = useState(false);
  const [replay, setReplay] = useState<GameProjection>();
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string>();
  const [placement, setPlacement] = useState<Placement[]>([]);
  const historyGeneration = useRef(0);
  const shown = replay ?? projection;
  const replaying = replay !== undefined;
  useEffect(() => () => { historyGeneration.current++; }, []);
  async function viewHistory(cursor: number) {
    if (cursor < 0 || cursor >= projection.historySummary.entries) return;
    const generation = ++historyGeneration.current;
    setHistoryLoading(true); setHistoryError(undefined);
    try {
      const history = await loadHistory(projection.gameId, cursor);
      if (generation !== historyGeneration.current) return;
      setReplay(history); setDetails(false);
    } catch (error) {
      if (generation === historyGeneration.current) setHistoryError(error instanceof Error ? error.message : "History could not be loaded.");
    } finally { if (generation === historyGeneration.current) setHistoryLoading(false); }
  }
  function backToLive() {
    historyGeneration.current++;
    setReplay(undefined); setDetails(false); setHistoryLoading(false); setHistoryError(undefined);
  }

  useEffect(() => {
    setSelectedAction(undefined);
    setProvisionalPath([]);
    setPlacement([]);
  }, [projection.revision, projection.viewer.player, projection.viewer.canAct, projection.status, replaying]);

  const canAct = !replaying && projection.status === "active" && projection.viewer.canAct && Boolean(onAction) && actionState !== "submitting";
  const activeActions = provisionalPath.at(-1)?.nextActions ?? projection.legalActions;
  const chooseActions = (actions: LegalAction[]) => {
    if (!canAct) return;
    const fullPath = [...provisionalPath, ...actions];
    const lastAction = actions.at(-1);
    if (lastAction?.nextActions?.length) {
      setProvisionalPath(fullPath);
      setSelectedAction(undefined);
    } else {
      onAction?.(fullPath);
    }
  };

  return (
    <main className="game-shell">
      <header className="game-header">
        <a className="wordmark" href="/organism"><span aria-hidden="true">O</span><h1>ORGANISM</h1></a>
        <details className="table-title"><summary>{displayName(projection.gameId)}</summary><div className="title-card">
          <p>{projection.viewer.role === "player" ? `Player view · ${playerName(projection.viewer.player)}` : "Observer view"}</p>
          <p>{String(projection.invocation["ring-count"] ?? "")} rings · {String(projection.invocation["player-count"] ?? "")} seats</p>
          <span className={`connection connection--${connectionState}`}>{connectionState === "connected" ? "Current" : "Reconnecting"}</span>
        </div></details>
        <div className="topbar-actions"><div className="avatar-stack" aria-label="Players">{Array.isArray(projection.invocation.players) && projection.invocation.players.filter((p): p is string => typeof p === "string" && !!p).map(player => <span key={player} className="avatar" title={player}>{player[0].toUpperCase()}</span>)}</div>
          <button className="icon-button" aria-label="Scores, history, help and discussion" onClick={() => setDetails(true)}>•••</button></div>
      </header>
      {projection.status === "waiting" ? (
        <section className="waiting-room surface">
          <div className="section-label">Game lobby</div>
          <h2>Waiting for players</h2>
          <p>The board will appear here when every seat is filled and the game begins.</p>
        </section>
      ) : (
        <section className="game-stage" aria-label="Game board">
          <GameBoard
            key={`${shown.revision}-${projection.viewer.player}-${projection.viewer.canAct}-${projection.status}-${provisionalPath.length}-${replaying}`}
            projection={{ ...shown, legalActions: canAct ? activeActions : [] }}
            onAction={canAct ? chooseActions : undefined}
            placement={canAct ? placement : []}
            onSelectionChange={(action) => setSelectedAction(action ?? undefined)}
          />
        </section>
      )}
      <section className="turn-dock" aria-label="Turn controls">
          {replaying ? <div className="section-label">Replay · position {(replay.historyCursor ?? 0) + 1}</div> : null}
          <GameStatus projection={shown} />
          {!replaying && projection.status === "active" && projection.viewer.canAct && onAction ? (
            activeActions[0]?.kind === "introduce" ? <section className="action-panel" aria-label="Your turn"><div className="section-label">Your turn</div><h2>Place your starting organism</h2><StartingPlacement key={`${projection.revision}-${projection.viewer.player}`} actions={activeActions} disabled={!canAct} onAction={chooseActions} onPreview={setPlacement} /></section> :
            <ActionPanel
              actions={activeActions}
              selectedAction={selectedAction}
              onAction={chooseActions}
              onBack={provisionalPath.length > 0 ? () => {
                setProvisionalPath((path) => path.slice(0, -1));
                setSelectedAction(undefined);
              } : undefined}
              previousChoice={provisionalPath.at(-1)}
              state={actionState}
              error={actionError}
            />
          ) : null}
          {replaying || !projection.viewer.canAct || !onAction ? (
            <div className="observer-note">
              <div className="section-label">
                {replaying ? "Read-only history" : projection.status === "completed" ? "Game over" : projection.viewer.role === "observer" ? "Observer view" : "Waiting"}
              </div>
              <p>
                {replaying ? "The live game continues while you look back." : projection.status === "completed" ? "This game is complete. Explore the result and discussion in Details." : projection.viewer.role === "observer" ? "Watching the shared board. Only the active player can act." : "Your choices will appear when it is your turn."}
              </p>
            </div>
          ) : null}
          {replaying && <div className="replay-controls"><button disabled={historyLoading || !replay.historyCursor} onClick={() => void viewHistory((replay.historyCursor ?? 0) - 1)}>Previous position</button><button disabled={historyLoading || (replay.historyCursor ?? 0) >= projection.historySummary.currentIndex} onClick={() => void viewHistory((replay.historyCursor ?? 0) + 1)}>Next position</button><button className="primary" onClick={backToLive}>Back to live</button></div>}
          {historyLoading && <p role="status">Loading history…</p>}
          {!details && historyError && <p role="alert">{historyError}</p>}
          {feedback}
      </section>
      <DetailsDialog open={details} onClose={() => setDetails(false)}>
        <PlayerRail projection={projection} />
        <section aria-label="Game history"><div className="section-label">History</div><h3>Follow the game</h3><p>{projection.historySummary.entries} recorded positions · live revision {projection.revision}</p>
          <button disabled={!projection.historySummary.entries || historyLoading || actionState === "submitting"} onClick={() => void viewHistory(0)}>Replay from start</button>
          <button disabled={!projection.viewer.canUndo || !onUndo || replaying || actionState === "submitting"} onClick={() => { setDetails(false); onUndo?.(); }}>Undo last action</button>
          <p className="muted">Undo is available only within your current turn, when the game allows it.</p>
          {historyError && <p role="alert">{historyError}</p>}
        </section>
        <Discussion projection={projection} />
        <section><div className="section-label">How to play</div><h3>Eat. Grow. Move.</h3><p>Choose a highlighted piece, then one of its legal destinations. Tap it again to deselect, or select another piece.</p><a href="/organism/learn">Learn to play</a> · <a href="/organism/play">Games &amp; tables</a></section>
      </DetailsDialog>
    </main>
  );
}
