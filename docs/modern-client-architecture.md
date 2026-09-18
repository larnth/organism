# Modern Client Architecture

## Decision

ORGANISM's existing Clojure/ClojureScript rules engine remains the canonical implementation of the game. The modernization will replace the player-facing client with a React, TypeScript, and Vite application while keeping game transitions in Clojure on the server.

This is a boundary change, not a rules rewrite.

## Why this shape

- The rules engine is established and covered by a substantial Clojure test suite.
- The product problem is approachability, interaction design, and presentation—not game correctness.
- A framework-neutral JSON contract lets the frontend evolve without duplicating game rules.
- Vite is a smaller fit than a server-rendered JavaScript framework because the existing Clojure application already owns sessions, persistence, routing, and realtime play.

## Current boundary

The existing browser client receives complete game state over a WebSocket, computes legal choices with shared CLJC code, applies transitions locally, and submits a replacement state through `update-game-state`. The server verifies whose turn it is, but it does not independently derive the submitted transition.

Relevant code:

- `src/cljc/organism/` — canonical game and choice logic
- `src/cljs/organism/play.cljs` — current UI and client-side transition orchestration
- `src/cljs/organism/websockets.cljs` — current browser transport
- `src/clj/organism/routes/websockets.clj` — current server transport and persistence
- `src/clj/organism/routes/organism.clj` — lobby and game routes

## Target boundary

The Clojure server is authoritative:

1. Load the current game from MongoDB.
2. Identify the authenticated player from the existing session.
3. Derive the currently legal choices with the existing rules engine.
4. Accept a small versioned command that identifies one legal choice.
5. Apply the existing transition function on the server.
6. Persist one canonical state and broadcast a fresh projection.

The React client:

- renders the server projection;
- presents available choices as helpful, contextual interactions;
- sends commands rather than replacement game states;
- reconnects and refreshes from canonical server state;
- never reimplements rules or decides whether a move is legal.

### Recipient-scoped interaction model

The board is shared state; the controls are not. The modern client must never render another player's action interface as a disabled or partially interactive copy.

- The active player receives a compact contextual action tray containing only the choices legal for that player in the current phase.
- A waiting player sees the shared board, whose turn it is, and a plain waiting message. Their action tray is absent—not disabled, dimmed, or populated with the active player's choices.
- An observer receives the same readable shared board and turn context without player controls.
- Player summaries remain informational and visually distinct from the active player's controls.
- When authority changes, the old action tray leaves and the new recipient's tray appears. Pending, success, conflict, and reconnect feedback stay in that recipient's interaction area.

This boundary is enforced by the API as well as the renderer: `legalActions` is populated only in the current player's projection. The browser must render controls exclusively from that recipient-scoped collection and must not reconstruct or preview another player's legal choices from board state.

## UI/UX direction for the frontend phase

The dated appearance comes from the current imperative painter and surrounding interaction design, not from SVG itself. The default frontend direction is React/TypeScript/Vite with accessible headless menu/dialog primitives, a tokenized responsive design system, and purposeful motion for state changes. The board should begin as declarative React SVG so it remains crisp, responsive, inspectable, and accessible; canvas/WebGL should be introduced only if measured rendering needs justify the accessibility and complexity cost.

The primary composition is board-first rather than control-panel-first. On desktop, the current player's action tray sits beside the board; on tablet and phone it becomes a bottom sheet or dock below the board. Waiting and observing layouts reclaim that space for the board and a concise turn-status surface instead of preserving an empty or disabled control rail.

Before production frontend implementation, present the concrete component/styling/motion choices and two or three visual directions for approval. The target is a biological, luminous facelift with responsive contextual menus, clear legal-action previews, touch and keyboard support, and animation that explains transitions rather than decorating them.

## Landing-menu refresh

The landing page should feel fresh, fun, and consistent with gameplay without replacing the original cover. Preserve `resources/public/img/rulebook-cover-01.png` in full, including its printed credits. The game is by Ryan Spangler; the artwork is by Wyn Tiedmers. Keeping that original work prominent is an intentional acknowledgement of Ryan's project.

Use the currently rendered gameplay shell as the visual reference: charcoal `#0c0f12`, warm-white `#f3f0e8`, lime `#c9f27b`, rounded controls, and the existing Eat/Grow/Move silhouettes. The older green/amber observer theme alone is not the latest public gameplay reference.

The user selected **Play dock**. The two disposable directions remain available for historical comparison:

- Play dock: `http://127.0.0.1:4181/play-dock/` — a focused primary play action, then watching and learning.
- Field cards: `http://127.0.0.1:4181/field-cards/` — a more colorful two-column menu.

The disposable preview files and short usage guides live in `/private/tmp/organism-landing-review-20260914/`. If the server is stopped, restart it with:

    python3 -m http.server 4181 --bind 127.0.0.1 --directory /private/tmp/organism-landing-review-20260914

The toolbar switches between visitor and fictitious signed-in examples; it is not authentication. All navigation is intercepted and no credentials are collected or games created. The original cover is copied byte-for-byte, not regenerated. The preview toolbar and route-explanation dialogs are review aids, not production UI.

For implementation, retain the server-rendered session boundary and existing destinations. Guest play offers login/registration; signed-in play exposes finding a game and creating a game. Watching, learning, community, bot tools, account, and logout remain reachable. Do not add a frontend framework just for this menu or change game/lobby authority. Note that the existing `/organism/generate` GET creates a persisted all-bot game: label that action explicitly and never prefetch it during visual review.

The server-rendered Play dock uses versioned standalone CSS/JavaScript, encoded account links, and no preview-only identity controls. Local browser acceptance covers visitor and signed-in sessions, desktop/phone layouts, long names, dialog focus handling, disclosure, and reduced motion. See [the landing guide](landing-page.md).

## Registration

The approved playful Player dock registration is live at `/register`, using the same palette and Eat/Grow/Move identity without changing the login design or legacy other-game forms. Server-rendered policy drives progressive client feedback: 12–200 UTF-16 password units, no invented composition rules, confirmation rechecked when either field changes, and Unicode-aware player-name validation. Server-side CSRF, hashing, atomic name claiming, and canonical sessions remain authoritative with or without JavaScript. Error responses keep escaped nonsecret context and clear both passwords.

The implementation passed backend and client tests, build checks, an independent registration review, and local signup/login/no-JavaScript acceptance. See [the registration guide](registration.md).

## Create and lobby redesign: visual direction under review

The next product slice replaces the legacy broad invocation editor with an explicit `create draft → launched lobby → active game` lifecycle. The creator chooses the supported settings, launches a lobby into seat one, and later starts the game only after every configured seat is occupied and every human member is ready. Open rooms are publicly discoverable; private rooms are omitted from discovery and require a hashed lobby password. Joining never starts play.

The complete authority, lifecycle, password, roster, readiness, kick, chat-continuity, and acceptance contract is in [Modern Lobby Product Contract](modern-lobby-product-contract.md). The approved **Lobby Deck** direction is implemented as a conventional dedicated roster/chat room. The player workflow is documented in [Create and join a game](create-and-join-a-game.md). The earlier disposable comparison remains outside the repository at `/private/tmp/organism-lobby-review-20260915/` and is not production code.

## Version 1 contract

Initial read model:

- `GET /api/v1/organism/games/:game-key`
- authenticated with the existing Ring session cookie;
- returns a JSON projection containing game identity, board state, players, turn metadata, server-derived legal-action descriptors for the viewer, and a monotonically increasing revision;
- excludes password hashes, other private account data, database identifiers, and server-only metadata.

Initial command model:

- `POST /api/v1/organism/games/:game-key/commands`
- body contains `actionId`, `expectedRevision`, and `commandId`. `actionId` is normally one opaque ID; a confirmed Move or Eat may send the projected `[sourceId, destinationId]` pair so tentative source selection remains local;
- rejects unauthenticated callers, nonparticipants, out-of-turn players, stale versions, unknown command types, and choices absent from the server-derived legal set;
- returns the updated projection after persistence.

Realtime updates:

- the existing WebSocket transport remains during migration;
- a versioned projection event is introduced before the old full-state mutation message is retired;
- HTTP snapshot refresh remains the reconnect and recovery path.

## Compatibility rules

The modernization must not change:

- game creation parameters and their meanings;
- legal move generation;
- state-transition results;
- turn advancement;
- conflict resolution;
- victory and tie behavior;
- persisted game-history semantics;
- existing games' ability to load.

## Migration sequence

1. Keep the current client operational.
2. Add characterization fixtures for representative states and legal choices.
3. Add a server-side projection function and read-only endpoint.
4. Scaffold the modern client against fixture projections.
5. Add the server-authoritative command executor.
6. Connect realtime projection updates.
7. migrate lobby, join, and create flows.
8. Run both clients against the same games during parity testing.
9. Remove the legacy `update-game-state` path only after full-turn parity is verified.

The read projection, action-descriptor adapter, and command executor are implemented. The adapter runs `choice/find-next-choices` on the server, assigns deterministic opaque IDs to immediate choices, and publishes interaction metadata without exposing resulting game states. During Move and Eat source selection, each source descriptor includes only its server-derived next choices. The browser can therefore switch or clear a tentative mover/eater without changing the game; selecting a destination or food source submits both IDs together, and the server revalidates both steps before one atomic persistence write. Grow uses the same model recursively: element type, payment, and destination remain provisional and reversible, then all three IDs are revalidated and persisted as one revision.

## Verified baseline

On 2026-09-10:

- repository: upstream `master` at `060e61d3fa40785938cc77a60089cb618c963f74`;
- Java 21 and Leiningen were installed locally;
- MongoDB 8.0 was started in the local `organism-dev-mongo` Docker container;
- focused rules tests passed;
- the complete suite passed with 81 tests and 461 assertions after repairing the test configuration path and making the test config non-empty.

Run the complete suite with:

    lein with-profile +project/test test

The test suite is unusually noisy because game code writes extensive diagnostic output; success is determined by the final test summary and process exit status.

## Delivery guardrail

Do not commit, push, publish, or deploy without explicit approval. Confirm the code's reuse and derivative-work authorization before public release; the repository currently states copyright but does not include an explicit license grant.
