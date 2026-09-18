(ns organism.turn-notification
  (:require [clojure.string :as str]))

(defn your-turn?
  "True only while a signed-in player's active game is waiting on them."
  [player game]
  (and (not (str/blank? player))
       (nil? (get-in game [:state :winner]))
       (= player (get-in game [:state :player-turn :player]))))

(defn turn-became-yours?
  [player previous-game current-game]
  (and (not (your-turn? player previous-game))
       (your-turn? player current-game)))

(defn tab-title
  [game-key your-turn? attention-frame?]
  (if (and your-turn? attention-frame?)
    (str "● YOUR TURN — " game-key)
    (str game-key " — ORGANISM")))
