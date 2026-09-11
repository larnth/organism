import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./App";
import {
  demoSubmitAction,
  observerProjection,
  playerProjection,
  waitingProjection,
} from "./test/fixtures";

function routeGameId() {
  const query = new URLSearchParams(window.location.search).get("game");
  if (query) return query;
  const match = window.location.pathname.match(/\/modern\/games\/([^/]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

const fixture = import.meta.env.DEV
  ? new URLSearchParams(window.location.search).get("fixture")
  : null;
const initialProjection = fixture === "player"
  ? playerProjection
  : fixture === "waiting"
    ? waitingProjection
    : fixture
      ? observerProjection
      : undefined;

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App
      gameId={initialProjection?.gameId ?? routeGameId()}
      initialProjection={initialProjection}
      submitAction={fixture === "player" ? demoSubmitAction : undefined}
    />
  </StrictMode>,
);
