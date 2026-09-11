(ns organism.api.projection
  (:require
   [organism.api.actions :as actions]
   [organism.api.json :as json]))

(def json-safe json/json-safe)

(defn- game-status
  [game-state]
  (cond
    (nil? (:game game-state)) "waiting"
    (some? (get-in game-state [:game :state :winner])) "completed"
    :else "active"))

(defn- revision
  [history]
  (max 0 (dec (count history))))

(defn project-game
  "Create the recipient-safe modern-client projection for one viewer."
  [game-state viewer]
  (when game-state
    (let [status (game-status game-state)
          players (set (get-in game-state [:invocation :players]))
          role (if (and (some? viewer) (contains? players viewer))
                 "player"
                 "observer")
          current-player (get-in game-state [:game :state :player-turn :player])
          can-act (and (= status "active")
                       (= role "player")
                       (= viewer current-player))
          action-context (when can-act
                           (actions/action-context (:game game-state) viewer))
          projected-game (or (:game action-context) (:game game-state))
          effective-can-act (and can-act (seq (:actions action-context)))
          history (or (:history game-state) [])
          entries (count history)]
      {:gameId (:key game-state)
       :revision (revision history)
       :status status
       :viewer {:player viewer
                :role role
                :canAct (boolean effective-can-act)}
       :invocation (json/json-safe (:invocation game-state))
       :game (json/json-safe projected-game)
       :legalActions (or (:actions action-context) [])
       :historySummary {:entries entries
                        :currentIndex (revision history)}
       :chat (json/json-safe (or (:chat game-state) []))})))
