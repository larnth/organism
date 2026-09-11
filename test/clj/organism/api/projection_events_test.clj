(ns organism.api.projection-events-test
  (:require
   [clojure.test :refer :all]
   [organism.api.actions :as actions]
   [organism.persist :as persist]
   [organism.routes.websockets :as ws]))

(def projected-game-state
  {:key "pond-life"
   :invocation {:players ["alice" "bob"]}
   :game {:state {:round 2
                  :elements {}
                  :food {}
                  :captures {"alice" [] "bob" []}
                  :player-turn {:player "alice"}}}
   :history [{:round 1} {:round 2}]
   :chat []})

(use-fixtures
  :each
  (fn [run]
    (reset! ws/games {:games {}})
    (run)
    (reset! ws/games {:games {}})))

(deftest sends-a-recipient-specific-projection-to-each-connected-client
  (let [sent (atom [])]
    (reset! ws/games
            {:games {"pond-life"
                     (assoc projected-game-state
                            :channels #{:alice-channel :bob-channel :observer-channel}
                            :channel-players {:alice-channel "alice"
                                              :bob-channel "bob"
                                              :observer-channel "--observer--"})}})
    (with-redefs [actions/action-context
                  (fn [game actor]
                    {:game game
                     :phase :move-from
                     :actions [{:actionId "move" :actor actor}]})
                  ws/send! (fn [channel message]
                             (swap! sent conj [channel message]))]
      (ws/refresh-projections! projected-game-state)
      (let [messages (into {} @sent)
            alice (get-in messages [:alice-channel :projection])
            bob (get-in messages [:bob-channel :projection])
            observer (get-in messages [:observer-channel :projection])]
        (is (= #{:alice-channel :bob-channel :observer-channel}
               (set (keys messages))))
        (is (every? #(= "projection" (:type %)) (vals messages)))
        (is (every? #(= 1 (:version %)) (vals messages)))
        (is (= [{:actionId "move" :actor "alice"}]
               (:legalActions alice)))
        (is (empty? (:legalActions bob)))
        (is (empty? (:legalActions observer)))
        (is (= "observer" (get-in observer [:viewer :role])))
        (is (nil? (get-in observer [:viewer :player])))))
    (is (= (:game projected-game-state)
           (get-in @ws/games [:games "pond-life" :game])))))

(deftest tracks-the-viewer-for-new-and-existing-websocket-games
  (with-redefs [persist/load-game (constantly nil)
                persist/find-open-game (constantly nil)
                ws/send! (fn [& _])]
    (ws/find-game! :db "new-game" "alice" :alice-channel)
    (ws/find-game! :db "new-game" "bob" :bob-channel)
    (is (= {:alice-channel "alice" :bob-channel "bob"}
           (get-in @ws/games [:games "new-game" :channel-players])))))
