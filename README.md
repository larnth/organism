# organism

A server for synchronous and asynchronous play of ORGANISM, the board game.

![ORGANISM](https://github.com/prismofeverything/organism/blob/master/resources/public/img/organism-five-player.png)

An instance is currently running at https://playorganism.io/

## Prerequisites

- [Leiningen][1] 2.0+
- Node.js / npm (for shadow-cljs)
- MongoDB running locally on the default port

[1]: https://github.com/technomancy/leiningen

## Local development

Create a `dev-config.edn` in the project root (gitignored):

    {:dev true
     :port 3000
     :nrepl-port 7000}

Install JS deps once:

    npm install

Then run two processes in parallel:

1. **ClojureScript build (hot-reload):**

        npx shadow-cljs watch organism journey oroboros eridu future

2. **Clojure server:**

        lein run

The server listens on `http://localhost:3000` and starts an nREPL on `7000`. Shadow writes compiled JS into `resources/public/js/`, which the server serves directly.

## Tests

With MongoDB running locally:

    lein with-profile +project/test test

The explicit profile activates `env/test/resources/config.edn`; plain `lein test` does not add that resource path in this project.

## Modern client work

The modernization preserves the existing rules engine and replaces the player-facing client behind a server-authoritative API. See [`docs/modern-client-architecture.md`](docs/modern-client-architecture.md).

- [Verified modernization baseline](docs/modern-client/baseline.md)
- [Read-only game API](docs/modern-client/read-api.md)
- [Create and join a game](docs/create-and-join-a-game.md)

To run the modern React client locally:

    cd client
    npm install
    npm run dev

The development-only interaction fixtures use projections generated from the Clojure rules engine:

- `http://127.0.0.1:5174/modern/?fixture=player` — Move
- `http://127.0.0.1:5174/modern/?fixture=eat` — Eat
- `http://127.0.0.1:5174/modern/?fixture=grow` — Grow

Eat and Move source choices are reversible on the board. Grow payment components are selected on the board when the projected choice is unambiguous; compound payment combinations remain explicit in the action panel. Grow choices remain local until a destination is confirmed, and the change buttons allow revision. The complete action path is submitted atomically with expected-revision protection.

### Playing

The artwork-preserving **Play dock** is available at `/organism`. See the [landing guide](docs/landing-page.md) for navigation.

1. Choose **Let’s play → Create account** and create a player name and a password of at least 12 characters.
2. Choose **create a game**, set the table options, and launch its lobby. Invite players, chat, and ready up; the owner starts after every seat is occupied and ready.
3. Use the modern board to select only the legal actions highlighted by the server-owned rules engine. A choice is not committed until its complete path is confirmed.
4. Use **observe** to watch without joining a player seat. Observer views do not receive player-only legal actions.

Accounts and games persist across service restarts.

### Registration refresh

The approved **Player dock** registration form is available at `/register`, with visible password rules, live matching feedback, Show/Hide controls, and recoverable server errors that preserve the player name. See the [registration guide](docs/registration.md) for usage. The login page is unchanged.

### Learning and gameplay repairs

The original learning videos are available at `/organism/learn`. Waiting players and observers cannot enter another player's action flow, concurrent game reconnects retain both players, and bot simulations record and display their winner automatically. See the [player guide](docs/learning-and-gameplay-fixes.md).

## Production build

Use Java 21 and build both browser clients before packaging:

    npm ci
    npm ci --prefix client
    npm run build --prefix client
    npx shadow-cljs release organism journey journey-bots oroboros eridu future
    lein uberjar

The uberjar at `target/uberjar/organism.jar` includes the modern client, all existing ClojureScript release bundles, and the standalone landing and registration assets.

## License

Copyright © 2021 Ryan Spangler
