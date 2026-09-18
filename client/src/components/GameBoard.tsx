import { useState } from "react";

import type { GameProjection, JsonValue, LegalAction } from "../api/contracts";
import { ElementGlyph } from "./ElementGlyph";
import { FoodDots } from "./FoodDots";
import { growPaymentSource, isBoardAction } from "./actionPresentation";
import { buildBoardLayout } from "./boardLayout";
import type { Placement } from "./StartingPlacement";

type Pair = [JsonValue, JsonValue];
type BoardChoice = {
  action: LegalAction;
  path: LegalAction[];
  selectsSource: boolean;
};

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

export function GameBoard({
  projection,
  onAction,
  onSelectionChange,
  placement = [],
}: {
  projection: GameProjection;
  onAction?: (actions: LegalAction[]) => void;
  onSelectionChange?: (action: LegalAction | null) => void;
  placement?: Placement[];
}) {
  const [selectedActionId, setSelectedActionId] = useState<string | null>(null);
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
  const selectedAction = projection.legalActions.find(({ actionId }) => actionId === selectedActionId);
  const previews = new Map(placement.map(item => [`${item.coordinate[0]}:${item.coordinate[1]}`, item.type]));
  const startingSpaces = new Set(projection.legalActions.filter(action => action.kind === "introduce").flatMap(action => (action.targets ?? []).map(value => {
    const at = coordinate(value); return at ? `${at[0]}:${at[1]}` : "";
  })));
  const actionsByCoordinate = new Map<string, BoardChoice>();
  for (const action of projection.legalActions.filter((candidate) =>
    isBoardAction(candidate, projection.legalActions)
  )) {
    const values = action.kind === "grow-from"
      ? [growPaymentSource(action)]
      : [action.source, ...(action.targets ?? [])];
    for (const value of values) {
      if (value === undefined) continue;
      const at = coordinate(value);
      if (at) {
        actionsByCoordinate.set(`${at[0]}:${at[1]}`, {
          action,
          path: [action],
          selectsSource: ["eat-to", "move-from"].includes(action.kind)
            && Boolean(action.nextActions?.length),
        });
      }
    }
  }
  if (selectedAction?.nextActions) {
    for (const action of selectedAction.nextActions) {
      for (const value of [action.source, ...(action.targets ?? [])]) {
        if (value === undefined) continue;
        const at = coordinate(value);
        if (at) {
          actionsByCoordinate.set(`${at[0]}:${at[1]}`, {
            action,
            path: [selectedAction, action],
            selectsSource: false,
          });
        }
      }
    }
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
            const choice = actionsByCoordinate.get(key);
            const action = choice?.action;
            const actionLabel = action?.kind === "grow-from"
              ? `Spend ${action.cost ?? 0} food from ${ring} ${index}`
              : action?.label;
            const sourceSelected = choice?.selectsSource && action?.actionId === selectedActionId;
            const actionClass = choice?.selectsSource
              ? " board-location--source"
              : choice && choice.path.length > 1 ? " board-location--destination" : "";
            const activate = choice && onAction ? () => {
              if (choice.selectsSource) {
                const nextAction = selectedActionId === action?.actionId ? null : action ?? null;
                setSelectedActionId(nextAction?.actionId ?? null);
                onSelectionChange?.(nextAction);
              } else {
                onAction(choice.path);
              }
            } : undefined;
            return (
              <g
                key={key}
                data-coordinate={key}
                aria-label={actionLabel ?? `${ring} ${index}`}
                aria-pressed={choice?.selectsSource ? sourceSelected : undefined}
                className={action
                  ? `board-location board-location--action${actionClass}${sourceSelected ? " board-location--selected" : ""}`
                  : `board-location${startingSpaces.has(key) ? " board-location--preview" : ""}`}
                role={action ? "button" : undefined}
                tabIndex={action ? 0 : undefined}
                onClick={activate}
                onKeyDown={activate ? (event) => {
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    activate();
                  }
                } : undefined}
              >
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
                {!element && previews.has(key) ? <g className="placement-preview" transform={`translate(${x} ${y})`}><ElementGlyph type={previews.get(key)!} color="#c7f59b" food={0} radius={layout.cellRadius} /></g> : null}
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
