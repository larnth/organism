(ns organism.api.projection-test
  (:require
   [clojure.test :refer :all]
   [organism.api.actions :as actions]
   [organism.api.projection :as projection]
   [organism.examples :as examples]))

(def active-game-state
  {:key "pond-life"
   :_id "database-id"
   :invocation {:players ["alice" "bob"]
                :colors [[:green "#00aa66"]]
                :password "must-not-leak"}
   :game {:rings [:yellow :red]
          :turn-order ["alice" "bob"]
          :state {:round 2
                  :elements {[:red 0] {:player "alice" :type :eat}}
                  :food {[:yellow 0] 2}
                  :captures {"alice" #{{:type :grow}}}
                  :player-turn {:player "alice"}}}
   :history [{:round 0} {:round 1} {:round 2}]
   :chat [{:type "chat" :player "alice" :message "hello" :_id "chat-id"}]
   :channels #{:private-channel}})

(deftest projects-active-game-for-acting-player
  (let [advertised [{:actionId "legal-action" :kind "move"}]]
    (with-redefs [actions/action-context
                  (fn [game actor]
                    (is (= "alice" actor))
                    {:game (assoc-in game [:state :round] 3)
                     :phase :move-from
                     :actions advertised})]
      (let [result (projection/project-game active-game-state "alice")]
        (is (= "pond-life" (:gameId result)))
        (is (= 2 (:revision result)))
        (is (= "active" (:status result)))
        (is (= {:player "alice" :role "player" :canAct true}
               (:viewer result)))
        (is (= advertised (:legalActions result)))
        (is (= 3 (get-in result [:game "state" "round"])))
        (is (= {:entries 3 :currentIndex 2}
               (:historySummary result)))
        (is (= [["red" 0]
                {"player" "alice" "type" "eat"}]
               (first (get-in result [:game "state" "elements"]))))))))

(deftest waiting-player-never-receives-the-active-players-controls
  (with-redefs [actions/action-context
                (fn [& _]
                  (throw (ex-info "must not derive choices for a waiting player" {})))]
    (let [result (projection/project-game active-game-state "bob")]
      (is (= {:player "bob" :role "player" :canAct false}
             (:viewer result)))
      (is (= "alice" (get-in result [:game "state" "player-turn" "player"])))
      (is (empty? (:legalActions result))))))

(deftest projects-canonical-engine-actions-for-current-player
  (let [game-state {:key "canonical-game"
                    :invocation {:players ["orb" "mass"]}
                    :game examples/two-player-close
                    :history [examples/two-player-close]
                    :chat []}
        result (projection/project-game game-state "orb")]
    (is (true? (get-in result [:viewer :canAct])))
    (is (= 6 (count (:legalActions result))))
    (is (every? #(= "introduce" (:kind %)) (:legalActions result)))
    (is (every? #(= "orb" (:actor %)) (:legalActions result)))))

(deftest projects-observer-without-private-fields
  (let [result (projection/project-game active-game-state nil)
        rendered (pr-str result)]
    (is (= {:player nil :role "observer" :canAct false}
           (:viewer result)))
    (is (empty? (:legalActions result)))
    (is (not (re-find #"channels|private-channel|password|database-id|chat-id" rendered)))))

(deftest projects-completed-game
  (let [completed (assoc-in active-game-state [:game :state :winner] "alice")
        result (projection/project-game completed "alice")]
    (is (= "completed" (:status result)))
    (is (false? (get-in result [:viewer :canAct])))))

(deftest projects-open-lobby
  (let [lobby (assoc active-game-state :game nil :history [])
        result (projection/project-game lobby "alice")]
    (is (= "waiting" (:status result)))
    (is (= 0 (:revision result)))
    (is (= {:entries 0 :currentIndex 0}
           (:historySummary result)))))

(deftest anonymous-viewer-is-not-an-empty-lobby-seat
  (let [lobby (assoc active-game-state
                     :invocation {:players ["alice" nil]}
                     :game nil
                     :history [])
        result (projection/project-game lobby nil)]
    (is (= "observer" (get-in result [:viewer :role])))
    (is (false? (get-in result [:viewer :canAct])))))

(deftest missing-game-has-no-projection
  (is (nil? (projection/project-game nil "alice"))))

(deftest json-safe-values-are-deterministic
  (is (= {"coordinateMap" [[["blue" 2] "second"]
                            [["red" 1] "first"]]
          "keywords" ["eat" "grow"]}
         (projection/json-safe
          {:coordinateMap {[:red 1] :first [:blue 2] :second}
           :keywords #{:grow :eat}}))))
