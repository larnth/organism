# Modern Client Baseline

Verified on 2026-09-10 from upstream `master` at `060e61d3fa40785938cc77a60089cb618c963f74`.

## Local toolchain

- OpenJDK 21.0.12.1
- Leiningen 2.12.1-SNAPSHOT
- Node.js 22.23.0
- npm 10.9.8
- MongoDB 8.0.30 in the `organism-dev-mongo` Docker container

## Clojure tests

Run MongoDB locally, then execute:

    lein with-profile +project/test test

Result after adding the read projection and server-owned legal-action descriptors:

    Ran 98 tests containing 658 assertions.
    0 failures, 0 errors.

The explicit profile is required because this repository does not add the test resource path for plain `lein test`. The formerly empty test configuration also had to be made non-empty because cprop rejects an empty configuration.

## Production ClojureScript build

Install the locked npm dependencies and build every existing client:

    npm ci
    npx shadow-cljs release organism journey oroboros eridu future

All five release builds completed successfully:

- `organism`: 215 files, 73 compiled
- `journey`: 194 files, 58 compiled
- `oroboros`: 91 files, 37 compiled
- `eridu`: 191 files, 55 compiled
- `future`: 192 files, 56 compiled

The build reports existing dependency and compiler warnings, including Reagent's deprecated `render`, thi.ng symbol redefinitions, and two Journey inference warnings. npm reports eight dependency advisories: six low, one moderate, and one high. These are baseline findings, not changes made by the modernization.

## Local application smoke test

With MongoDB and the Clojure server running on port 11551:

- `GET /organism` returned HTTP 200.
- `GET /api/v1/organism/games/definitely-missing` returned HTTP 404 with:

      {"error":"game-not-found","gameId":"definitely-missing"}

## Scope protected by this baseline

The existing rules engine, routes, persistence, bots, templates, and legacy clients remain operational. Modern-client changes must continue to pass this suite and the production ClojureScript builds until legacy cutover.
