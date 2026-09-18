(ns organism.api.projection
  (:require
   [organism.api.actions :as actions]
   [organism.api.commands :as commands]
   [organism.api.json :as json]
   [organism.bots :as bots]
   [organism.lobby :as lobby]))

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
          players (get-in game-state [:invocation :players])
          game-type (or (:game-type game-state)
                        (get-in game-state [:invocation :game-type])
                        "organism")
          bot-set (set (:bots game-state))
          bot? #(or (contains? bot-set %)
                    (bots/bot? game-type %))
          member? (if (= "waiting" status)
                    (lobby/member? (:created-by game-state) players viewer bot?)
                    (lobby/human-seat? players viewer bot?))
          role (if (lobby/human-seat? players viewer bot?)
                 "player"
                 "observer")
          current-player (get-in game-state [:game :state :player-turn :player])
          can-act (and (not (:replay? game-state)) (= status "active")
                       (= role "player")
                       (= viewer current-player))
          action-context (when can-act
                           (actions/action-context (:game game-state) viewer))
          projected-game (or (:game action-context) (:game game-state))
          effective-can-act (and can-act (seq (:actions action-context)))
          history (or (:history game-state) [])
          entries (count history)]
      {:gameId (:key game-state)
       :instanceId (commands/instance-id game-state)
       :revision (commands/current-revision game-state)
       :status status
       :lobby (when (= "waiting" status)
                {:owner (lobby/visible-creator (:visibility game-state) (:created-by game-state)
                                               players viewer bot?)
                 :visibility (or (:visibility game-state) "open")
                 :member (boolean member?)
                 :readiness (if member? (json/json-safe (or (:readiness game-state) {})) {})
                 :bots (if (or member? (not= "private" (:visibility game-state)))
                         (vec (filter bot? players)) [])
                 :blocker (if (or member? (not= "private" (:visibility game-state)))
                            (lobby/launch-blocker players (:readiness game-state)
                                                 (set (filter bot? players)))
                            "Join this lobby to see readiness.")
                 :availableBots (if (lobby/same-player? (:created-by game-state) viewer)
                                  (mapv #(assoc % :player (bots/next-instance-name (:name %) players))
                                        (bots/list-bots game-type)) [])
                 :canStart (boolean (and (lobby/same-player? (:created-by game-state) viewer)
                                         (nil? (lobby/launch-blocker players (:readiness game-state)
                                                                     (set (filter bot? players))))))})
       :viewer {:player viewer
                :role role
                :canAct (boolean effective-can-act)
                :canUndo (boolean (and member? (not (:replay? game-state))
                                       (commands/undo-index game-state viewer)))}
       :invocation (json/json-safe
                    (if (= "waiting" status)
                      (lobby/visible-invocation (:visibility game-state) (:invocation game-state) viewer bot?)
                      (:invocation game-state)))
       :game (json/json-safe projected-game)
       :legalActions (or (:actions action-context) [])
       :historySummary {:entries entries
                        :currentIndex (revision history)}
       :chat (json/json-safe (if (and (= "waiting" status) (not member?))
                              [] (or (:chat game-state) [])))})))
