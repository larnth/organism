import type { GameProjection } from "../api/contracts";

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
