(ns organism.api.events-test
  (:require
   [clojure.test :refer :all]
   [organism.api.actions :as actions]
   [organism.api.events :as events]))

(def event-game-state
  {:key "pond-life"
   :invocation {:players ["alice" "bob"]}
   :game {:state {:round 2
                  :elements {}
                  :food {}
                  :captures {"alice" [] "bob" []}
                  :player-turn {:player "alice"}}}
   :history [{:round 0
              :player-turn {:player "bob"}}
             {:round 1
              :player-turn {:player "alice"}}
             {:round 2
              :player-turn {:player "alice"}}]
   :chat []})

(deftest returns-monotonic-updates-after-a-known-revision
  (with-redefs [actions/action-context
                (fn [game actor]
                  {:game game
                   :actions [{:actionId (str "act-" actor)}]})]
    (let [result (events/catch-up event-game-state "alice" 0)]
      (is (= {:gameId "pond-life" :fromRevision 0 :toRevision 2}
             (dissoc result :events)))
      (is (= [1 2] (mapv :revision (:events result))))
      (is (every? #(= "game.updated" (:type %)) (:events result)))
      (is (= [1 2]
             (mapv #(get-in % [:projection :game "state" "round"])
                   (:events result)))))))

(deftest returns-no-events-when-the-client-is-current
  (is (= {:gameId "pond-life"
          :fromRevision 2
          :toRevision 2
          :events []}
         (events/catch-up event-game-state "bob" 2))))

(deftest returns-a-snapshot-when-the-requested-revision-is-unavailable
  (doseq [revision [-1 3]]
    (let [result (events/catch-up event-game-state nil revision)
          event (first (:events result))]
      (is (= ["snapshot"] (mapv :type (:events result))))
      (is (= 2 (:revision event)))
      (is (= "observer" (get-in event [:projection :viewer :role]))))))
