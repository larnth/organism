import { useEffect, useState } from "react";

import { fetchCatchUp, fetchGame, GameApiError, submitCommand } from "./api/client";
import type { CatchUpResponse, GameProjection, LegalAction } from "./api/contracts";
import { selectNewerProjection } from "./projectionState";
import { ObserveGamePage } from "./routes/ObserveGamePage";
import "./styles/game.css";

type Loader = (gameId: string) => Promise<GameProjection>;
type UpdateLoader = (gameId: string, afterRevision: number) => Promise<CatchUpResponse>;
type ActionSubmitter = (
  gameId: string,
  actionId: string,
  expectedRevision: number,
) => Promise<GameProjection>;

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
  submitAction = submitCommand,
  pollInterval = 5000,
}: {
  gameId: string | null;
  initialProjection?: GameProjection;
  loadGame?: Loader;
  loadUpdates?: UpdateLoader;
  submitAction?: ActionSubmitter;
  pollInterval?: number;
}) {
  const [attempt, setAttempt] = useState(0);
  const [connectionState, setConnectionState] = useState<"connected" | "reconnecting">("connected");
  const [actionState, setActionState] = useState<"ready" | "submitting" | "error">("ready");
  const [actionError, setActionError] = useState<string>();
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
          setState((current) => current.kind === "loaded"
            ? { kind: "loaded", projection: selectNewerProjection(current.projection, latest) }
            : current);
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

  const handleAction = async (action: LegalAction) => {
    if (state.kind !== "loaded" || actionState === "submitting") return;
    setActionState("submitting");
    setActionError(undefined);
    try {
      const projection = await submitAction(
        state.projection.gameId,
        action.actionId,
        state.projection.revision,
      );
      setState((current) => current.kind === "loaded"
        ? { kind: "loaded", projection: selectNewerProjection(current.projection, projection) }
        : { kind: "loaded", projection });
      setActionState("ready");
    } catch (error) {
      if (error instanceof GameApiError && error.status === 409 && gameId) {
        try {
          const projection = await loadGame(gameId);
          setState({ kind: "loaded", projection });
          setActionError("The board changed, so your available choices were refreshed.");
        } catch {
          setActionError("The board changed and could not be refreshed yet.");
        }
      } else {
        setActionError(error instanceof Error ? error.message : "That action could not be applied.");
      }
      setActionState("error");
    }
  };

  if (state.kind === "loaded") {
    return (
      <ObserveGamePage
        projection={state.projection}
        connectionState={connectionState}
        onAction={state.projection.viewer.canAct ? handleAction : undefined}
        actionState={actionState}
        actionError={actionError}
      />
    );
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
