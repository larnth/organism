import { useEffect, useState } from "react";

import { fetchCatchUp, fetchGame, GameApiError } from "./api/client";
import type { CatchUpResponse, GameProjection } from "./api/contracts";
import { ObserveGamePage } from "./routes/ObserveGamePage";
import "./styles/game.css";

type Loader = (gameId: string) => Promise<GameProjection>;
type UpdateLoader = (gameId: string, afterRevision: number) => Promise<CatchUpResponse>;

type LoadState =
  | { kind: "loading" }
  | { kind: "loaded"; projection: GameProjection }
  | { kind: "missing" }
  | { kind: "error"; message: string };

export function App({
  gameId,
  initialProjection,
  loadGame = fetchGame,
  loadUpdates = fetchCatchUp,
  pollInterval = 5000,
}: {
  gameId: string | null;
  initialProjection?: GameProjection;
  loadGame?: Loader;
  loadUpdates?: UpdateLoader;
  pollInterval?: number;
}) {
  const [attempt, setAttempt] = useState(0);
  const [connectionState, setConnectionState] = useState<"connected" | "reconnecting">("connected");
  const [state, setState] = useState<LoadState>(() =>
    initialProjection ? { kind: "loaded", projection: initialProjection } : { kind: "loading" },
  );

  useEffect(() => {
    if (initialProjection) {
      setState({ kind: "loaded", projection: initialProjection });
      return;
    }
    if (!gameId) {
      setState({ kind: "missing" });
      return;
    }

    let active = true;
    setState({ kind: "loading" });
    loadGame(gameId)
      .then((projection) => {
        if (active) setState({ kind: "loaded", projection });
      })
      .catch((error: unknown) => {
        if (!active) return;
        if (error instanceof GameApiError && error.status === 404) {
          setState({ kind: "missing" });
        } else {
          setState({
            kind: "error",
            message: error instanceof Error ? error.message : "The specimen could not be loaded.",
          });
        }
      });

    return () => {
      active = false;
    };
  }, [attempt, gameId, initialProjection, loadGame]);

  useEffect(() => {
    if (initialProjection || !gameId || state.kind !== "loaded") return;
    let active = true;
    let timer: number;
    const sync = async () => {
      try {
        const update = await loadUpdates(gameId, state.projection.revision);
        if (!active) return;
        const latest = [...update.events].reverse().find((event) => event.projection)?.projection;
        setConnectionState("connected");
        if (latest) {
          setState({ kind: "loaded", projection: latest });
          return;
        }
      } catch {
        if (active) setConnectionState("reconnecting");
      }
      if (active) timer = window.setTimeout(sync, pollInterval);
    };
    timer = window.setTimeout(sync, pollInterval);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [gameId, initialProjection, loadUpdates, pollInterval, state]);

  if (state.kind === "loaded") {
    return <ObserveGamePage projection={state.projection} connectionState={connectionState} />;
  }

  return (
    <main className="empty-state">
      <div className="empty-state__mark" aria-hidden="true">O</div>
      <div className="section-label">Organism field lab</div>
      {state.kind === "loading" ? <h1>Preparing the specimen…</h1> : null}
      {state.kind === "missing" ? (
        <>
          <h1>That game could not be found.</h1>
          <p>Check the game link or return to the game list.</p>
        </>
      ) : null}
      {state.kind === "error" ? (
        <>
          <h1>Connection interrupted.</h1>
          <p>{state.message}</p>
          <button type="button" onClick={() => setAttempt((value) => value + 1)}>Try again</button>
        </>
      ) : null}
    </main>
  );
}
