import type { GameProjection, LegalAction } from "../api/contracts";

const demoRings = ["yellow", "red", "blue", "orange", "green", "purple"];
const demoAdjacencies = demoRings.flatMap((ring, level) =>
  Array.from({ length: level === 0 ? 1 : level * 6 }, (_, index) => [ring, index])
    .map((coordinate) => [coordinate, []]),
);

export const observerProjection: GameProjection = {
  gameId: "pond-life",
  revision: 8,
  status: "active",
  viewer: { player: null, role: "observer", canAct: false },
  invocation: {
    "ring-count": 6,
    "player-count": 4,
    players: ["alice", "bob", "cy", "dev"],
    colors: [
      ["yellow", "#d5b64b"],
      ["red", "#854437"],
      ["blue", "#315c72"],
      ["orange", "#94642f"],
      ["green", "#55704c"],
      ["purple", "#6b3f68"],

    ],
    "organism-victory": 3,
  },
  game: {
    rings: [...demoRings],
    adjacencies: demoAdjacencies,
    "turn-order": ["alice", "bob", "cy", "dev"],
    players: {
      alice: { "capture-limit": 5 },
      bob: { "capture-limit": 5 },
      cy: { "capture-limit": 5 },
      dev: { "capture-limit": 5 },

    },
    state: {
      round: 4,
      elements: [
        [["purple", 2], { player: "alice", organism: 1, type: "eat", food: 2 }],
        [["purple", 3], { player: "alice", organism: 1, type: "grow", food: 1 }],
        [["purple", 4], { player: "alice", organism: 1, type: "move", food: 1 }],
        [["purple", 10], { player: "bob", organism: 2, type: "eat", food: 1 }],
        [["purple", 11], { player: "bob", organism: 2, type: "grow", food: 0 }],
        [["purple", 12], { player: "bob", organism: 2, type: "move", food: 2 }],
        [["purple", 17], { player: "cy", organism: 3, type: "eat", food: 1 }],
        [["purple", 18], { player: "cy", organism: 3, type: "grow", food: 0 }],
        [["purple", 19], { player: "cy", organism: 3, type: "move", food: 1 }],
        [["purple", 25], { player: "dev", organism: 4, type: "eat", food: 1 }],
        [["purple", 26], { player: "dev", organism: 4, type: "grow", food: 1 }],
        [["purple", 27], { player: "dev", organism: 4, type: "move", food: 0 }],
      ],
      food: [
        [["red", 1], 2],
        [["blue", 9], 3],
        [["purple", 14], 1],
      ],
      captures: { alice: [], bob: [], cy: [], dev: [] },
      "player-turn": { player: "alice", introduction: {}, "organism-turns": [] },
    },
  },
  legalActions: [],
  historySummary: { entries: 9, currentIndex: 8 },
  chat: [],
};

export const completedProjection: GameProjection = {
  ...observerProjection,
  status: "completed",
  game: {
    ...observerProjection.game,
    state: {
      ...(observerProjection.game?.state as Record<string, never>),
      winner: "alice",
    },
  },
};

export const waitingProjection: GameProjection = {
  ...observerProjection,
  status: "waiting",
  game: null,
  viewer: { player: "alice", role: "player", canAct: false },
};

const planMove: LegalAction = {
  actionId: "c3382d9ce9489362dea61a25cf4781cbe440de8f388b7fd21f46d995e413c771",
  kind: "choose-action-type",
  label: "Plan move actions",
  actor: "alice",
  options: ["move"],
  targets: [],
  consequences: [],
};

function playerStage(revision: number, legalActions: LegalAction[]): GameProjection {
  return {
    ...observerProjection,
    revision,
    viewer: { player: "alice", role: "player", canAct: true },
    legalActions,
  };
}

export const playerProjection = playerStage(8, [planMove]);

const useMove: LegalAction = {
  actionId: "bf29a6291d9e24785810edade8e1649b6b7a5e941afdf58784df6bf972171753",
  kind: "choose-action",
  label: "Use move",
  actor: "alice",
  options: ["move"],
  targets: [],
  consequences: [],
};

const moveSources: LegalAction[] = [
  {
    actionId: "986053204abf57761f73954c89f47883f2ec68036ec26ebdb3a3230513c43c38",
    kind: "move-from",
    label: "Move element at purple 3",
    actor: "alice",
    source: ["purple", 3],
    targets: [],
    options: [],
    consequences: [],
  },
  {
    actionId: "9f3a141a818e4c6d27c144a6a8e71c8f9bfebdbd8a23f370fa60f3ceb1e78586",
    kind: "move-from",
    label: "Move element at purple 4",
    actor: "alice",
    source: ["purple", 4],
    targets: [],
    options: [],
    consequences: [],
  },
];

function moveTarget(actionId: string, ring: string, index: number): LegalAction {
  return {
    actionId,
    kind: "move-to",
    label: `Move to ${ring} ${index}`,
    actor: "alice",
    targets: [[ring, index]],
    options: [],
    consequences: [],
  };
}

const targetsBySource: Record<number, LegalAction[]> = {
  3: [
    moveTarget("1f2cf7601318b40ee3c581da5bd6efd2d35e999ee61a0c17153f25cb7bc5880a", "green", 2),
    moveTarget("ef32c3c08d7ce374b797c69b0505f14f8cedf996452f0f353e0f4ac660796a0e", "green", 3),
  ],
  4: [
    moveTarget("4dcdd89295837669ea6da4c5894936d90c336539de2e86612d896027366ee174", "green", 4),
    moveTarget("8a8a6f95aed6bf5626596510e963c9c9519c3b24a78cdea0343f74f26662398c", "purple", 5),
    moveTarget("ef32c3c08d7ce374b797c69b0505f14f8cedf996452f0f353e0f4ac660796a0e", "green", 3),
  ],
};

let demoMoveSource = 4;

export async function demoSubmitAction(
  _gameId: string,
  actionId: string,
  _revision: number,
): Promise<GameProjection> {
  if (actionId === planMove.actionId) return playerStage(9, [useMove]);
  if (actionId === useMove.actionId) return playerStage(10, moveSources);

  const source = moveSources.find((action) => action.actionId === actionId)?.source;
  if (Array.isArray(source) && typeof source[1] === "number") {
    demoMoveSource = source[1];
    return playerStage(11, targetsBySource[demoMoveSource]);
  }

  const target = Object.values(targetsBySource)
    .flat()
    .find((action) => action.actionId === actionId)
    ?.targets?.[0];
  if (Array.isArray(target) && typeof target[0] === "string" && typeof target[1] === "number") {
    const game = observerProjection.game!;
    const state = game.state as Record<string, unknown>;
    const elements = (state.elements as Array<[unknown, unknown]>).map(([coordinate, element]) =>
      Array.isArray(coordinate) && coordinate[0] === "purple" && coordinate[1] === demoMoveSource
        ? [target, element]
        : [coordinate, element],
    );
    return {
      ...playerStage(12, [{
        actionId: "554dc82d8b614e32765b367dc8e612f10714a7bcebf3f76c7d1579d1ea62040c",
        kind: "check-integrity",
        label: "Continue",
        actor: "alice",
        options: [],
        targets: [],
        consequences: [],
      }]),
      game: { ...game, state: { ...state, elements } as never },
    };
  }
  return playerStage(13, []);
}
