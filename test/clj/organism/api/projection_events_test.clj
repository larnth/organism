(ns organism.api.projection-events-test
  (:require
   [clojure.test :refer :all]
   [organism.api.actions :as actions]
   [organism.persist :as persist]
   [organism.routes.websockets :as ws]))

(def projected-game-state
  {:key "pond-life"
   :invocation {:players ["alice" "bob"] :created 1}
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
        (is (every? #(= "game.updated" (:type %)) (vals messages)))
        (is (every? #(= 1 (:version %)) (vals messages)))
        (is (every? #(= 1 (:revision %)) (vals messages)))
        (is (= [{:actionId "move" :actor "alice"}]
               (:legalActions alice)))
        (is (empty? (:legalActions bob)))
        (is (empty? (:legalActions observer)))
        (is (= "observer" (get-in observer [:viewer :role])))
        (is (nil? (get-in observer [:viewer :player])))))
    (is (= (:game projected-game-state)
           (get-in @ws/games [:games "pond-life" :game])))))

(deftest sends-a-recipient-snapshot-when-a-websocket-connects
  (let [sent (atom [])]
    (with-redefs [persist/load-game (constantly projected-game-state)
                  persist/find-player-game (constantly {})
                  ws/send! (fn [channel message]
                             (swap! sent conj [channel message]))]
      (ws/connect! {:db :db :game-key "pond-life" :player "alice"}
                   :alice-channel)
      (is (= ["initialize" "snapshot"]
             (mapv (comp :type second) @sent)))
      (is (= "alice"
             (get-in (second (second @sent)) [:projection :viewer :player])))
      (is (= 1 (get-in (second (second @sent)) [:revision]))))))

(deftest websocket-observer-snapshots-do-not-expose-the-sentinel-player
  (let [sent (atom [])]
    (with-redefs [persist/load-game (constantly projected-game-state)
                  persist/find-player-game (constantly {})
                  ws/send! (fn [_ message] (swap! sent conj message))]
      (ws/connect! {:db :db
                    :game-key "pond-life"
                    :player "--observer--"}
                   :observer-channel)
      (let [snapshot (second @sent)]
        (is (= "snapshot" (:type snapshot)))
        (is (= "observer" (get-in snapshot [:projection :viewer :role])))
        (is (nil? (get-in snapshot [:projection :viewer :player])))))))

(deftest emits-modern-chat-and-deletion-events-alongside-legacy-events
  (let [sent (atom [])]
    (reset! ws/games
            {:games {"pond-life"
                     (assoc projected-game-state
                            :channels #{:alice-channel})}})
    (with-redefs [ws/send! (fn [_ message] (swap! sent conj message))
                  persist/update-chat! (fn [& _])]
      (ws/update-chat :db "alice" "pond-life" :alice-channel
                      {:player "alice" :message "hello"})
      (is (= ["chat" "chat.created"] (mapv :type @sent)))
      (reset! sent [])
      (ws/drop-game! "pond-life")
      (is (= ["deleted" "game.deleted"] (mapv :type @sent)))
      (is (nil? (get-in @ws/games [:games "pond-life"]))))))

(deftest tracks-the-viewer-for-new-and-existing-websocket-games
  (with-redefs [persist/load-game (constantly nil)
                persist/find-open-game (constantly nil)
                ws/send! (fn [& _])]
    (ws/find-game! :db "new-game" "alice" :alice-channel)
    (ws/find-game! :db "new-game" "bob" :bob-channel)
    (is (= {:alice-channel "alice" :bob-channel "bob"}
           (get-in @ws/games [:games "new-game" :channel-players])))))
