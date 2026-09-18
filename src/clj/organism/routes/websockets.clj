(ns organism.routes.websockets
  (:require
   [buddy.hashers :as hashers]
   [clojure.pprint :refer (pprint)]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [cognitect.transit :as transit]
   [org.httpkit.server :as hk]
   [organism.api.events :as events]
   [organism.api.commands :as commands]
   [organism.bots :as bots]
   [organism.choice :as choice]
   [organism.game :as game]
   [organism.board :as board]
   [organism.leaderboard :as leaderboard]
   [organism.lobby :as lobby]
   [organism.middleware :as middleware]
   [organism.mutations :as mutations]
   [organism.persist :as persist]
   [organism.examples :as examples])
  (:import
   [java.io ByteArrayOutputStream]))

(defn- ->stream [input]
  (cond (string? input) (io/input-stream (.getBytes input))
        :default input))

(defn read-json [input]
  (with-open [ins (->stream input)]
    (-> ins
        (transit/reader :json)
        transit/read)))

(defn write-json [output]
  (let [out (ByteArrayOutputStream. 4096)
        r (transit/writer out :json)
        _ (transit/write r output)
        ret (.toString out)]
    (.reset out)
    ret))

(defn send!
  [channel message]
  (hk/send!
   channel
   (write-json message)))

(defonce games
  (atom {:games {}}))

(def ^:private admission-window-ms 60000)
(def ^:private admission-limit 8)

(defrecord GameState [key invocation game chat history channels])

(def player-cycle
  (atom (cycle board/default-player-order)))

(defn empty-game
  [game-key player channel]
  {:key game-key
   :invocation (board/empty-invocation player)
   :game nil
   :chat []
   :history []
   :channels #{channel}
   :channel-players {channel player}})

(defn append-channel!
  [game-key player channel]
  (swap!
   games
   (fn [registry]
     (-> registry
         (update-in [:games game-key :channels] conj channel)
         (assoc-in [:games game-key :channel-players channel] player)))))

(defn load-game
  [db game-key player channel]
  (if-let [game-state (mutations/recover! db game-key)]
    (assoc game-state
           :channels #{channel}
           :channel-players {channel player})
    (let [game (empty-game game-key player channel)]
      (if-let [game-state (persist/find-open-game db game-key)]
        (merge game game-state)
        game))))

(defn load-game!
  [db game-key player channel]
  (locking (mutations/game-lock game-key)
  (let [game-state (load-game db game-key player channel)]
    (get-in
     (swap! games update-in [:games game-key]
            (fn [live]
              ;; Several tabs can finish the initial DB load together.
              ;; Keep the already-live state and its sockets, not the stale
              ;; snapshot that happened to finish loading last.
              (if (seq live)
                (-> live
                    (update :channels conj channel)
                    (assoc-in [:channel-players channel] player))
                game-state)))
     [:games game-key]))))

(declare maybe-run-bot-turns! lobby-member?)

(defn- reconcile-published-game!
  "Refresh a resident lobby from durable state, or replace it with a published
   game. Preserve process-local channels and revoke channels whose durable seat
   disappeared on another process."
  [db game-key]
  (locking (mutations/game-lock game-key)
  (let [promoted (atom false)
        previous (atom nil)
        published (persist/load-game db game-key)
        durable-lobby (when-not published (persist/find-open-game db game-key))
        game-state (get-in
                    (swap! games update-in [:games game-key]
                           (fn [live]
                             (reset! previous live)
                             (if (and (seq live) (nil? (:game live)))
                               (cond
                                 published
                                 (do
                                   (reset! promoted true)
                                   (assoc published
                                          :channels (:channels live)
                                          :channel-players (:channel-players live)))

                                 durable-lobby
                                 (assoc (merge live durable-lobby)
                                        :game nil
                                        :channels (:channels live)
                                        :channel-players (:channel-players live))

                                 :else live)
                               live)))
                    [:games game-key])
        revoked (when (and durable-lobby (nil? (:game @previous)))
                  (for [[channel player] (:channel-players @previous)
                        :when (and (lobby-member? @previous player)
                                   (not (lobby-member? game-state player)))]
                    channel))]
    (doseq [channel revoked]
      (send! channel {:type "lobby-removed"
                      :reason "Your lobby membership changed."}))
    [game-state @promoted])))

(defn- announce-published-game!
  [game-state]
  (doseq [channel (:channels game-state)]
    (let [player (get-in game-state [:channel-players channel])
          viewer (when-not (= "--observer--" player) player)]
      (send! channel {:type "initialize"
                      :invocation (:invocation game-state)
                      :game (:game game-state)
                      :player player
                      :history (:history game-state)
                      :chat (:chat game-state)})
      (send! channel (events/snapshot game-state viewer)))))

(defonce ^:private publication-watchers (atom #{}))

(defn- watch-lobby-publication!
  [db game-key]
  (when (and (instance? com.mongodb.DB db)
             (not (contains? @publication-watchers game-key)))
    (swap! publication-watchers conj game-key)
    (future
      (try
        (loop []
          (let [[game-state promoted?] (reconcile-published-game! db game-key)]
            (cond
              promoted? (announce-published-game! game-state)
              (and (seq (:channels game-state)) (nil? (:game game-state)))
              (do (Thread/sleep 250) (recur)))))
        (catch Exception error
          (log/warn error "lobby publication watcher stopped" game-key))
        (finally
          (swap! publication-watchers disj game-key))))))

(defn find-game!
  [db game-key player channel]
  (locking (mutations/game-lock game-key)
  (let [existing (get-in (deref games) [:games game-key])
        [existing promoted?] (if (and (seq existing) (nil? (:game existing)))
                               (reconcile-published-game! db game-key)
                               [existing false])]
    (when promoted?
      (announce-published-game! existing))
    (if (empty? existing)
      (load-game! db game-key player channel)
      (locking (mutations/game-lock game-key)
        (when (:game existing)
          (when-let [durable (mutations/recover! db game-key)]
            (swap! games update-in [:games game-key] merge durable)))
        (get-in (append-channel! game-key player channel)
                [:games game-key]))))))

(defn connect!
  [{:keys [db game-key player]} channel]
  (let [game-state (find-game! db game-key player channel)
        viewer (when-not (= "--observer--" player) player)]
    (if (get-in game-state [:invocation :created])
      (let [player-game (persist/find-player-game db game-key player)
            witness (:witness player-game)]
        (log/info "CONNECTING" player game-key "witness" witness (get-in game-state [:game :state]))
        (send!
         channel
         {:type "initialize"
          :invocation (:invocation game-state)
          :game (:game game-state)
          :player player
          :witness witness
          :history (:history game-state)
          :chat (:chat game-state)})
        (send! channel (events/snapshot game-state viewer))
        ;; If the current turn belongs to a bot, kick off bot turns
        (maybe-run-bot-turns! db game-key))
      (do
        (let [roster (get-in game-state [:invocation :players])
              member? (lobby-member? game-state player)
              private-outsider? (and (= "private" (:visibility game-state))
                                     (not member?))
              visible-invocation (lobby/visible-invocation (:visibility game-state)
                                                            (:invocation game-state)
                                                            player
                                                            #(or (contains? (set (:bots game-state)) %)
                                                                 (bots/bot? "organism" %)))]
          (send!
           channel
           (-> game-state
               (select-keys [:key :invocation :chat :readiness :visibility :created-by])
               (assoc :invocation visible-invocation)
               (assoc :chat (if member? (:chat game-state) []))
               (assoc :readiness (if private-outsider? {} (:readiness game-state)))
               (cond-> private-outsider? (dissoc :created-by))
               (assoc :bots (if private-outsider?
                              #{}
                              (set (filter #(bots/bot? "organism" %) roster))))
               (assoc :type "create")))
          (send! channel (events/snapshot (cond-> game-state
                                            (not member?) (assoc :chat [])
                                            private-outsider? (assoc :invocation visible-invocation
                                                                     :readiness {})
                                            private-outsider? (dissoc :created-by))
                                          viewer)))
          (watch-lobby-publication! db game-key)))))

(defn disconnect-game
  [game-key channel games]
  (let [games (update-in
               games [:games game-key :channels]
               #(remove #{channel} %))
        player-path [:games game-key :channel-players]
        games (if (get-in games player-path)
                (update-in games player-path dissoc channel)
                games)]
    (if (empty? (get-in games [:games game-key :channels]))
      ;; dissoc off the :games map, not off the wrapper — dropping the key from
      ;; the top level did nothing, so every game ever opened stayed resident
      ;; and a reconnect read the stale registry copy instead of the database.
      (update games :games dissoc game-key)
      games)))

(defn disconnect!
  [{:keys [db game-key player]} channel status]
  (let [active-game? (some? (get-in @games [:games game-key :game]))]
    (log/info "channel closed" player status)
    (swap!
     games
     (partial disconnect-game game-key channel))
    ;; Open lobbies have no game history. Writing a witness for them creates a
    ;; player-game placeholder that can collide with the eventual launch.
    (when active-game?
      (persist/store-witness! db game-key player))))

(defn send-channels!
  [channels message]
  (doseq [ch channels]
    (send! ch message)))

(defn refresh-projections!
  "Refresh the live registry and send each channel its own game projection."
  [game-state]
  (let [game-key (:key game-state)
        live (get-in @games [:games game-key])]
    (when live
      (swap! games update-in [:games game-key] merge game-state)
      (doseq [channel (:channels live)]
        (let [connected-player (get-in live [:channel-players channel])
              viewer (when-not (= "--observer--" connected-player)
                       connected-player)]
          (send! channel
                 (events/game-updated game-state viewer)))))))

(defn post-commit!
  "The shared, retryable finalization hook for every ORGANISM mutation."
  [db game-state]
  (let [key (:key game-state)
        state (get-in game-state [:game :state])]
    (if (:winner state)
      (do (persist/complete-game! db key state)
          (leaderboard/rate-later! db))
      (persist/update-player-games! db key (get-in game-state [:invocation :players]) state))
    ;; Preserve sockets; even a game with no browsers needs resident bot state.
    (swap! games update-in [:games key] merge game-state)
    (send-channels! (get-in @games [:games key :channels])
                    {:type "game-state" :game state :complete true})
    (refresh-projections! game-state)
    (maybe-run-bot-turns! db key)))

(defn recover-modern-game!
  "Resume a bot after process loss without repeating a completed commit hook.
   The runner owns deduplication; snapshots must not depend on a legacy socket."
  [db game-key]
  (locking (mutations/game-lock game-key)
    (when-let [state (mutations/recover! db game-key)]
      (swap! games update-in [:games game-key] merge state)
      (maybe-run-bot-turns! db game-key)
      state)))

(defn drop-game!
  "Forget a deleted game: tell any open tabs, then take it out of the registry.

   Without this a connected client keeps a live channel on a key whose data is
   gone, and the next connect! would quietly rebuild it as an empty lobby."
  [game-key]
  (locking (mutations/game-lock game-key)
   ((requiring-resolve 'organism.routes.organism-bot/stop-game!) game-key)
  (let [channels (get-in @games [:games game-key :channels])]
    ;; Removal must survive a broken notification channel.
    (swap! games update :games dissoc game-key)
    (when (seq channels)
     (try
      (send-channels! channels {:type "deleted" :key game-key})
      (send-channels! channels {:type "game.deleted"
                                :version events/version
                                :gameId game-key})
      (catch Exception error (log/warn error "deleted game notification failed" game-key)))))))

(defn- canonical-account-name
  [db player]
  (if (str/blank? player)
    player
    (or (persist/canonical-player-name db player) player)))

(defn- same-player-identity?
  [left right]
  (= (persist/player-identity-key left)
     (persist/player-identity-key right)))

(defn- lobby-member?
  [game-state player]
  (let [bot-set (set (:bots game-state))
        players (get-in game-state [:invocation :players])
        bot? #(or (contains? bot-set %)
                  (bots/bot? "organism" %))]
    (if (:game game-state)
      (lobby/human-seat? players player bot?)
      (lobby/member? (:created-by game-state) players player bot?))))

(defn- lobby-member-channels
  "Channels whose authenticated player is still in the lobby roster. Lobby
   mutation payloads are membership-scoped; public outsiders can reconnect for
   a fresh public preview, but do not receive the private realtime stream."
  [game-state]
  (filter
   (fn [channel]
     (let [connected (get-in game-state [:channel-players channel])]
       (lobby-member? game-state connected)))
   (:channels game-state)))

(defn- canonicalize-roster
  [db invocation]
  (let [player-count (if (integer? (:player-count invocation)) (:player-count invocation) 0)
        ring-count (if (integer? (:ring-count invocation)) (:ring-count invocation) 0)
        game-type (or (:game-type invocation) "organism")]
    (-> invocation
        (update :players
                #(mapv (fn [player]
                         (if (bots/bot? game-type player)
                           player
                           (canonical-account-name db player)))
                       %))
        ;; These are standard ORGANISM rules, not lobby variants.
        (assoc :organism-victory 3
               :mutations {}
               :player-captures (vec (repeat player-count board/default-player-captures))
               :colors (board/generate-colors (take ring-count board/total-rings))))))

(defn- roster-name
  [db actor game-type player]
  (cond
    (str/blank? player) ""
    (same-player-identity? actor player) (canonical-account-name db actor)
    (bots/bot? game-type player) player
    (persist/canonical-player-name db player) (persist/canonical-player-name db player)
    :else nil))

(defn- roster-identities
  [players]
  (mapv #(when-not (str/blank? %) (persist/player-identity-key %)) players))

(defn- prepare-lobby-invocation
  "Validate broad setup updates without making them an unsafe second roster API."
  [db record actor invocation]
  (let [invocation (-> (select-keys invocation
                                    [:description :player-captures :mutations
                                     :player-count :colors :game-type :ring-count
                                     :players :organism-victory :visibility
                                     :lobby-password])
                       (assoc :game-type "organism"))
        original-players (vec (get-in record [:invocation :players]))
        requested (vec (:players invocation))
        owner-index (when record
                      (first (keep-indexed
                              #(when (same-player-identity? %2 actor) %1)
                              original-players)))
        requested (if record
                    requested
                    (let [seat-count (:player-count invocation)]
                      (if (and (integer? seat-count) (pos? seat-count))
                        (vec (cons (canonical-account-name db actor)
                                   (repeat (dec seat-count) "")))
                        requested)))
        requested (if record
                    (mapv (fn [index player]
                            (let [original (get original-players index)]
                              (if (and (same-player-identity? original player)
                                       (bots/bot? "organism" original))
                                original
                                player)))
                          (range)
                          requested)
                    requested)]
    (cond
      (and owner-index
           (not (same-player-identity? actor (get requested owner-index))))
      {:error "your seat always uses your account name"}

      :else
      (let [players (mapv (partial roster-name db actor "organism") requested)
            identities (remove nil? (roster-identities players))]
        (cond
          (some nil? players)
          {:error "choose a registered player or bot"}

          (not= (count identities) (count (set identities)))
          {:error "each player can occupy only one seat"}

          (and record
               (not= (roster-identities original-players)
                     (roster-identities players)))
          {:error "change the roster one seat at a time"}

          :else
          {:invocation (canonicalize-roster db (assoc invocation :players players))})))))

(defn- observer?
  [player]
  (or (str/blank? player) (= "--observer--" player)))

(defn- lobby-owner?
  [record player]
  (let [owner (or (:created-by record)
                  (first (get-in record [:invocation :players])))]
    (and record
         (not (observer? player))
         (same-player-identity? owner player))))

(defn- reject!
  ([channel message]
   (reject! channel message nil))
  ([channel message scope]
   (when channel
     (send! channel (cond-> {:type "error" :message message}
                      scope (assoc :scope scope))))
   {:error message}))

(defn- edit-lobby-problem
  [db player game-key]
  (let [record (persist/find-open-game db game-key)]
    (cond
      (observer? player) "sign in to edit a game"
      (and record (not (lobby-owner? record player)))
      "only the lobby creator can edit this game"
      (and (nil? record) (persist/load-game db game-key))
      "this game has already started"
      :else nil)))

(defn- lobby-settings-problem
  [invocation]
  (let [player-count (:player-count invocation)
        ring-count (:ring-count invocation)
        players (:players invocation)
        colors (:colors invocation)]
    (cond
      (not (and (integer? player-count) (<= 1 player-count 10)))
      "choose between 1 and 10 players"
      (not (and (integer? ring-count) (<= 3 ring-count 7)))
      "choose between 3 and 7 rings"
      (not (and (vector? players) (= player-count (count players))))
      "the lobby roster does not match its player count"
      (not (and (vector? colors) (<= ring-count (count colors))))
      "the field colors do not match its ring count"
      :else nil)))

(defn update-create-game
  [db player game-key channel {:keys [invocation] :as message}]
  (persist/with-open-game-mutation!
   db game-key true
   (fn []
     (locking games
   (if-let [problem (board/game-key-problem game-key)]
    (do
      (log/warn "refusing to open game" (pr-str game-key) "-" problem)
      (send! channel {:type "error"
                      :message (str (pr-str game-key) " will not work as a game name: "
                                    problem)}))
    (if-let [problem (edit-lobby-problem db player game-key)]
      (reject! channel problem)
      (let [record (persist/find-open-game db game-key)
            prepared (prepare-lobby-invocation db record player invocation)]
        (if-let [problem (or (:error prepared)
                             (lobby-settings-problem (:invocation prepared)))]
          (reject! channel problem)
          (let [invocation (:invocation prepared)
                requested-visibility (if (= "private" (:visibility invocation)) "private" "open")
                visibility (if record (or (:visibility record) "open") requested-visibility)
                submitted-password (:lobby-password invocation)
                password (when-not record submitted-password)
                privacy-problem (when (and record
                                           (or (not= requested-visibility (or (:visibility record) "open"))
                                               (not (str/blank? submitted-password))))
                                  "lobby privacy is fixed after launch")
                password-problem (when (and (= "private" visibility)
                                            (str/blank? password)
                                            (str/blank? (:password-hash record)))
                                   "private lobbies require a password")
                invocation (dissoc invocation :lobby-password)
                gameplay-settings-changed?
                (and record
                     (not= (select-keys (:invocation record)
                                        [:ring-count :player-count :organism-victory :mutations])
                           (select-keys invocation
                                        [:ring-count :player-count :organism-victory :mutations])))
                message (cond-> {:type "create"
                                 :invocation invocation
                                 :visibility visibility}
                          gameplay-settings-changed? (assoc :readiness {}))]
            (if-let [problem (or privacy-problem password-problem)]
              (reject! channel problem)
              (do
                (when gameplay-settings-changed?
                  (persist/update-open-lobby! db game-key {:readiness {}}))
                (persist/create-open-game! db game-key invocation player
                                           {:visibility visibility :password password})
                (let [saved (persist/find-open-game db game-key)
                      _ (swap! games update-in [:games game-key] merge saved)
                      live (get-in @games [:games game-key])]
                  (if-not (lobby-owner? saved player)
                    (reject! channel "that game name is already in use")
                    (do
                      (send-channels! (lobby-member-channels live) message)
                      (when channel
                        (send! channel {:type (if record "lobby-updated" "lobby-created")
                                        :key game-key
                                        :invocation (lobby/visible-invocation visibility invocation player)}))
                      {:invocation invocation}))))))))))))))

(declare set-slot! ensure-open-game!)

(defn update-player-name
  [db page-player game-key channel {:keys [index player] :as message}]
  (let [result (set-slot! db page-player game-key index player)]
    (if-let [problem (:error result)]
      (reject! channel problem)
      (do
        (log/info "player name updated" player "invocation"
                  (-> @games :games (get game-key) :invocation))
        result))))

(defn update-open-game
  [db player game-key channel {:keys [invocation] :as message}]
  (update-create-game db player game-key channel (assoc message :type "create")))

(defn complete-game-state
  [{:keys [invocation game channels history chat] :as game-state}]
  (let [{:keys
         [ring-count
          player-count
          players
          colors
          organism-victory
          player-captures
          mutations]} invocation
        symmetry (board/player-symmetry player-count)
        starting (board/starting-spaces ring-count player-count players board/total-rings mutations)
        player-info (game/initial-players starting player-captures)
        notches? (board/cut-notches? ring-count player-count mutations)
        rings (vec (take ring-count board/total-rings))
        create (game/create-game symmetry rings player-info organism-victory notches? mutations)
        created (System/currentTimeMillis)
        bot-set (->> players
                     (filter #(bots/bot? "organism" %))
                     set
                     (#(disj % (:created-by game-state))))]
    (-> game-state
        (dissoc :password-hash :readiness)
        (assoc-in [:invocation :created] created)
        (assoc :game create :bots bot-set))))

(defn lobby-creator
  "Who set this lobby up. Falls back to whoever is asking, for lobbies opened
   before the creator was recorded."
  [db game-key fallback]
  (let [record (persist/find-open-game db game-key)]
    (or (:created-by record)
        (first (get-in record [:invocation :players]))
        fallback)))

(defn begin-game!
  "Turn an open lobby into a live game: build the starting position, tell every
   watching tab to switch over, and move the record out of :open-games.

   `creator` is whoever set the lobby up, not whoever filled the last seat — a
   game that starts itself on someone else's join still belongs to its author."
  ([db game-key creator]
   (begin-game! db game-key creator (str (java.util.UUID/randomUUID))))
  ([db game-key creator transition-id]
   (let [game-state (get-in @games [:games game-key])
        {:keys [invocation game channels history chat] :as game-state}
        (complete-game-state game-state)]
    (try
      (persist/stage-game-transition!
       db
       (assoc (dissoc game-state :channels :channel-players)
              :created-by creator
              :game-type "organism")
       transition-id)
      (when-not (persist/mark-game-transition-committing! db game-key transition-id)
        (throw (ex-info "lobby transition could not enter committing state" {:key game-key})))
      (when-not (persist/commit-game-transition! db game-key transition-id)
        (throw (ex-info "lobby start claim was lost before commit" {:key game-key})))
      (catch Exception error
        ;; Before lobby retirement, failure leaves the lobby recoverable. After
        ;; retirement the durable transition is recovery state and must survive.
        (when (persist/find-open-game db game-key)
          (persist/discard-game-transition! db game-key transition-id)
          (persist/release-open-game-start! db game-key transition-id))
        (throw error)))
    ;; The persistence transition has committed. Client and bot failures after
    ;; this point must not reconstruct or roll back the old lobby.
    (swap! games assoc-in [:games game-key] game-state)
    (send-channels! channels
                    {:type "initialize"
                     :invocation invocation
                     :game game
                     :history history
                     :chat chat})
     (maybe-run-bot-turns! db game-key)
     game-state)))

(defn trigger-creation
  [db player game-key channel message]
  (persist/with-open-game-mutation!
   db game-key
   (fn []
     (locking games
   (let [record (persist/find-open-game db game-key)
        players (get-in record [:invocation :players])
        occupied-identities (->> players
                                 (remove str/blank?)
                                 (map persist/player-identity-key))
        malformed-roster? (or (not (vector? players))
                              (not= (get-in record [:invocation :player-count])
                                    (count players))
                              (not= (count occupied-identities)
                                    (count (set occupied-identities))))
        bot-players (set (filter #(bots/bot? "organism" %) players))
        blocker (when record
                  (lobby/launch-blocker players (:readiness record) bot-players))]
    (cond
      (nil? record) (reject! channel "this lobby is no longer open")
      (not (lobby-owner? record player))
      (reject! channel "only the lobby creator can start this game")
      (lobby-settings-problem (:invocation record))
      (reject! channel (lobby-settings-problem (:invocation record)))
      malformed-roster?
      (reject! channel "the lobby settings or roster are incomplete")
      blocker (reject! channel blocker)
      (not (board/full-invocation? (:invocation record)))
      (reject! channel "the lobby settings or roster are incomplete")
      (persist/load-game db game-key) (reject! channel "this game has already started")
      :else
      (do
        (ensure-open-game! db game-key)
        (if-let [{:keys [token]} (persist/claim-open-game-start! db game-key)]
          (try
            (begin-game! db game-key (or (:created-by record) (first players)) token)
            {:started? true}
            (catch Exception _
              (if (persist/find-open-game db game-key)
                (reject! channel "could not start the game; the lobby is still open")
                (reject! channel "the game start committed; reconnect to continue"))))
          (reject! channel "this game is already starting")))))))))

(defn ensure-open-game!
  "The registry entry for an open lobby, read out of the database if no tab has
   one open. Callers with no websocket of their own — the HTTP join — need this
   before they can touch the roster."
  [db game-key]
  (when-let [open (persist/find-open-game db game-key)]
    (let [existing (get-in @games [:games game-key])
          record (if (and existing (nil? (:game existing)))
                   (merge existing open)
                   (merge {:key game-key :game nil :history [] :channels #{}} open))]
      (swap! games assoc-in [:games game-key] record)
      record)))

(defn- set-slot-in-loaded-lobby!
  [db actor game-key index player-name seats]
  (swap!
   games
   assoc-in [:games game-key :invocation :players]
   (assoc seats index player-name))
  (let [invocation (get-in @games [:games game-key :invocation])
        players (:players invocation)
        readiness (lobby/normalize-readiness players (:readiness (get-in @games [:games game-key])))
        ;; 2-arity: leave :created-by alone, a joiner does not own the lobby
        _ (persist/create-open-game! db game-key invocation)
        _ (persist/update-open-lobby! db game-key {:readiness readiness})
        _ (swap! games assoc-in [:games game-key :readiness] readiness)
        _ (send-channels!
           (lobby-member-channels (get-in @games [:games game-key]))
           {:type "player-name" :index index :player player-name})
        _ (send-channels!
           (lobby-member-channels (get-in @games [:games game-key]))
           {:type "lobby-readiness" :readiness readiness})
        live (get-in @games [:games game-key])
        _ (doseq [[ch connected] (:channel-players live)
                  :when (same-player-identity? connected actor)]
            (send! ch
                   (-> live
                       (select-keys [:key :invocation :chat :readiness :visibility :created-by])
                       (assoc :bots (set (filter #(bots/bot? "organism" %) players)))
                       (assoc :type "create"))))
        joined-self? (and (seq player-name) (same-player-identity? player-name actor))
        lobby-projection (-> live
                             (select-keys [:key :invocation :chat :readiness :visibility :created-by])
                             (assoc :bots (set (filter #(bots/bot? "organism" %) players))))]
    {:invocation invocation
     :lobby lobby-projection
     :begun? false
     :joined? joined-self?}))

(defn set-slot!
  "Put `player-name` in seat `index` of an open lobby.

   Persisting here is the whole point. A claimed seat used to live only in the
   registry plus whatever open-game snapshot the browser sent afterwards, so a
   join could be quietly undone by a stale snapshot arriving late.

   Filling the final seat does not start the game. The creator gets an explicit
   final review/start boundary, so a join never begins play out from under the
   table."
   [db actor game-key index player-name]
   (persist/with-open-game-mutation!
    db game-key
    (fn []
      (locking games
    (let [record (ensure-open-game! db game-key)
        seats (vec (get-in record [:invocation :players]))
        actor (canonical-account-name db actor)
        requested-player (canonical-account-name db player-name)
        owner? (lobby-owner? record actor)
        seated-index (first (keep-indexed
                             (fn [i seated]
                               (when (and (seq seated)
                                          (same-player-identity? seated actor))
                                 i))
                             seats))
        requested-account (when (seq requested-player)
                            (persist/canonical-player-name db requested-player))
        requested-bot? (bots/bot? "organism" requested-player)
        bot-account-collision? (and requested-account requested-bot?)
        resolved-player (cond
                          (str/blank? requested-player) ""
                          (same-player-identity? requested-player actor) actor
                          requested-account requested-account
                          requested-bot? requested-player
                          :else nil)]
    (cond
      (nil? record) {:error "no such open game"}
      (observer? actor) {:error "sign in to join this game"}
      (not (and (integer? index) (<= 0 index) (< index (count seats))))
      {:error "no such seat"}
      bot-account-collision? {:error "bot names cannot also be player accounts"}
      (and (= index seated-index)
           (not (same-player-identity? actor resolved-player)))
      {:error "your seat always uses your account name"}
      (and (some? seated-index) (not owner?))
      {:error "you are already in this game"}
      (and (= "private" (:visibility record)) (not owner?))
      {:error "enter the lobby password to join this private game"}
      (and (not owner?)
           (not (same-player-identity? actor resolved-player)))
      {:error "you can only join as your account name"}
      (and owner?
           (seq resolved-player)
           (not (same-player-identity? actor resolved-player))
           (not requested-bot?))
      {:error "human players must claim their own seats"}
      (and owner?
           (seq (nth seats index))
           (not (same-player-identity? (nth seats index) resolved-player)))
      {:error "use the remove control to open an occupied seat"}
      (and (not owner?) (not (str/blank? (nth seats index))))
      {:error (str "that seat is taken by " (nth seats index))}
      (nil? resolved-player) {:error "choose a registered player or bot"}
      (some (fn [[i seated]]
              (and (not= i index)
                   (seq seated)
                   (same-player-identity? seated resolved-player)))
            (map-indexed vector seats))
      {:error "that player is already in this game"}
      :else (set-slot-in-loaded-lobby! db actor game-key index resolved-player seats)))))))

(defn- admission-rate-limited?
  [db game-key player]
  (persist/record-private-admission-attempt!
   db game-key (persist/player-identity-key player)
   (System/currentTimeMillis) admission-window-ms admission-limit))

(defn- private-admission-problem
  [db record game-key player password]
  (when (= "private" (:visibility record))
    (cond
      (admission-rate-limited? db game-key player) "too many password attempts; try again shortly"
      (not (and (string? password)
                (seq password)
                (string? (:password-hash record))
                (hashers/check password (:password-hash record))))
      "incorrect lobby password")))

(defn join-open-game!
  "Take a seat in an open lobby on behalf of `player`, with the checks a click
   from the games list needs — the seat has to exist, be empty, and the player
   must not already be seated. Returns {:invocation :begun?} or {:error}."
  ([db game-key index player]
   (join-open-game! db game-key index player nil))
  ([db game-key index player password]
   (persist/with-open-game-mutation!
    db game-key
    (fn []
      (locking games
    (if-let [record (ensure-open-game! db game-key)]
      (let [player (canonical-account-name db player)
            invocation (:invocation record)
            players (vec (:players invocation))
            seats (or (:player-count invocation) (count players))
            admission-problem (private-admission-problem db record game-key player password)]
        (cond
          admission-problem {:error admission-problem}

          (bots/bot? "organism" player)
          {:error "bot names cannot be player accounts"}

          (not (and (integer? index) (<= 0 index) (< index (count players))))
          {:error "no such seat"}

          (some #(same-player-identity? player %) (take seats players))
          {:error "you are already in this game"}

          (not (str/blank? (nth players index)))
          {:error (str "that seat is taken by " (nth players index))}

          :else
          ;; The joiner is both the actor and the name; starting remains a
          ;; separate creator-only command.
          ;; Password admission is complete here; use the narrow seat mutation
          ;; directly so the generic websocket seat command cannot bypass it.
          (set-slot-in-loaded-lobby! db player game-key index player players)))
      {:error "no such open game"}))))))

(defn set-ready!
  [db actor game-key ready? channel]
  (persist/with-open-game-mutation!
   db game-key
   (fn []
     (locking games
   (let [record (ensure-open-game! db game-key)
        players (get-in record [:invocation :players])
        player (some #(when (same-player-identity? actor %) %) players)]
    (cond
      (nil? record) (reject! channel "this lobby is no longer open")
      (nil? player) (reject! channel "only seated players can ready up")
      (bots/bot? "organism" player) (reject! channel "bots are always ready")
      :else
      (let [readiness (assoc (or (:readiness record) {})
                             (persist/player-identity-key player)
                             (boolean ready?))]
        (persist/update-open-lobby! db game-key {:readiness readiness})
        (swap! games assoc-in [:games game-key :readiness] readiness)
        (send-channels! (lobby-member-channels (get-in @games [:games game-key]))
                        {:type "lobby-ready" :player player :ready (boolean ready?)})
        {:readiness readiness})))))))

(defn kick-player!
  [db actor game-key index channel]
  (persist/with-open-game-mutation!
   db game-key
   (fn []
     (locking games
   (let [record (ensure-open-game! db game-key)
        players (vec (get-in record [:invocation :players]))
        target (when (and (integer? index) (< -1 index (count players)))
                 (nth players index))
        target-channels (->> (get-in @games [:games game-key :channel-players])
                             (keep (fn [[ch connected]]
                                     (when (same-player-identity? connected target) ch))))]
    (cond
      (nil? record) (reject! channel "this lobby is no longer open")
      (not (lobby-owner? record actor))
      (reject! channel "only the lobby creator can remove players")
      (or (nil? target) (str/blank? target)) (reject! channel "that seat is already open")
      (lobby-owner? record target)
      (reject! channel "the lobby creator cannot remove their own seat")
      :else
      (let [result (set-slot-in-loaded-lobby! db actor game-key index "" players)]
        (doseq [target-channel target-channels]
          (send! target-channel {:type "lobby-removed" :reason "The lobby owner removed you."}))
        result)))))))

(defn- maybe-run-bot-turns!
  "After a turn change, if the new current player is a bot, spawn a future
   that runs bot turns until a human's turn (or game-over)."
  [db game-key]
  (locking (mutations/game-lock game-key)
  (let [game-state (if db (persist/load-game db game-key)
                      (get-in @games [:games game-key]))
        gs (:game game-state)
        invocation (:invocation game-state)
        all-players (set (:players invocation))
        humans (set (remove #(or (contains? (set (:bots game-state)) %)
                                (bots/bot? "organism" %)) all-players))
        current (when gs (game/current-player gs))]
    (when (and gs (not (get-in gs [:state :winner]))
               (contains? all-players current) (not (contains? humans current)))
      ((requiring-resolve 'organism.routes.organism-bot/run-bot-until-human!)
       games game-key humans 600 nil nil db)))))

(def ^:private max-client-choice-depth 8)
(def ^:private max-client-choice-states 10000)

(defn- legal-client-state?
  "Accept only a state reachable through the authoritative engine choice tree.
   The legacy browser still submits a completed state, so validation walks the
   bounded server-owned tree rather than trusting those submitted bytes."
  [current-game candidate]
  (loop [frontier [current-game]
         seen #{(:state current-game)}
         depth 0
         visited 0]
    (cond
      (and (pos? depth)
           (some #(= candidate (:state %)) frontier)) true
      (or (>= depth max-client-choice-depth)
          (>= visited max-client-choice-states)) false
      :else
      (let [current-player (game/current-player current-game)
            remaining (- max-client-choice-states visited (count frontier))
            next-games (->> frontier
                            ;; A submitted choice may hand off the turn, but
                            ;; validation must never walk into that next
                            ;; player's choices.
                            (filter #(= current-player (game/current-player %)))
                            (mapcat (fn [game]
                                      (vals (second (choice/find-state game)))))
                            (filter map?)
                            (remove #(contains? seen (:state %)))
                            (take (max 0 remaining))
                            vec)
            next-states (into seen (map :state next-games))]
        (recur next-games next-states (inc depth)
               (+ visited (count frontier)))))))

(defn update-game-state
  [db player game-key channel {:keys [game complete] :as message}]
  (let [result (mutations/execute!
                db game-key player
                {:operation "legacy" :commandId (str (java.util.UUID/randomUUID))}
                (fn [current]
                  (cond
                    (not (and (= player (game/current-player (:game current)))
                              (lobby-member? current player)
                              (nil? (get-in current [:game :state :winner]))))
                    (mutations/reject 403 "you cannot update this game state")
                    (not (legal-client-state? (:game current) game))
                    (mutations/reject 422 "that state is not a legal game choice")
                    :else {:status :accepted :game (assoc (:game current) :state game)})))]
    (when (= :rejected (:status result)) (reject! channel (:error result)))
    result))

(defn walk-history
  [db player game-key channel message]
  (let [id (str (java.util.UUID/randomUUID))
        result (mutations/execute!
                db game-key player {:operation "undo" :commandId id}
                #(commands/execute-command % player
                                           {:operation "undo" :commandId id
                                            :expectedRevision (commands/current-revision %)}))]
    (when (= :rejected (:status result))
      (reject! channel (if (= "nothing-to-undo" (:error result))
                         "there is nothing to undo in your current turn"
                         "you cannot rewind this game")))
    result))

(defn find-beginning
  [history]
  (when-not (empty? history)
    (let [initial-state (last history)
          initial-player (game/current-player {:state initial-state})
          initial-round (game/current-round {:state initial-state})
          now-back (reverse history)
          beginning
          (last
           (take-while
            (fn [state]
              (let [player (game/current-player {:state state})
                    round (game/current-round {:state state})]
                (and
                 (= player initial-player)
                 (= round initial-round))))
            now-back))]
      (if (empty? beginning)
        initial-state
        beginning))))

(defn clear-player-turn
  [db player game-key channel message]
  (let [result (mutations/execute!
                db game-key player {:operation "clear" :commandId (str (java.util.UUID/randomUUID))}
                (fn [{:keys [game history] :as state}]
                  (let [beginning (find-beginning history)]
                    (if (and (= player (game/current-player game))
                             (lobby-member? state player)
                             (nil? (get-in game [:state :winner]))
                             (not (game/beginning-of-turn? game))
                             (not= beginning (:state game)))
                      {:status :accepted :game (assoc game :state beginning)
                       :history-index (.indexOf (vec history) beginning)}
                      (mutations/reject 409 "you cannot clear this player turn")))))]
    (when (= :rejected (:status result)) (reject! channel (:error result)))
    result))

(defn timestamp
  []
  (quot (System/currentTimeMillis) 1000))

(defn update-chat
  [db player-key game-key channel {:keys [message client-id] :as received}]
  (let [_ (when (instance? com.mongodb.DB db)
            (reconcile-published-game! db game-key))
        active? (if (instance? com.mongodb.DB db)
                  (some? (persist/load-game db game-key))
                  (some? (get-in @games [:games game-key :game])))
        mutate
        (fn []
          (locking (if active? (mutations/game-lock game-key) games)
            (let [_ (when (instance? com.mongodb.DB db)
                      (when-let [durable (or (persist/load-game db game-key)
                                            (persist/find-open-game db game-key))]
                        (swap! games update-in [:games game-key] merge durable)))
                  game-state (get-in @games [:games game-key])
                  roster (get-in game-state [:invocation :players])
                  player (when (lobby-member? game-state player-key)
                           (some #(when (same-player-identity? player-key %) %) roster))]
              (cond
                (nil? player)
                (reject! channel "only players in this game can post messages" "chat")

                (or (not (string? message)) (str/blank? message))
                (reject! channel "write a message before sending" "chat")

                (> (count message) 1000)
                (reject! channel "messages must be 1000 characters or fewer" "chat")

                (and (some? client-id)
                     (or (not (string? client-id)) (> (count client-id) 100)))
                (reject! channel "that message could not be identified" "chat")

                :else
                (let [chat-message (cond-> {:type "chat"
                                            :id (str (java.util.UUID/randomUUID))
                                            :player player
                                            :time (timestamp)
                                            :message message}
                                     client-id (assoc :client-id client-id))
                      accepted (persist/accept-chat! db game-key chat-message)
                      chat-message (:message accepted)
                      channels (if (:game game-state)
                                 (:channels game-state)
                                 (lobby-member-channels game-state))]
                  (if-let [error (:error accepted)]
                    (reject! channel error "chat")
                    (do
                      (swap! games update-in [:games game-key :chat]
                             (fn [chat]
                               (if (some #(= (:id %) (:id chat-message)) chat)
                                 chat (conj (vec chat) chat-message))))
                      (doseq [ch channels]
                        (send! ch chat-message)
                        (send! ch {:type "chat.created"
                                   :version events/version
                                   :gameId game-key
                                   :message chat-message}))
                      accepted)))))))]
    (if active?
      (mutate)
      (persist/with-open-game-mutation! db game-key mutate))))

(defn notify-clients!
  [{:keys [db player game-key]} channel raw]
  (let [message (read-json raw)
        [game-state promoted?] (reconcile-published-game! db game-key)]
    (if promoted?
      (announce-published-game! game-state)
      (condp = (:type message)
        "create" (update-create-game db player game-key channel message)
        "player-name" (update-player-name db player game-key channel message)
        "lobby-ready" (set-ready! db player game-key (:ready message) channel)
        "lobby-kick" (kick-player! db player game-key (:index message) channel)
        "open-game" (update-open-game db player game-key channel message)
        "trigger-creation" (trigger-creation db player game-key channel message)
        "game-state" (update-game-state db player game-key channel message)
        "history" (walk-history db player game-key channel message)
        "clear" (clear-player-turn db player game-key channel message)
        "chat" (update-chat db player game-key channel message)
        (log/error "unknown message type!" (:type message))))))

(defn websocket-callbacks
  [db player game-key]
  (let [config {:db db :player player :game-key game-key}]
    {:on-open (partial connect! config)
     :on-close (partial disconnect! config)
     :on-receive (partial notify-clients! config)}))

(defn ws-handler
  [db {:keys [path-params session] :as request}]
  (let [play (:play path-params)
        player (or (:player session) "--observer--")]
    (hk/as-channel request (websocket-callbacks db player play))))

(defn websocket-routes
  [db]
  [["/ws/organism/play/:play"
    {:middleware [middleware/wrap-require-origin]
     :handler (partial ws-handler db)}]])
