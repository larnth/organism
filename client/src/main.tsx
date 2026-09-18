import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./App";
import type { GameProjection } from "./api/contracts";

function routeGameId() {
  const query = new URLSearchParams(window.location.search).get("game");
  if (query) return query;
  const match = window.location.pathname.match(/\/modern\/games\/([^/]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

type FixtureSubmitter = (
  gameId: string,
  actionId: string | string[],
  expectedRevision: number,
) => Promise<GameProjection>;

async function fixtureConfig(name: string | null) {
  if (!import.meta.env.DEV || !name) return {};
  const fixtures = await import("./test/fixtures");
  const interactive = {
    player: { projection: fixtures.playerProjection, submitAction: fixtures.demoSubmitAction },
    eat: { projection: fixtures.eatProjection, submitAction: fixtures.eatSubmitAction },
    grow: { projection: fixtures.growProjection, submitAction: fixtures.growSubmitAction },
  } as const;
  if (name in interactive) {
    return interactive[name as keyof typeof interactive];
  }
  return {
    projection: name === "waiting" ? fixtures.waitingProjection : fixtures.observerProjection,
  };
}

async function bootstrap() {
  const fixture = new URLSearchParams(window.location.search).get("fixture");
  const config = await fixtureConfig(fixture) as {
    projection?: GameProjection;
    submitAction?: FixtureSubmitter;
  };

  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <App
        view={config.projection ? null : new URLSearchParams(window.location.search).get("view")}
        gameId={config.projection?.gameId ?? routeGameId()}
        initialProjection={config.projection}
        submitAction={config.submitAction}
      />
    </StrictMode>,
  );
}

void bootstrap();
