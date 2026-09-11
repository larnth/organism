import type { GameProjection, JsonValue } from "../api/contracts";

interface ElementValue {
  player: string;
  organism: string;
}

function displayName(value: string) {
  return value[0]?.toUpperCase() + value.slice(1);
}

function entries(value: JsonValue | undefined): Array<[JsonValue, JsonValue]> {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (entry): entry is [JsonValue, JsonValue] => Array.isArray(entry) && entry.length === 2,
  );
}

function elementValue(value: JsonValue): ElementValue | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  if (typeof value.player !== "string") return null;
  const organism = value.organism;
  if (typeof organism !== "string" && typeof organism !== "number") return null;
  return { player: value.player, organism: String(organism) };
}

export function PlayerRail({ projection }: { projection: GameProjection }) {
  const rawPlayers = projection.invocation.players;
  const players = Array.isArray(rawPlayers)
    ? rawPlayers.filter((value): value is string => typeof value === "string")
    : [];
  const state = projection.game?.state;
  const stateObject = state && typeof state === "object" && !Array.isArray(state) ? state : {};
  const elements = entries(stateObject.elements).map(([, value]) => elementValue(value));
  const captures = stateObject.captures;
  const captureMap = captures && typeof captures === "object" && !Array.isArray(captures) ? captures : {};
  const colors = Array.isArray(projection.invocation.colors)
    ? projection.invocation.colors
    : [];
  const palette = colors
    .map((entry) => Array.isArray(entry) ? entry[1] : null)
    .filter((color): color is string => typeof color === "string")
    .reverse()
    .slice(Math.max(0, colors.length - players.length));

  return (
    <aside className="player-rail surface" aria-label="Player standings">
      <div className="section-label">Colony</div>
      <ol className="player-list">
        {players.map((player, index) => {
          const organisms = new Set(
            elements
              .filter((element) => element?.player === player)
              .map((element) => element?.organism),
          ).size;
          const playerCaptures = captureMap[player];
          const captureCount = Array.isArray(playerCaptures) ? playerCaptures.length : 0;
          const color = palette[index] ?? "#79e5b0";

          return (
            <li className="player-row" key={player}>
              <span className="player-row__identity">
                <span className="player-swatch" style={{ backgroundColor: color }} />
                <strong>{displayName(player)}</strong>
              </span>
              <span className="player-row__stats">
                {organisms} {organisms === 1 ? "organism" : "organisms"} · {captureCount} captured
              </span>
            </li>
          );
        })}
      </ol>
      <p className="victory-note">
        First to {String(projection.invocation["organism-victory"] ?? 3)} living organisms wins.
      </p>
    </aside>
  );
}
