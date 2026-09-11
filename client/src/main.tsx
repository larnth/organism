import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./App";
import { observerProjection } from "./test/fixtures";

function routeGameId() {
  const query = new URLSearchParams(window.location.search).get("game");
  if (query) return query;
  const match = window.location.pathname.match(/\/modern\/games\/([^/]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

const fixture = import.meta.env.DEV && new URLSearchParams(window.location.search).has("fixture");

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App
      gameId={fixture ? observerProjection.gameId : routeGameId()}
      initialProjection={fixture ? observerProjection : undefined}
    />
  </StrictMode>,
);
