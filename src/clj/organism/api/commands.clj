(ns organism.api.commands
  (:require
   [clojure.string :as str]
   [organism.api.actions :as actions]))

(defn- field
  [value key]
  (get value key (get value (name key))))

(defn- revision
  [game-state]
  (max 0 (dec (count (or (:history game-state) [])))))

(defn- rejected
  ([http-status error]
   {:status :rejected :http-status http-status :error error})
  ([http-status error extra]
   (merge (rejected http-status error) extra)))

(defn- nonblank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn execute-command
  "Validate and resolve one recipient command without performing side effects."
  [game-state player body]
  (let [action-id (field body :actionId)
        expected-revision (field body :expectedRevision)
        command-id (field body :commandId)
        current-revision (revision game-state)
        players (set (get-in game-state [:invocation :players]))]
    (cond
      (not (nonblank-string? action-id))
      (rejected 400 "action-id-required")

      (not (integer? expected-revision))
      (rejected 400 "expected-revision-required")

      (not (nonblank-string? command-id))
      (rejected 400 "command-id-required")

      (nil? player)
      (rejected 401 "authentication-required")

      (not (contains? players player))
      (rejected 403 "not-a-participant")

      (not= expected-revision current-revision)
      (rejected 409 "stale-revision" {:revision current-revision})

      (or (nil? (:game game-state))
          (some? (get-in game-state [:game :state :winner])))
      (rejected 409 "game-not-active")

      :else
      (let [{:keys [game current-player active? matches]}
            (actions/resolve-current-action (:game game-state) player action-id)]
        (cond
          (not active?)
          (rejected 409 "game-not-active")

          (not= player current-player)
          (rejected 403 "not-your-turn")

          (not= 1 (count matches))
          (rejected 422 "action-not-legal")

          :else
          {:status :accepted
           :action-id action-id
           :command-id command-id
           :player player
           :game (:game (first matches))
           :base-game game})))))