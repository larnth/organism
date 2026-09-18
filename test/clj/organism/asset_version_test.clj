(ns organism.asset-version-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def release-version "20260916-game-loader")
(def game-discovery-version "20260917-game-discovery")

(deftest organism-pages-version-the-redesigned-static-assets
  (doseq [resource ["html/organism/create.html" "html/organism/play.html"]
          :let [template (slurp (io/resource resource))]]
    (is (str/includes? template (str "/css/screen.css?v=" release-version)) resource)
    (is (str/includes? template (str "/js/organism.js?v=" release-version)) resource)))

(deftest create-page-loads-the-lobby-deck-styles
  (let [template (slurp (io/resource "html/organism/create.html"))]
    (is (str/includes? template (str "/css/organism-lobby.css?v=" release-version)))))

(deftest game-pages-hold-a-loading-surface-until-the-websocket-identifies-the-table
  (let [template (slurp (io/resource "html/organism/play.html"))
        source (slurp "src/cljs/organism/play.cljs")]
    (is (str/includes? template "organism-game-loader")
        "the server-rendered shell should not be blank before ClojureScript mounts")
    (is (str/includes? template "Opening table")
        "the initial shell should explain the wait")
    (is (str/includes? source "game-loading?")
        "the mounted client should retain the loading surface")
    (is (str/includes? source "[game-loading-page]")
        "play routes should render the loader before authoritative state arrives")
    (is (<= 2 (count (re-seq #"reset! game-loading\? false" source)))
        "both active-game and open-lobby initialization should resolve loading")))

(deftest websocket-state-updates-flush-the-mounted-page
  (let [source (slurp "src/cljs/organism/play.cljs")]
    (is (str/includes? source "(r/flush)")
        "WebSocket state updates must be visible immediately, including the lobby-to-game transition")))

(deftest mobile-eat-selection-keeps-the-source-selected-until-food-is-chosen
  (let [source (slurp "src/cljs/organism/play.cljs")]
    (is (str/includes? source "(defn- compute-eat-options")
        "eat choices should be resolved through the complete eater-to-food path")
    (is (str/includes? source "selected-action-source")
        "a tap must persist its source selection on touch devices")
    (is (str/includes? source "(= action-type :eat)")
        "eat destinations need a dedicated tap-to-commit path")))

(deftest game-websockets-reconnect-and-resynchronize-after-an-interruption
  (let [source (slurp "src/cljs/organism/websockets.cljs")]
    (is (str/includes? source "schedule-websocket-reconnect!")
        "an already-open game must recover after a server restart or network interruption")
    (is (str/includes? source "(.-onclose channel)")
        "closed sockets must schedule reconnection")
    (is (str/includes? source "connection-generation")
        "stale timers and socket callbacks must not replace a newer connection")
    (is (str/includes? source "(.-onmessage channel) nil")
        "retired sockets must not deliver queued authoritative snapshots")
    (is (str/includes? source "(set! (.-onclose channel) nil)")
        "starting a replacement connection must retire the old socket")
    (is (str/includes? source "js/WebSocket.OPEN")
        "outbound messages must not be attempted through a stale socket")))

(deftest websocket-errors-remain-visible-outside-the-lobby
  (let [source (slurp "src/cljs/organism/play.cljs")]
    (is (str/includes? source
                       "(reset! lobby-feedback {:kind :error :message (:message received)})")
        "create/lobby pages should use inline feedback")
    (is (str/includes? source
                       "(js/alert (:message received))")
        "active-game errors need a visible fallback")))

(deftest websocket-send-failures-never-log-outbound-payloads
  (let [source (slurp "src/cljs/organism/websockets.cljs")]
    (is (not (re-find #"could not write:[^\n]*message" source)))
    (is (str/includes? source "websocket send failed"))))

(deftest private-invocations-are-never-printed
  (let [source (slurp "src/cljs/organism/play.cljs")]
    (is (not (str/includes? source "(println \"INVOCATION\" invocation)")))))

(deftest list-joins-enter-the-lobby-instead-of-reloading-the-list
  (let [source (slurp "src/cljs/organism/components.cljs")]
    (is (re-find #"\[play-prefix create-prefix game-key index\]" source))
    (is (re-find #"\(game-url create-prefix game-key \"\"\)" source))))

(deftest active-game-cards-name-the-current-turn-owner
  (let [source (slurp "src/cljs/organism/components.cljs")]
    (is (str/includes? source "YOUR TURN")
        "the viewer's turn must be stated in text")
    (is (str/includes? source "’S TURN")
        "another player's turn must be stated in text")
    (is (str/includes? source "organism-game-turn")
        "turn ownership needs a stable styled status hook")))

(deftest player-game-list-versions-its-turn-status-assets
  (let [template (slurp (io/resource "html/organism/player.html"))]
    (is (str/includes? template
                       (str "/css/organism-community.css?v=" game-discovery-version)))
    (is (str/includes? template
                       (str "/js/organism.js?v=" game-discovery-version)))))
