(ns organism.interaction
  "Local affordances follow the session's player and the live turn.
   These guards supplement, never replace, server authorization."
  (:require [organism.game :as game]))

(defn can-act?
  [viewer game cursor]
  (boolean
   (and (seq viewer)
        (not= viewer game/observer-key)
        (nil? cursor)
        (nil? (get-in game [:state :winner]))
        (some #{viewer} (:turn-order game))
        (= viewer (game/current-player game)))))
