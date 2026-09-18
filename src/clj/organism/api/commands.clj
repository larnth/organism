(ns organism.api.commands
  (:require
   [clojure.string :as str]
   [organism.api.actions :as actions]
   [organism.bots :as bots]
   [organism.lobby :as lobby]))

(defn- field
  [value key]
  (get value key (get value (name key))))

(defn current-revision
  [game-state]
  (or (:revision game-state) (max 0 (dec (count (or (:history game-state) []))))))

(defn instance-id
  "Opaque logical table lifetime: waiting through launch, never the reusable key.
   Historical active records retain their physical-incarnation fallback."
  [game-state]
  (when-let [id (or (:table-id game-state) (:incarnation-id game-state))]
    (actions/action-id :game-instance (str id))))

(defn undo-index
  "Undo only within this actor's current round and turn. No completed rewinds."
  [game-state player]
  (let [history (vec (:history game-state))
        present (get-in game-state [:game :state])
        index (- (count history) 2)
        previous (get history index)]
    (when (and previous (nil? (:winner present))
               (= player (get-in present [:player-turn :player])
                  (get-in previous [:player-turn :player]))
               (= (:round present) (:round previous)))
      index)))

(defn- rejected
  ([http-status error]
   {:status :rejected :http-status http-status :error error})
  ([http-status error extra]
   (merge (rejected http-status error) extra)))

(defn- nonblank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- action-path
  [value]
  (cond
    (nonblank-string? value) [value]
    (and (vector? value)
         (<= 2 (count value) 3)
         (every? nonblank-string? value)) value
    :else nil))

(def ^:private compound-next-phases
  {:eat-to #{:eat-from}
   :grow-element #{:grow-from :grow-to}
   :grow-from #{:grow-to}
   :move-from #{:move-to}})

(defn execute-command
  "Validate and resolve one recipient command without performing side effects."
  [game-state player body]
  (let [action-id (field body :actionId)
        operation (or (field body :operation) "action")
        action-ids (action-path action-id)
        expected-revision (field body :expectedRevision)
        command-id (field body :commandId)
        current-revision (current-revision game-state)
        players (get-in game-state [:invocation :players])
        game-type (or (:game-type game-state)
                      (get-in game-state [:invocation :game-type])
                      "organism")
        bot-set (set (:bots game-state))
        bot? #(or (contains? bot-set %)
                  (bots/bot? game-type %))]
    (cond
      (not (contains? #{"action" "undo"} operation))
      (rejected 400 "operation-invalid")

      (and (= "action" operation) (nil? action-ids))
      (rejected 400 "action-id-required")

      (not (integer? expected-revision))
      (rejected 400 "expected-revision-required")

      (not (nonblank-string? command-id))
      (rejected 400 "command-id-required")

      (nil? player)
      (rejected 401 "authentication-required")

      (not (lobby/human-seat? players player bot?))
      (rejected 403 "not-a-participant")

      (not= expected-revision current-revision)
      (rejected 409 "stale-revision" {:revision current-revision})

      (or (nil? (:game game-state))
          (some? (get-in game-state [:game :state :winner])))
      (rejected 409 "game-not-active")

      (= "undo" operation)
      (if-let [index (undo-index game-state player)]
        {:status :accepted :command-id command-id :player player
         :history-index index
         :game (assoc (:game game-state) :state (nth (:history game-state) index))}
        (rejected 409 "nothing-to-undo"))

      :else
      (loop [current-game (:game game-state)
             remaining action-ids
             base-game nil
             position 0
             previous-phase nil]
        (let [current-id (first remaining)
              {:keys [game phase current-player active? matches]}
              (actions/resolve-current-action current-game player current-id)
              compound? (< 1 (count action-ids))
              phase-valid? (or (not compound?)
                               (if (zero? position)
                                 (contains? compound-next-phases phase)
                                 (contains? (get compound-next-phases previous-phase #{}) phase)))
              path-complete? (or (not compound?)
                                 (next remaining)
                                 (not (contains? compound-next-phases phase)))
              next-game (:game (first matches))]
          (cond
            (not phase-valid?)
            (rejected 400 "invalid-action-path")

            (not active?)
            (rejected 409 "game-not-active")

            (not= player current-player)
            (rejected 403 "not-your-turn")

            (not= 1 (count matches))
            (rejected 422 "action-not-legal")

            (not path-complete?)
            (rejected 400 "invalid-action-path")

            (next remaining)
            (recur next-game
                   (next remaining)
                   (or base-game game)
                   (inc position)
                   phase)

            :else
            {:status :accepted
             :action-id action-id
             :command-id command-id
             :player player
             :game next-game
             :base-game (or base-game game)}))))))