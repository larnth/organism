(ns organism.websocket-connections-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [organism.choice :as choice]
   [organism.api.commands :as commands]
   [organism.game :as game]
   [organism.persist :as persist]
   [organism.routes.websockets :as ws])
  (:import [java.util.concurrent CyclicBarrier TimeUnit]))

(def ^:dynamic *durable* nil)

(defn memory-commit [_ key current next-game _ index]
  (when (nil? index) (persist/update-state! nil key (:state next-game)))
  (let [accepted (assoc current :key key :game next-game
         :revision (inc (commands/current-revision current))
         :history (conj (if (some? index) (subvec (vec (:history current)) 0 index)
                           (vec (:history current))) (:state next-game)))]
    (swap! *durable* assoc key accepted)
    accepted))

(use-fixtures :each
  (fn [run]
    (let [before @ws/games]
      (reset! ws/games {:games {}})
      (try
        (binding [*durable* (atom {})]
        (with-redefs [persist/load-game (fn [_ key] (or (get @*durable* key) (get-in @ws/games [:games key])))
                      persist/find-command (fn [& _] nil)
                      persist/repair-mutation! (fn [& _])
                      persist/mark-mutation-finalized! (fn [& _])
                      persist/commit-mutation! memory-commit
                      persist/update-player-games! (fn [& _])
                      ws/refresh-projections! (fn [& _])]
          (run)))
        (finally (reset! ws/games before))))))

(deftest simultaneous-game-loads-keep-both-live-connections
  ;; The lifecycle owner now serializes cold loads with deletion. Hold the first
  ;; load while the second browser enters, then prove both channels survive.
  (let [entered (promise) release (promise) second-entered (promise)
        saved {:key "table" :invocation {:created 1 :players ["alice" "bob"]}
               :game {:state {:player-turn {:player "alice"}}} :history []}]
    (with-redefs [persist/load-game (fn [_ _]
                                     (deliver entered true)
                                     (deref release 3000 nil)
                                     saved)]
      (let [alice (future (ws/find-game! nil "table" "alice" :alice-channel))]
        (is (= true (deref entered 3000 ::timeout)))
        (let [bob (future (deliver second-entered true)
                          (ws/find-game! nil "table" "bob" :bob-channel))]
        (is (= true (deref second-entered 3000 ::timeout)))
        (try (is (= ::waiting (deref bob 100 ::waiting)))
             (finally (deliver release true)))
        (is (not= ::timeout (deref alice 4000 ::timeout)))
        (is (not= ::timeout (deref bob 4000 ::timeout)))
        (is (= #{:alice-channel :bob-channel}
               (set (get-in @ws/games [:games "table" :channels]))))
        (is (= {:alice-channel "alice" :bob-channel "bob"}
               (get-in @ws/games [:games "table" :channel-players]))))))))

(deftest a-waiting-player-or-spectator-cannot-submit-someone-elses-state
  (let [state {:player-turn {:player "alice"} :round 0 :winner nil}
        room {:invocation {:players ["alice" "bob"]}
              :game {:state state :turn-order ["alice" "bob"]}
              :history [state] :channels #{:channel}}
        forged {:game (assoc state :winner "bob") :complete true}
        sent (atom [])]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [persist/update-state! (fn [& _] (throw (ex-info "Unexpected write" {})))
                  persist/complete-game! (fn [& _] (throw (ex-info "Unexpected completion" {})))
                  ws/send-channels! (fn [& _] (throw (ex-info "Unexpected broadcast" {})))
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (doseq [actor ["bob" game/observer-key nil]]
        (ws/update-game-state nil actor "table" :channel forged)
        (is (= room (get-in @ws/games [:games "table"])) (str "Rejected actor: " actor)))
      (is (= 3 (count @sent)))
      (is (every? #(= "error" (get-in % [1 :type])) @sent)))))

(deftest a-current-player-cannot-submit-a-state-outside-the-engine-choice-tree
  (let [present {:player-turn {:player "alice"} :stage :present}
        legal {:player-turn {:player "alice"} :stage :legal}
        forged {:player-turn {:player "alice"} :stage :forged}
        room {:invocation {:players ["alice" "bob"]}
              :game {:state present :turn-order ["alice" "bob"]}
              :history [present]
              :channels #{:alice}
              :channel-players {:alice "alice"}}
        sent (atom [])]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [choice/find-state
                  (fn [{:keys [state] :as current}]
                    (if (= state present)
                      [:test {:legal (assoc current :state legal)}]
                      [:done {}]))
                  persist/update-state! (fn [& _] (throw (ex-info "Unexpected write" {})))
                  ws/send-channels! (fn [& _] (throw (ex-info "Unexpected broadcast" {})))
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/update-game-state nil "alice" "table" :alice
                            {:type "game-state" :game forged :complete true})
      (is (= room (get-in @ws/games [:games "table"])))
      (is (= [[:alice {:type "error"
                       :message "that state is not a legal game choice"}]]
             @sent)))))

(deftest engine-choice-state-is-accepted-from-the-current-player
  (let [present {:player-turn {:player "alice"} :stage :present}
        legal {:player-turn {:player "alice"} :stage :legal}
        room {:invocation {:players ["alice" "bob"]}
              :game {:state present :turn-order ["alice" "bob"]}
              :history [present]
              :channels #{:alice}
              :channel-players {:alice "alice"}}
        persisted (atom [])]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [choice/find-state
                  (fn [{:keys [state] :as current}]
                    (if (= state present)
                      [:test {:legal (assoc current :state legal)}]
                      [:done {}]))
                  persist/update-state! (fn [_ _ state] (swap! persisted conj state))
                  ws/send-channels! (fn [& _])]
      (ws/update-game-state nil "alice" "table" :alice
                            {:type "game-state" :game legal :complete true})
      (is (= legal (get-in @ws/games [:games "table" :game :state])))
      (is (= [legal] @persisted)))))

(deftest duplicate-current-player-submission-is-applied-once
  (let [present {:player-turn {:player "alice"} :stage :present}
        legal {:player-turn {:player "alice"} :stage :legal}
        room {:invocation {:players ["alice" "bob"]}
              :game {:state present :turn-order ["alice" "bob"]}
              :history [present]
              :channels #{:alice}
              :channel-players {:alice "alice"}}
        persisted (atom [])
        sent (atom [])
        message {:type "game-state" :game legal :complete true}]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [choice/find-state
                  (fn [{:keys [state] :as current}]
                    (if (= state present)
                      [:test {:legal (assoc current :state legal)}]
                      [:done {}]))
                  persist/update-state! (fn [_ _ state] (swap! persisted conj state))
                  ws/send-channels! (fn [& _])
                  ws/send! (fn [channel response] (swap! sent conj [channel response]))]
      (ws/update-game-state nil "alice" "table" :alice message)
      (ws/update-game-state nil "alice" "table" :alice message)
      (is (= [legal] @persisted))
      (is (= 2 (count (get-in @ws/games [:games "table" :history]))))
      (is (= [[:alice {:type "error"
                       :message "that state is not a legal game choice"}]]
             @sent)))))

(deftest an-account-colliding-with-a-bot-cannot-submit-the-bots-state
  (let [state {:player-turn {:player "alice"} :round 0 :winner nil}
        room {:created-by "alice"
              :bots #{"alice"}
              :invocation {:players ["host" "alice"]}
              :game {:state state :turn-order ["host" "alice"]}
              :history [state] :channels #{:channel}}
        forged {:game (assoc state :winner "alice") :complete true}]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [persist/update-state! (fn [& _] (throw (ex-info "Unexpected write" {})))
                  persist/complete-game! (fn [& _] (throw (ex-info "Unexpected completion" {})))
                  ws/send-channels! (fn [& _] (throw (ex-info "Unexpected broadcast" {})))
                  ws/send! (fn [& _])]
      (ws/update-game-state nil "alice" "table" :channel forged)
      (is (= room (get-in @ws/games [:games "table"]))))))

(deftest lobby-chat-history-is-visible-only-to-current-members
  (let [lobby {:key "table"
               :created-by "alice"
               :invocation {:players ["alice" "bob"]}
               :chat [{:type "chat" :player "alice" :message "members only"}]
               :readiness {"alice" false "bob" false}}
        sent (atom [])]
    (with-redefs [persist/load-game (constantly nil)
                  persist/find-open-game (constantly lobby)
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/connect! {:db :db :game-key "table" :player game/observer-key} :observer)
      (ws/connect! {:db :db :game-key "table" :player "alice"} :alice)
      (let [create-message (fn [channel]
                             (->> @sent
                                  (filter #(= channel (first %)))
                                  (map second)
                                  (filter #(= "create" (:type %)))
                                  first))]
        (is (= [] (:chat (create-message :observer))))
        (is (= (:chat lobby) (:chat (create-message :alice))))))))

(deftest an-account-colliding-with-a-bot-seat-is-not-a-private-lobby-member
  (let [lobby {:key "private-table"
               :visibility "private"
               :created-by "alice"
               :invocation {:players ["alice" "OBO-A"]}
               :chat [{:type "chat" :player "alice" :message "members only"}]
               :readiness {"alice" false}}
        sent (atom [])]
    (with-redefs [persist/load-game (constantly nil)
                  persist/find-open-game (constantly lobby)
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/connect! {:db :db :game-key "private-table" :player "obo-a"} :collision)
      (let [create-message (->> @sent
                                (map second)
                                (filter #(= "create" (:type %)))
                                first)]
        (is (= [] (:chat create-message)))
        (is (= {} (:readiness create-message)))
        (is (nil? (:created-by create-message)))
        (is (= ["Occupied" "Occupied"]
               (get-in create-message [:invocation :players])))
        (is (nil? (get-in create-message [:invocation :description])))))))

(deftest a-resident-lobby-is-reconciled-when-another-process-publishes-the-game
  (let [lobby {:key "table"
               :created-by "alice"
               :invocation {:players ["alice" "bob"]}
               :game nil
               :channels #{:alice-channel}
               :channel-players {:alice-channel "alice"}}
        active {:key "table"
                :created-by "alice"
                :invocation {:players ["alice" "bob"] :created 1}
                :game {:state {:player-turn {:player "alice"}}}
                :history []
                :chat []}]
    (reset! ws/games {:games {"table" lobby}})
    (with-redefs [persist/load-game (fn [_ _] active)
                  ws/send! (fn [& _])]
      (let [loaded (ws/find-game! :db "table" "bob" :bob-channel)]
        (is (= (:game active) (:game loaded)))
        (is (= #{:alice-channel :bob-channel} (:channels loaded)))
        (is (= {:alice-channel "alice" :bob-channel "bob"}
               (:channel-players loaded)))))))

(deftest an-account-colliding-with-a-bot-cannot-rewind-the-bots-history
  (let [previous {:player-turn {:player "host"} :round 0}
        present {:player-turn {:player "alice"} :round 1}
        room {:created-by "host"
              :bots #{"alice"}
              :invocation {:players ["host" "alice"]}
              :game {:state present}
              :history [previous present]
              :channels #{:channel}}
        sent (atom [])]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [persist/reset-state! (fn [& _] (throw (ex-info "Unexpected rewind" {})))
                  ws/send-channels! (fn [& _] (throw (ex-info "Unexpected broadcast" {})))
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/walk-history nil "alice" "table" :channel {})
      (is (= room (get-in @ws/games [:games "table"])))
      (is (= "error" (get-in @sent [0 1 :type]))))))

(deftest undo-never-hands-the-turn-back-to-the-previous-player
  (let [previous {:player-turn {:player "bob"} :round 0}
        present {:player-turn {:player "alice"} :round 1}
        room {:invocation {:players ["alice" "bob"]}
              :game {:state present}
              :history [previous present]
              :channels #{:alice :bob}
              :channel-players {:alice "alice" :bob "bob"}}
        sent (atom [])]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [persist/reset-state! (fn [& _] (throw (ex-info "Unexpected rewind" {})))
                  persist/update-player-games! (fn [& _] (throw (ex-info "Unexpected player index write" {})))
                  ws/send-channels! (fn [& _] (throw (ex-info "Unexpected broadcast" {})))
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/walk-history nil "alice" "table" :alice {})
      (is (= room (get-in @ws/games [:games "table"])))
      (is (= [[:alice {:type "error"
                       :message "there is nothing to undo in your current turn"}]]
             @sent)))))

(deftest duplicate-undo-stops-at-the-current-turn-boundary
  (let [bob {:player-turn {:player "bob"} :round 0 :stage :bob}
        alice-first {:player-turn {:player "alice"} :round 1 :stage :first}
        alice-second {:player-turn {:player "alice"} :round 1 :stage :second}
        room {:invocation {:players ["alice" "bob"]}
              :game {:state alice-second}
              :history [bob alice-first alice-second]
              :channels #{:alice :bob}
              :channel-players {:alice "alice" :bob "bob"}}
        resets (atom 0)
        sent (atom [])]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [persist/commit-mutation! (fn [& args] (swap! resets inc) (apply memory-commit args))
                  ws/send-channels! (fn [& _])
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/walk-history nil "alice" "table" :alice {})
      (ws/walk-history nil "alice" "table" :alice {})
      (is (= 1 @resets))
      (is (= [bob alice-first]
             (get-in @ws/games [:games "table" :history])))
      (is (= alice-first
             (get-in @ws/games [:games "table" :game :state])))
      (is (= [[:alice {:type "error"
                       :message "there is nothing to undo in your current turn"}]]
             @sent)))))

(deftest an-account-colliding-with-a-bot-cannot-clear-the-bots-turn
  (let [present {:player-turn {:player "alice"} :round 1}
        room {:created-by "host"
              :bots #{"alice"}
              :invocation {:players ["host" "alice"]}
              :game {:state present}
              :history [present]
              :channels #{:channel}}
        sent (atom [])]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [game/beginning-of-turn? (constantly false)
                  persist/update-state! (fn [& _] (throw (ex-info "Unexpected clear" {})))
                  ws/send-channels! (fn [& _] (throw (ex-info "Unexpected broadcast" {})))
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/clear-player-turn nil "alice" "table" :channel {})
      (is (= room (get-in @ws/games [:games "table"])))
      (is (= "error" (get-in @sent [0 1 :type]))))))

(deftest an-account-colliding-with-a-bot-seat-cannot-post-as-the-bot
  (let [sent (atom [])
        lobby {:created-by "alice"
               :invocation {:players ["alice" "OBO-A"]}
               :channels #{:collision}
               :channel-players {:collision "OBO-A"}
               :chat []}]
    (reset! ws/games {:games {"private-table" lobby}})
    (with-redefs [persist/update-chat! (fn [& _]
                                        (throw (ex-info "unexpected chat write" {})))
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/update-chat nil "OBO-A" "private-table" :collision {:message "forged"})
      (is (= [] (get-in @ws/games [:games "private-table" :chat])))
      (is (= "only players in this game can post messages"
             (:message (second (first @sent)))))
      (is (= "chat" (:scope (second (first @sent))))))))

(deftest lobby-chat-broadcasts-only-to-current-members
  (let [sent (atom [])
        lobby {:invocation {:players ["alice"]}
               :channels #{:alice :kicked :observer}
               :channel-players {:alice "alice"
                                 :kicked "bob"
                                 :observer game/observer-key}
               :chat []}]
    (reset! ws/games {:games {"table" lobby}})
    (with-redefs [persist/update-chat! (fn [& _])
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/update-chat nil "alice" "table" :alice {:message "hello"})
      (is (= #{:alice} (set (map first @sent)))))))

(deftest active-game-chat-echoes-the-client-delivery-id
  (let [sent (atom [])
        persisted (atom nil)
        room {:invocation {:players ["alice" "bob"]}
              :game {:state {:player-turn {:player "alice"}}}
              :channels #{:alice :bob}
              :channel-players {:alice "alice" :bob "bob"}
              :chat []}]
    (reset! ws/games {:games {"table" room}})
    (with-redefs [persist/update-chat! (fn [_ _ message] (reset! persisted message))
                  ws/send! (fn [channel message] (swap! sent conj [channel message]))]
      (ws/update-chat nil "alice" "table" :alice
                      {:message "hello" :client-id "draft-123"})
      (is (= "draft-123" (:client-id @persisted)))
      (is (= #{:alice :bob}
             (->> @sent
                  (filter #(= "chat" (:type (second %))))
                  (map first)
                  set)))
      (is (every? #(= "draft-123" (:client-id (second %)))
                  (filter #(= "chat" (:type (second %))) @sent))))))
