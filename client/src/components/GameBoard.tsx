import type { GameProjection, JsonValue } from "../api/contracts";
import { ElementGlyph } from "./ElementGlyph";
import { FoodDots } from "./FoodDots";
import { buildBoardLayout } from "./boardLayout";

type Pair = [JsonValue, JsonValue];

function pairs(value: JsonValue | undefined): Pair[] {
  if (!Array.isArray(value)) return [];
  return value.filter((entry): entry is Pair => Array.isArray(entry) && entry.length === 2);
}

function coordinate(value: JsonValue): [string, number] | null {
  if (!Array.isArray(value) || value.length !== 2) return null;
  return typeof value[0] === "string" && typeof value[1] === "number"
    ? [value[0], value[1]]
    : null;
}

function valueObject(value: JsonValue): Record<string, JsonValue> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value : null;
}

export function GameBoard({ projection }: { projection: GameProjection }) {
  const boardName = projection.gameId[0]?.toUpperCase() + projection.gameId.slice(1);
  const game = projection.game ?? {};
  const ringsValue = game.rings;
  const rings = Array.isArray(ringsValue)
    ? ringsValue.filter((ring): ring is string => typeof ring === "string")
    : [];
  const rawPlayers = projection.invocation.players;
  const playerCount = typeof projection.invocation["player-count"] === "number"
    ? projection.invocation["player-count"]
    : Array.isArray(rawPlayers) ? rawPlayers.length : 2;
  const symmetry = playerCount === 5 ? 5 : playerCount === 7 ? 7 : 6;
  const layout = buildBoardLayout(rings, symmetry);
  const [backgroundPull, backgroundTie] = symmetry === 5
    ? [1.85, 1.9]
    : symmetry === 7 ? [1.9, 1.5] : [1.7, 1.6];
  const stateValue = game.state;
  const state = stateValue && typeof stateValue === "object" && !Array.isArray(stateValue)
    ? stateValue
    : {};
  const colors = new Map(
    pairs(projection.invocation.colors)
      .filter((pair): pair is [string, string] =>
        typeof pair[0] === "string" && typeof pair[1] === "string",
      ),
  );
  const playerColors = new Map<string, string>();
  const players = projection.invocation.players;
  if (Array.isArray(players)) {
    const palette = pairs(projection.invocation.colors)
      .map((pair) => pair[1])
      .filter((color): color is string => typeof color === "string")
      .reverse()
      .slice(Math.max(0, pairs(projection.invocation.colors).length - players.length));
    players.forEach((player, index) => {
      if (typeof player === "string" && palette[index]) {
        playerColors.set(player, palette[index]);
      }
    });
  }

  const topologyCoordinates = pairs(game.adjacencies)
    .map(([rawCoordinate]) => coordinate(rawCoordinate))
    .filter((value): value is [string, number] => value !== null);
  const spaces = topologyCoordinates.length > 0
    ? topologyCoordinates
        .map(([ring, index]) => layout.byCoordinate.get(`${ring}:${index}`))
        .filter((space): space is (typeof layout.spaces)[number] => space !== undefined)
    : layout.spaces;
  const elements = new Map<string, Record<string, JsonValue>>();
  for (const [rawCoordinate, rawElement] of pairs(state.elements)) {
    const at = coordinate(rawCoordinate);
    const element = valueObject(rawElement);
    if (at && element) elements.set(`${at[0]}:${at[1]}`, element);
  }
  const food = new Map<string, number>();
  for (const [rawCoordinate, rawFood] of pairs(state.food)) {
    const at = coordinate(rawCoordinate);
    if (at && typeof rawFood === "number") food.set(`${at[0]}:${at[1]}`, rawFood);
  }

  return (
    <div className="board-frame surface">
      <svg
        className="game-board"
        viewBox={`0 0 ${layout.size} ${layout.size}`}
        role="img"
        aria-label={`${boardName} game board`}
      >
        <title>{projection.gameId} game board</title>
        <g className="board-background" aria-hidden="true">
          <circle
            className="board-background-ring board-background-ring--field"
            cx={layout.size / 2}
            cy={layout.size / 2}
            r={(layout.size / 2) * 0.93}
          />
          {rings.slice(1).reverse().map((ring, index) => {
            return (
              <circle
                className="board-background-ring"
                key={ring}
                cx={layout.size / 2}
                cy={layout.size / 2}
                r={backgroundPull
                  * (rings.length + 1 - (backgroundTie + index))
                  * (layout.cellRadius + layout.buffer)}
                fill={colors.get(ring) ?? "#263a32"}
              />
            );
          })}
        </g>
        <g className="board-spaces">
          {spaces.map(({ ring, index, x, y }) => {
            const key = `${ring}:${index}`;
            const element = elements.get(key);
            const foodCount = food.get(key) ?? 0;
            const player = typeof element?.player === "string" ? element.player : null;
            const type = typeof element?.type === "string" ? element.type : null;
            const pieceColor = player ? playerColors.get(player) ?? "#79e5b0" : null;
            return (
              <g key={key} data-coordinate={key} aria-label={`${ring} ${index}`}>
                <circle
                  className="board-space"
                  cx={x}
                  cy={y}
                  r={layout.cellRadius}
                  fill={colors.get(ring) ?? "#263a32"}
                />
                {foodCount > 0 && !element ? (
                  <g className="free-food" transform={`translate(${x} ${y})`}>
                    <FoodDots count={foodCount} radius={layout.cellRadius} />
                  </g>
                ) : null}
                {element ? (
                  <g transform={`translate(${x} ${y})`}>
                    <ElementGlyph
                      type={type ?? "eat"}
                      color={pieceColor ?? "#79e5b0"}
                      food={element.food}
                      radius={layout.cellRadius}
                    />
                  </g>
                ) : null}
              </g>
            );
          })}
        </g>
      </svg>
      <div className="board-key" aria-hidden="true">
        {(["eat", "grow", "move"] as const).map((type) => (
          <span key={type}>
            <svg className="shape" viewBox="-30 -30 60 60"><ElementGlyph type={type} color="#79e5b0" food={0} radius={24} /></svg>
            {type[0].toUpperCase() + type.slice(1)}
          </span>
        ))}
      </div>
    </div>
  );
}
