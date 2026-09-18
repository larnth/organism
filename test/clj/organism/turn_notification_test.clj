(ns organism.turn-notification-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [organism.turn-notification :as notification]))

(defn game-for
  ([player] (game-for player nil))
  ([player winner]
   {:state {:player-turn {:player player}
            :winner winner}}))

(deftest detects-when-a-turn-becomes-the-viewers
  (testing "only a transition from another player rings"
    (is (true? (notification/turn-became-yours?
                "alice" (game-for "bob") (game-for "alice"))))
    (is (false? (notification/turn-became-yours?
                 "alice" (game-for "alice") (game-for "alice"))))
    (is (false? (notification/turn-became-yours?
                 "" (game-for "bob") (game-for "")))))
  (testing "completed games do not ask for another turn"
    (is (false? (notification/your-turn? "alice" (game-for "alice" "alice"))))))

(deftest formats-a-stable-and-an-attention-title
  (is (= "Pond Life — ORGANISM"
         (notification/tab-title "Pond Life" false false)))
  (is (= "● YOUR TURN — Pond Life"
         (notification/tab-title "Pond Life" true true)))
  (is (= "Pond Life — ORGANISM"
         (notification/tab-title "Pond Life" true false))))
