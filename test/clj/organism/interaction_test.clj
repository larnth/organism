(ns organism.interaction-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [organism.interaction :as interaction]))

(def playing
  {:turn-order ["alice" "bob"]
   :state {:round 0 :player-turn {:player "alice"} :winner nil}})

(deftest only-the-current-authenticated-player-can-interact
  (is (true? (interaction/can-act? "alice" playing nil)))
  (doseq [viewer ["bob" "spectator" "--observer--" "" nil]]
    (is (false? (interaction/can-act? viewer playing nil)) (str viewer)))
  (is (false? (interaction/can-act? "alice" (assoc playing :turn-order ["bob"]) nil)))
  (is (false? (interaction/can-act? "alice" {} nil))))

(deftest history-and-finished-games-are-read-only
  (doseq [cursor [0 1 12]]
    (is (false? (interaction/can-act? "alice" playing cursor))))
  (is (false? (interaction/can-act? "alice" (assoc-in playing [:state :winner] "alice") nil))))

(deftest authority-follows-the-live-turn-not-the-previous-player
  (let [next-turn (assoc-in playing [:state :player-turn :player] "bob")]
    (is (false? (interaction/can-act? "alice" next-turn nil)))
    (is (true? (interaction/can-act? "bob" next-turn nil)))))
