(ns organism.bot-completion-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [organism.choice :as choice]
   [organism.game :as game]
   [organism.leaderboard :as leaderboard]
   [organism.persist :as persist]
   [organism.mutations :as mutations]
   [organism.persist-journey-bots :as bots-db]
   [organism.routes.organism-bot :as bot]
   [organism.routes.websockets :as ws]))

(defn winning-position
  ([] (winning-position {"OBO-A" 2 "human" 0}))
  ([captures]
   {:turn-order ["OBO-A" "human"] :organism-victory 3 :mutations {}
    :players {"OBO-A" {:capture-limit 2} "human" {:capture-limit 2}}
    :state {:round 1 :elements {} :food {}
            :captures (into {} (map (fn [[p n]] [p (vec (repeat n :eat))]) captures))
            :player-turn {:player "OBO-A" :advance :check-integrity
                          :introduction {} :organism-turns []}}}))

(defn run-completion [initial mixed? db]
  (let [registry (atom {:games {"table" {:game initial :history []}}})
        persisted (atom [])
        broadcasts (atom [])
        broadcast #(swap! broadcasts conj [%1 %2])
        persist #(swap! persisted conj %)
        task (if mixed?
               (bot/run-bot-until-human! registry "table" #{"human"} 0 broadcast persist db)
               (bot/run-bot-turns! registry "table" 0 broadcast persist db))]
    (is (not= ::timeout (deref task 4000 ::timeout)))
    {:final (get-in @registry [:games "table" :game])
     :persisted @persisted :broadcasts @broadcasts}))

(deftest generated-bots-record-and-broadcast-the-engine-winner
  (let [initial (winning-position)
        {:keys [final persisted broadcasts]} (run-completion initial false nil)
        [keys broadcast-game] (last broadcasts)
        replay (reduce (fn [g key] (get (second (choice/find-state g)) key)) initial keys)]
    (is (= "OBO-A" (game/victory? initial)))
    (is (= "OBO-A" (get-in final [:state :winner])))
    (is (= "OBO-A" (:winner (last persisted))))
    (is (= [:advance] keys) "the declaration is a real replayable engine transition")
    (is (= final broadcast-game replay))))

(deftest mixed-bot-games-preserve-the-authoritative-tie-winner
  (let [initial (winning-position {"OBO-A" 2 "human" 2})
        {:keys [final persisted]} (run-completion initial true nil)]
    (is (= "human" (game/victory? initial)))
    (is (= "human" (get-in final [:state :winner])))
    (is (= "human" (:winner (last persisted))))))

(deftest synchronous-bot-play-includes-the-recorded-result-in-history
  (let [[final history] (bot/play-until-human-or-done (winning-position) #{"human"})]
    (is (= "OBO-A" (get-in final [:state :winner])))
    (is (= "OBO-A" (:winner (last history))))))

(deftest a-recorded-winner-has-no-more-gameplay-choices
  (let [finished (game/declare-victory (winning-position) "OBO-A")]
    (is (= [finished :game-over {}] (choice/find-next-choices finished)))))

(deftest completion-updates-the-durable-game-record-and-ratings
  (let [completed (atom [])
        rated (atom 0)
        registry (atom {:games {}})]
    (with-redefs [bots-db/find-bot (fn [& _] nil)
                  ws/games registry
                  persist/load-game (fn [_ key] (get-in @registry [:games key]))
                  persist/complete-game! (fn [db key state]
                                           (swap! completed conj [db key (:winner state)]))
                  leaderboard/rate-later! (fn [_] (swap! rated inc))]
      ;; Completion is now owned by the common hook, not the bot callback.
      ;; The real durable bot -> hook journey is covered in mutation-test.
      (ws/post-commit! :test-db {:key "table"
                                :game (game/declare-victory (winning-position) "OBO-A")}))
    (is (= [[:test-db "table" "OBO-A"]] @completed))
    (is (= 1 @rated))))

(deftest failed-bot-persistence-keeps-the-accepted-registry-unchanged
  (let [initial (winning-position)
        registry (atom {:games {"failure" {:game initial :history [(:state initial)]}}})
        before @registry
        sent (atom [])
        task (bot/run-bot-turns! registry "failure" 0
                                  #(swap! sent conj [%1 %2])
                                  (fn [_] (throw (ex-info "durable write failed" {}))))]
    (is (not= ::timeout (deref task 4000 ::timeout)))
    (is (= before @registry))
    (is (empty? @sent))))

(deftest durable-bot-runner-retains-a-total-computation-budget
  (let [stored (atom {:game (winning-position {"OBO-A" 0 "human" 0})
                      :invocation {:players ["OBO-A" "human"]}
                      :incarnation-id "budget-test-incarnation"})
        steps (atom 0)]
    (with-redefs [persist/load-game (fn [& _] @stored)
                  bot/make-agent-step+key (fn [_]
                                           (fn [g]
                                             (swap! steps inc)
                                             [:advance (update-in g [:state :round] inc)]))
                  mutations/execute! (fn [_ _ _ _ resolver]
                                       (let [result (resolver @stored)]
                                         (swap! stored assoc :game (:game result))
                                         (assoc result :game-state @stored)))]
      (let [task (bot/run-bot-turns! (atom {}) "bounded-acceptance-test" 0 nil nil :test-db)]
        (try
          (is (not= ::timeout (deref task 2000 ::timeout))
              "an endless sequence of legal turns must not run forever")
          (is (<= @steps 20000) "retain the pre-migration total bot-step budget")
          (finally (future-cancel task)))))))
