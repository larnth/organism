(ns organism.join-test
  "Open-lobby membership and lifecycle guards."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [organism.board :as board]
   [organism.lobby :as lobby]
   [organism.mongo :as db]
   [organism.persist :as persist]
   [organism.routes.websockets :as ws]))

(def test-connection
  {:host "localhost" :port 27017 :database "organism-join-test"})

(def ^:dynamic *db* nil)

(defn- clear!
  [connection]
  (doseq [collection (remove #(.startsWith ^String % "system.")
                             (db/collections connection))]
    (db/delete! connection collection {})))

(use-fixtures
  :each
  (fn [run]
    (let [connection (db/connect! test-connection)]
      (clear! connection)
      (reset! ws/games {:games {}})
      (binding [*db* connection] (run))
      (reset! ws/games {:games {}})
      (clear! connection))))

(defn- invocation-for
  [players]
  {:player-count (count players)
   :ring-count 4
   :organism-victory 3
   :players (vec players)
   :player-captures (vec (repeat (count players) board/default-player-captures))
   :description ""
   :mutations {}
   :game-type "organism"
   :colors (board/generate-colors (take 4 board/total-rings))})

(defn- open-lobby!
  "A lobby opened by alice, with whatever seats are given."
  [game-key players]
  (persist/create-open-game! *db* game-key (invocation-for players) "alice"))

(defn- open-players
  [game-key]
  (get-in (db/one *db* :open-games {:key game-key}) [:invocation :players]))

;; ── the reported bug ──────────────────────────────────────────────────────

(deftest a-claimed-seat-is-written-down
  (testing "joining persists to open-games rather than only the live registry"
    (open-lobby! "three" ["alice" "" ""])
    (let [result (ws/join-open-game! *db* "three" 1 "bob")]
      (is (nil? (:error result)))
      (is (false? (:begun? result))))
    (is (= ["alice" "bob" ""] (vec (open-players "three"))))

    (testing "and survives the registry being emptied, as on a restart"
      (reset! ws/games {:games {}})
      (is (= ["alice" "bob" ""] (vec (open-players "three"))))))

  (testing "a joiner does not become the owner of someone else's lobby"
    (is (= "alice" (:created-by (db/one *db* :open-games {:key "three"}))))))

(deftest joining-is-guarded
  (open-lobby! "three" ["alice" "" ""])
  (testing "a seat somebody is already in"
    (is (= "that seat is taken by alice"
           (:error (ws/join-open-game! *db* "three" 0 "bob")))))
  (testing "a player who is already at the table"
    (is (= "you are already in this game"
           (:error (ws/join-open-game! *db* "three" 1 "alice")))))
  (testing "a seat that does not exist"
    (is (some? (:error (ws/join-open-game! *db* "three" 9 "bob"))))
    (is (some? (:error (ws/join-open-game! *db* "three" nil "bob")))))
  (testing "a lobby that does not exist"
    (is (= "no such open game"
           (:error (ws/join-open-game! *db* "nowhere" 0 "bob")))))
  (testing "none of that changed the roster"
    (is (= ["alice" "" ""] (vec (open-players "three"))))))

(deftest joining-uses-case-insensitive-account-identities
  (persist/set-player-password! *db* "Alice" "hash-a")
  (persist/set-player-password! *db* "Bob" "hash-b")
  (open-lobby! "canonical" ["Alice" "" ""])
  (testing "the same account cannot occupy another seat through different casing"
    (is (= "you are already in this game"
           (:error (ws/join-open-game! *db* "canonical" 1 "aLiCe")))))
  (testing "a mixed-case lookup stores the account's preferred display casing"
    (let [result (ws/join-open-game! *db* "canonical" 1 "bOB")]
      (is (nil? (:error result)))
      (is (= ["Alice" "Bob" ""] (vec (open-players "canonical")))))))

(deftest new-lobbies-use-the-fixed-victory-rule-and-no-mutations
  (persist/set-player-password! *db* "alice" "hash-a")
  (persist/set-player-password! *db* "bob" "hash-b")
  (let [invocation {:players ["alice" "bob"]
                    :player-count 2
                    :ring-count 4
                    :organism-victory 9
                    :mutations {:RAIN {}}}]
    (ws/update-open-game *db* "alice" "fixed-rules" nil
                         {:type "open" :invocation invocation})
    (let [saved (db/one *db* :open-games {:key "fixed-rules"})]
      (is (= 3 (get-in saved [:invocation :organism-victory])))
      (is (= {} (get-in saved [:invocation :mutations]))))))

;; ── explicit, creator-controlled start ───────────────────────────────────

(deftest the-last-seat-waits-for-the-creator-to-start
  (open-lobby! "two" ["alice" ""])
  (let [result (ws/join-open-game! *db* "two" 1 "bob")]
    (is (nil? (:error result)))
    (is (false? (:begun? result))))
  (is (some? (db/one *db* :open-games {:key "two"})) "lobby remains open")
  (is (nil? (db/one *db* :games {:key "two"}))))

(deftest disconnecting-from-an-open-lobby-does-not-create-a-player-game
  (open-lobby! "waiting-room" ["alice" "bob"])
  (ws/ensure-open-game! *db* "waiting-room")
  (swap! ws/games assoc-in [:games "waiting-room" :channels] #{:alice-channel})
  (swap! ws/games assoc-in [:games "waiting-room" :channel-players]
         {:alice-channel "alice"})
  (ws/disconnect! {:db *db* :game-key "waiting-room" :player "alice"}
                  :alice-channel :normal)
  (is (nil? (persist/find-player-game *db* "waiting-room" "alice"))))

(deftest lobby-witness-placeholders-do-not-block-game-start
  (open-lobby! "reconnected-room" ["alice" "bob"])
  (ws/ensure-open-game! *db* "reconnected-room")
  ;; Older lobby disconnects wrote witness-only player rows before a game
  ;; existed. Launch must adopt those placeholders rather than strand the
  ;; transition after retiring the lobby.
  (doseq [player ["alice" "bob"]]
    (db/insert! *db* (persist/player-games-key player)
                {:game "reconnected-room" :witness 0}))
  (with-redefs [ws/send! (fn [& _])]
    (ws/set-ready! *db* "alice" "reconnected-room" true nil)
    (ws/set-ready! *db* "bob" "reconnected-room" true nil)
    (is (= {:started? true}
           (ws/trigger-creation *db* "alice" "reconnected-room" nil {}))))
  (is (nil? (persist/find-open-game *db* "reconnected-room")))
  (is (some? (persist/load-game *db* "reconnected-room")))
  (is (= "active"
         (:status (persist/find-player-game *db* "reconnected-room" "bob")))))

(deftest only-the-creator-can-start-a-full-lobby
  (open-lobby! "two" ["alice" "bob"])
  (ws/ensure-open-game! *db* "two")
  (with-redefs [ws/send! (fn [& _])]
    (is (= "only the lobby creator can start this game"
           (:error (ws/trigger-creation *db* "bob" "two" nil {}))))
    (ws/set-ready! *db* "alice" "two" true nil)
    (ws/set-ready! *db* "bob" "two" true nil)
    (is (nil? (:error (ws/trigger-creation *db* "alice" "two" nil {})))))
  (is (nil? (db/one *db* :open-games {:key "two"})))
  (let [created-game (db/one *db* :games {:key "two"})]
    (is (= "alice" (:created-by created-game)))))

(deftest creator-can-configure-only-bots-before-start
  (persist/set-player-password! *db* "carol" "hash")
  (open-lobby! "typed" ["alice" ""])
  (let [result (ws/set-slot! *db* "alice" "typed" 1 "carol")]
    (is (= "human players must claim their own seats" (:error result))))
  (is (some? (db/one *db* :open-games {:key "typed"})) "still an open lobby")
  (is (nil? (db/one *db* :games {:key "typed"})))
  (is (= ["alice" ""] (vec (open-players "typed"))))
  (is (nil? (:error (ws/set-slot! *db* "alice" "typed" 1 "OBO-A"))))
  (is (= ["alice" "OBO-A"] (vec (open-players "typed"))))
  (open-lobby! "bot-account-collision" ["alice" ""])
  (persist/set-player-password! *db* "OBO" "hash-bot-collision")
  (is (= "bot names cannot also be player accounts"
         (:error (ws/set-slot! *db* "alice" "bot-account-collision" 1 "OBO"))))
  (open-lobby! "partial" ["alice" ""])
  (is (= "choose a registered player or bot"
         (:error (ws/set-slot! *db* "alice" "partial" 1 "bo")))))

(deftest roster-membership-always-uses-the-authenticated-account
  (persist/set-player-password! *db* "Alice" "hash-a")
  (persist/set-player-password! *db* "Bob" "hash-b")
  (open-lobby! "identity" ["Alice" ""])
  (testing "a seated player cannot rename or seat the same account twice"
    (is (= "your seat always uses your account name"
           (:error (ws/set-slot! *db* "Alice" "identity" 0 "Mallory"))))
    (is (= "that player is already in this game"
           (:error (ws/set-slot! *db* "Alice" "identity" 1 "Alice")))))
  (testing "a joiner can claim an empty seat only as their authenticated account"
    (is (= "you can only join as your account name"
           (:error (ws/set-slot! *db* "Bob" "identity" 1 "Carol"))))
    (is (nil? (:error (ws/set-slot! *db* "bOB" "identity" 1 "bOB"))))
    (is (= ["Alice" "Bob"] (vec (open-players "identity")))))
  (testing "an already seated player cannot change another seat"
    (is (= "you are already in this game"
           (:error (ws/set-slot! *db* "Bob" "identity" 0 "Bob"))))))

(deftest only-the-creator-can-change-lobby-configuration
  (open-lobby! "owned" ["alice" ""])
  (let [original (db/one *db* :open-games {:key "owned"})
        changed (assoc (:invocation original) :ring-count 7)]
    (with-redefs [ws/send! (fn [& _])]
      (is (= "only the lobby creator can edit this game"
             (:error (ws/update-create-game *db* "bob" "owned" nil
                                            {:type "create" :invocation changed}))))
      (is (= "sign in to edit a game"
             (:error (ws/update-open-game *db* "--observer--" "owned" nil
                                          {:type "open-game" :invocation changed})))))
    (is (= 4 (-> (db/one *db* :open-games {:key "owned"})
                 :invocation :ring-count)))))

(deftest the-creator-cannot-rename-their-seat-through-a-full-setup-update
  (persist/set-player-password! *db* "alice" "hash-a")
  (persist/set-player-password! *db* "bob" "hash-b")
  (let [invocation (assoc (invocation-for ["alice" "bob"]) :ring-count 4)]
    (persist/create-open-game! *db* "owned" invocation "alice")
    (with-redefs [ws/send! (fn [& _])]
      (is (= "your seat always uses your account name"
             (:error (ws/update-create-game
                      *db* "alice" "owned" nil
                      {:type "create"
                       :invocation (assoc-in invocation [:players 0] "mallory")})))))
    (is (= ["alice" "bob"]
           (-> (db/one *db* :open-games {:key "owned"}) :invocation :players)))))

(deftest a-new-lobby-always-seats-the-creator-as-their-account
  (persist/set-player-password! *db* "Alice" "hash-a")
  (with-redefs [ws/send! (fn [& _])]
    (is (nil? (:error (ws/update-create-game
                       *db* "alice" "new-owned" nil
                       {:type "create"
                        :invocation (invocation-for ["mallory" ""])}))))
    (is (= ["Alice" ""]
           (-> (db/one *db* :open-games {:key "new-owned"}) :invocation :players)))))

(deftest observers-cannot-create-lobbies
  (with-redefs [ws/send! (fn [& _])]
    (is (= "sign in to edit a game"
           (:error (ws/update-create-game
                    *db* "--observer--" "anonymous-room" nil
                    {:type "create"
                     :invocation (invocation-for ["--observer--" ""])})))))
  (is (nil? (persist/find-open-game *db* "anonymous-room"))))

(deftest lobby-invocations-drop-lifecycle-owned-fields
  (persist/set-player-password! *db* "alice" "hash-a")
  (with-redefs [ws/send! (fn [& _])]
    (ws/update-create-game
     *db* "alice" "canonical-room" nil
     {:type "create"
      :invocation (assoc (invocation-for ["alice" ""])
                         :created 1
                         :game {:forged true}
                         :readiness {"alice" true}
                         :password-hash "forged")}))
  (let [invocation (:invocation (persist/find-open-game *db* "canonical-room"))]
    (is (nil? (:created invocation)))
    (is (nil? (:game invocation)))
    (is (nil? (:readiness invocation)))
    (is (nil? (:password-hash invocation)))))

(deftest a-full-setup-update-cannot-add-a-non-account-player
  (persist/set-player-password! *db* "alice" "hash-a")
  (let [invocation (invocation-for ["alice" ""])]
    (persist/create-open-game! *db* "accounts-only" invocation "alice")
    (with-redefs [ws/send! (fn [& _])]
      (is (= "choose a registered player or bot"
             (:error (ws/update-create-game
                      *db* "alice" "accounts-only" nil
                      {:type "create"
                       :invocation (assoc-in invocation [:players 1] "invented-name")})))))
    (is (= ["alice" ""]
           (-> (db/one *db* :open-games {:key "accounts-only"}) :invocation :players)))))

(deftest incomplete-lobbies-cannot-start
  (open-lobby! "waiting" ["alice" ""])
  (ws/ensure-open-game! *db* "waiting")
  (with-redefs [ws/send! (fn [& _])]
    (is (= "Waiting for 1 player."
           (:error (ws/trigger-creation *db* "alice" "waiting" nil {})))))
  (is (some? (db/one *db* :open-games {:key "waiting"})))
  (is (nil? (db/one *db* :games {:key "waiting"}))))

(deftest humans-must-ready-before-the-owner-can-start
  (open-lobby! "ready-room" ["alice" "bob"])
  (ws/ensure-open-game! *db* "ready-room")
  (with-redefs [ws/send! (fn [& _])]
    (is (= "Waiting for alice and bob to ready up."
           (:error (ws/trigger-creation *db* "alice" "ready-room" nil {}))))
    (is (nil? (:error (ws/set-ready! *db* "alice" "ready-room" true nil))))
    (is (= "Waiting for bob to ready up."
           (:error (ws/trigger-creation *db* "alice" "ready-room" nil {}))))
    (is (nil? (:error (ws/set-ready! *db* "bob" "ready-room" true nil))))
    (is (nil? (:error (ws/trigger-creation *db* "alice" "ready-room" nil {})))))
  (is (some? (db/one *db* :games {:key "ready-room"}))))

(deftest only-the-owner-can-kick-and-kicking-clears-readiness
  (open-lobby! "kick-room" ["alice" "bob"])
  (with-redefs [ws/send! (fn [& _])]
    (ws/set-ready! *db* "bob" "kick-room" true nil)
    (is (= "only the lobby creator can remove players"
           (:error (ws/kick-player! *db* "bob" "kick-room" 0 nil))))
    (is (= "the lobby creator cannot remove their own seat"
           (:error (ws/kick-player! *db* "alice" "kick-room" 0 nil))))
    (is (nil? (:error (ws/kick-player! *db* "alice" "kick-room" 1 nil)))))
  (let [record (db/one *db* :open-games {:key "kick-room"})]
    (is (= ["alice" ""] (get-in record [:invocation :players])))
    (is (false? (lobby/ready? (:readiness record) "alice")))
    (is (nil? (get (:readiness record) :bob)))))

(deftest roster-changes-preserve-unaffected-readiness
  (open-lobby! "roster-ready" ["alice" "" ""])
  (with-redefs [ws/send! (fn [& _])]
    (ws/set-ready! *db* "alice" "roster-ready" true nil)
    (ws/join-open-game! *db* "roster-ready" 1 "bob")
    (let [after-join (:readiness (persist/find-open-game *db* "roster-ready"))]
      (is (true? (lobby/ready? after-join "alice")))
      (is (false? (lobby/ready? after-join "bob"))))
    (ws/kick-player! *db* "alice" "roster-ready" 1 nil)
    (let [after-kick (:readiness (persist/find-open-game *db* "roster-ready"))]
      (is (true? (lobby/ready? after-kick "alice")))
      (is (nil? (get after-kick "bob"))))))

(deftest only-gameplay-setting-edits-reset-readiness
  (persist/set-player-password! *db* "alice" "hash-a")
  (persist/set-player-password! *db* "bob" "hash-b")
  (open-lobby! "settings-room" ["alice" "bob"])
  (with-redefs [ws/send! (fn [& _])]
    (ws/set-ready! *db* "alice" "settings-room" true nil)
    (ws/set-ready! *db* "bob" "settings-room" true nil)
    (let [invocation (:invocation (persist/find-open-game *db* "settings-room"))]
      (is (nil? (:error
                 (ws/update-create-game *db* "alice" "settings-room" nil
                                        {:type "create"
                                         :invocation (assoc invocation :description "New table note")}))))
      (let [readiness (:readiness (persist/find-open-game *db* "settings-room"))]
        (is (lobby/ready? readiness "alice"))
        (is (lobby/ready? readiness "bob")))
      (is (nil? (:error
                 (ws/update-create-game *db* "alice" "settings-room" nil
                                        {:type "create"
                                         :invocation (assoc invocation :ring-count 5)}))))
      (let [readiness (:readiness (persist/find-open-game *db* "settings-room"))]
        (is (false? (lobby/ready? readiness "alice")))
        (is (false? (lobby/ready? readiness "bob")))))))

(deftest settings-updates-cannot-convert-a-bot-seat-into-a-colliding-account
  (persist/set-player-password! *db* "alice" "hash-a")
  (persist/set-player-password! *db* "obo-a" "hash-collision")
  (open-lobby! "bot-settings-room" ["alice" "OBO-A"])
  (let [invocation (:invocation (persist/find-open-game *db* "bot-settings-room"))]
    (with-redefs [ws/send! (fn [& _])]
      (is (nil? (:error
                 (ws/update-create-game *db* "alice" "bot-settings-room" nil
                                        {:type "create"
                                         :invocation (assoc invocation
                                                            :description "updated"
                                                            :players ["alice" "obo-a"])}))))))
  (let [saved (persist/find-open-game *db* "bot-settings-room")
        started (ws/complete-game-state saved)]
    (is (= ["alice" "OBO-A"] (get-in saved [:invocation :players])))
    (is (= #{"OBO-A"} (:bots started)))
    (is (false? (lobby/member? "alice" ["alice" "OBO-A"] "obo-a"
                               #(contains? (:bots started) %))))))

(deftest private-lobbies-require-a-server-validated-password
  (persist/set-player-password! *db* "alice" "hash-a")
  (with-redefs [ws/send! (fn [& _])]
    (let [result (ws/update-create-game
                  *db* "alice" "locked-without-password" nil
                  {:type "create"
                   :invocation (assoc (invocation-for ["alice" ""])
                                      :visibility "private")})]
      (is (= "private lobbies require a password" (:error result)))
      (is (nil? (persist/find-open-game *db* "locked-without-password"))))))

(deftest launched-private-policy-is-live-immediately-and-cannot-be-rewritten
  (persist/set-player-password! *db* "alice" "hash-a")
  (let [invocation (assoc (invocation-for ["alice" ""])
                          :visibility "private"
                          :lobby-password "initial-secret")]
    (with-redefs [ws/send! (fn [& _])]
      (is (nil? (:error (ws/update-create-game
                         *db* "alice" "fixed-private" nil
                         {:type "create" :invocation invocation}))))
      (is (= "private" (get-in @ws/games [:games "fixed-private" :visibility])))
      (let [saved (:invocation (persist/find-open-game *db* "fixed-private"))
            result (ws/update-create-game
                    *db* "alice" "fixed-private" nil
                    {:type "create" :invocation (assoc saved :visibility "open")})]
        (is (= "lobby privacy is fixed after launch" (:error result)))
        (is (= "private" (:visibility (persist/find-open-game *db* "fixed-private"))))))))

(deftest legacy-lobbies-default-to-open-and-first-seat-ownership
  (let [invocation (invocation-for ["alice" "bob"])]
    (persist/set-player-password! *db* "alice" "hash-a")
    (persist/set-player-password! *db* "bob" "hash-b")
    (db/merge! *db* :open-games {:key "legacy-room"}
               {:key "legacy-room" :invocation invocation :readiness {"alice" true "bob" true}})
    (with-redefs [ws/send! (fn [& _])]
      (is (nil? (:error
                 (ws/update-create-game *db* "alice" "legacy-room" nil
                                        {:type "create"
                                         :invocation (assoc invocation :description "still open")}))))
      (is (= "open" (:visibility (persist/find-open-game *db* "legacy-room"))))
      (is (nil? (:error (ws/trigger-creation *db* "alice" "legacy-room" nil {})))))
    (is (= "alice" (:created-by (db/one *db* :games {:key "legacy-room"}))))))

(deftest launch-persists-before-broadcast-and-keeps-lobby-on-failure
  (open-lobby! "fragile-room" ["alice" "bob"])
  (ws/ensure-open-game! *db* "fragile-room")
  (with-redefs [ws/send! (fn [& _])]
    (ws/set-ready! *db* "alice" "fragile-room" true nil)
    (ws/set-ready! *db* "bob" "fragile-room" true nil))
  (let [broadcasts (atom [])]
    (with-redefs [persist/stage-game-transition! (fn [& _] (throw (ex-info "write failed" {})))
                  ws/send-channels! (fn [& args] (swap! broadcasts conj args))
                  ws/send! (fn [& _])]
      (is (= "could not start the game; the lobby is still open"
             (:error (ws/trigger-creation *db* "alice" "fragile-room" nil {})))))
    (is (empty? @broadcasts))
    (is (some? (persist/find-open-game *db* "fragile-room")))
    (is (nil? (get-in @ws/games [:games "fragile-room" :game])))
    (is (nil? (persist/load-game *db* "fragile-room")))))

(deftest lobby-start-claim-is-single-writer
  (open-lobby! "claimed-room" ["alice" "bob"])
  (let [claim (persist/claim-open-game-start! *db* "claimed-room")]
    (is (some? claim))
    (is (nil? (persist/claim-open-game-start! *db* "claimed-room")))
    (persist/release-open-game-start! *db* "claimed-room" (:token claim)))
  (is (some? (persist/claim-open-game-start! *db* "claimed-room"))))

(deftest expired-start-claims-can-be-recovered
  (open-lobby! "expired-start" ["alice" "bob"])
  (db/merge! *db* :open-games {:key "expired-start"}
             {:start-claim {:token "dead-process" :expires-at 0}})
  (is (some? (persist/claim-open-game-start! *db* "expired-start"))))

(deftest only-the-current-start-claim-can-remove-a-lobby
  (open-lobby! "reclaimed-start" ["alice" "bob"])
  (let [first-claim (persist/claim-open-game-start! *db* "reclaimed-start")]
    (db/merge! *db* :open-games {:key "reclaimed-start"}
               {:start-claim {:token "replacement" :expires-at Long/MAX_VALUE}})
    (is (false? (persist/remove-claimed-open-game!
                 *db* "reclaimed-start" (:token first-claim))))
    (is (some? (persist/find-open-game *db* "reclaimed-start")))))

(deftest a-second-creator-cannot-overwrite-an-existing-lobby
  (open-lobby! "owned-once" ["alice" ""])
  (persist/create-open-game! *db* "owned-once" (invocation-for ["bob" "carol"]) "bob")
  (let [saved (persist/find-open-game *db* "owned-once")]
    (is (= "alice" (:created-by saved)))
    (is (= ["alice" ""] (get-in saved [:invocation :players])))))

(deftest lobby-mutation-claims-serialize-writers-and-expire
  (open-lobby! "mutation-room" ["alice" "bob"])
  (let [claim (persist/claim-open-game-mutation! *db* "mutation-room")]
    (is (some? claim))
    (is (nil? (persist/claim-open-game-mutation! *db* "mutation-room")))
    (is (= "this lobby is busy; try again"
           (:error (ws/set-ready! *db* "alice" "mutation-room" true nil))))
    (persist/release-open-game-mutation! *db* "mutation-room" (:token claim)))
  (db/merge! *db* :open-games {:key "mutation-room"}
             {:mutation-claim {:token "dead-process" :expires-at 0}})
  (is (some? (persist/claim-open-game-mutation! *db* "mutation-room"))))

(deftest an-expired-mutation-cannot-recreate-a-retired-lobby
  (open-lobby! "stale-mutation-room" ["alice" "bob"])
  (is (thrown?
       clojure.lang.ExceptionInfo
       (persist/with-open-game-mutation!
        *db* "stale-mutation-room"
        (fn []
          (let [old-token (get-in (db/one *db* :open-games {:key "stale-mutation-room"})
                                  [:mutation-claim :token])]
            (db/merge! *db* :open-games {:key "stale-mutation-room"}
                       {:mutation-claim {:token old-token :expires-at 0}})
            (let [{replacement-token :token}
                  (persist/claim-open-game-mutation! *db* "stale-mutation-room")]
              (db/merge! *db* :open-games {:key "stale-mutation-room"}
                         {:start-claim {:token replacement-token
                                        :expires-at Long/MAX_VALUE}})
              (binding [persist/*open-game-mutation-token* replacement-token]
                (persist/retire-claimed-open-game!
                 *db* "stale-mutation-room" replacement-token))
              (persist/update-open-lobby!
               *db* "stale-mutation-room" {:readiness {"alice" true}})))))))
  (is (nil? (persist/find-open-game *db* "stale-mutation-room")))
  (is (= "retired" (:lifecycle
                     (db/one *db* :open-games {:key "stale-mutation-room"})))))

(deftest rollback-removes-only-the-game-created-by-this-transition
  (let [invocation (invocation-for ["alice" "bob"])]
    (db/insert! *db* :games {:key "winner" :transition-id "winner-token"
                             :invocation invocation})
    (db/insert! *db* (persist/history-key "winner") {:round 1})
    (db/insert! *db* (persist/player-games-key "alice") {:game "winner"})
    (persist/remove-created-game! *db* "winner" ["alice" "bob"] "loser-token")
    (is (= "winner-token" (:transition-id (db/one *db* :games {:key "winner"}))))
    (is (= 1 (db/number *db* (persist/history-key "winner"))))
    (is (= 1 (db/number *db* (persist/player-games-key "alice") {:game "winner"})))))

(defn- transition-state
  [game-key]
  (let [invocation (invocation-for ["alice" "bob"])]
    (-> {:key game-key
         :invocation invocation
         :chat [{:player "alice" :message "launching"}]
         :created-by "alice"
         :game-type "organism"}
        ws/complete-game-state)))

(deftest prepared-transitions-are-invisible-until-the-lobby-is-retired
  (open-lobby! "staged-room" ["alice" "bob"])
  (let [claim (persist/claim-open-game-start! *db* "staged-room")
        token (:token claim)]
    (persist/stage-game-transition! *db* (transition-state "staged-room") token)
    (persist/mark-game-transition-committing! *db* "staged-room" token)
    (is (nil? (persist/load-game *db* "staged-room")))
    (is (some? (persist/find-open-game *db* "staged-room")))
    (is (nil? (db/one *db* :games {:key "staged-room"})))))

(deftest activating-games-are-hidden-from-observers
  (db/insert! *db* :games
              {:key "activating-room"
               :transition-state "activating"
               :invocation {:players ["alice" "bob"]}})
  (is (not-any? #(= "activating-room" (:key %))
                (persist/load-observe-games *db*))))

(deftest player-game-lists-hide-rows-until-the-game-is-published
  (db/insert! *db* :games
              {:key "activating-player-room"
               :transition-state "activating"
               :invocation {:players ["alice" "bob"]}})
  (db/insert! *db* (persist/player-games-key "alice")
              {:game "activating-player-room"
               :game-type "organism"
               :status "active"})
  (is (empty? (get (persist/load-player-games *db* "alice" "organism") "active")))
  (db/merge! *db* :games {:key "activating-player-room"}
             {:transition-state "active"})
  (is (= ["activating-player-room"]
         (mapv :game (get (persist/load-player-games *db* "alice" "organism") "active")))))

(deftest player-statistics-ignore-unpublished-player-and-game-records
  (db/insert! *db* :players {:key "alice" :color "#123456"})
  (db/insert! *db* :games
              {:key "activating-stats-room"
               :created-by "alice"
               :game-type "organism"
               :transition-state "activating"
               :invocation {:players ["alice" "bob"]}})
  (db/insert! *db* (persist/player-games-key "alice")
              {:game "activating-stats-room"
               :game-type "organism"
               :status "active"})
  (is (empty? (persist/load-player-stats *db* "organism")))
  (db/merge! *db* :games {:key "activating-stats-room"}
             {:transition-state "active"})
  (is (= [{:key "alice" :active 1 :complete 0 :wins 0 :created 1}]
         (mapv #(dissoc % :color :last-move-at)
               (persist/load-player-stats *db* "organism")))))

(deftest loading-an-active-game-preserves-authority-metadata
  (let [game-state (assoc (transition-state "persisted-bot-room")
                          :transition-state "active"
                          :bots #{"bob"})]
    (persist/create-game! *db* game-state)
    (let [loaded (persist/load-game *db* "persisted-bot-room")]
      (is (= "alice" (:created-by loaded)))
      (is (= #{"bob"} (:bots loaded)))
      (is (= "organism" (:game-type loaded))))))

(deftest a-committed-transition-recovers-idempotently-after-a-crash
  (open-lobby! "recover-room" ["alice" "bob"])
  (let [claim (persist/claim-open-game-start! *db* "recover-room")
        token (:token claim)]
    (persist/stage-game-transition! *db* (transition-state "recover-room") token)
    (persist/mark-game-transition-committing! *db* "recover-room" token)
    (is (true? (persist/retire-claimed-open-game! *db* "recover-room" token)))
    (is (some? (persist/load-game *db* "recover-room")))
    (is (some? (persist/load-game *db* "recover-room")))
    (is (= token (:transition-id (db/one *db* :games {:key "recover-room"}))))
    (is (= 1 (db/number *db* (persist/history-key "recover-room"))))
    (is (= "active" (:status (persist/find-player-game *db* "recover-room" "alice"))))))

(deftest resident-lobby-channels-follow-a-transition-published-by-another-process
  (let [game-key "cross-process-room"
        published (promise)]
    (open-lobby! game-key ["alice" "bob"])
    (with-redefs [ws/send! (fn [_ message]
                             (when (and (= "initialize" (:type message))
                                        (:game message))
                               (deliver published message)))]
      (ws/connect! {:db *db* :game-key game-key :player "alice"} :alice-channel)
      (let [{:keys [token]} (persist/claim-open-game-start! *db* game-key)]
        (persist/stage-game-transition! *db* (transition-state game-key) token)
        (persist/mark-game-transition-committing! *db* game-key token)
        (persist/retire-claimed-open-game! *db* game-key token)
        (is (not= ::timeout (deref published 3000 ::timeout)))
        (is (some? (get-in @ws/games [:games game-key :game])))
        (is (nil? (persist/find-open-game *db* game-key)))))))

(deftest resident-lobby-membership-follows-a-kick-from-another-process
  (let [game-key "cross-process-kick"
        sent (atom [])]
    (persist/create-open-game! *db* game-key (invocation-for ["alice" "bob"])
                               "alice" {:visibility "private" :password "secret"})
    (persist/update-chat! *db* game-key {:player "alice" :message "members only"})
    (ws/ensure-open-game! *db* game-key)
    (swap! ws/games update-in [:games game-key]
           assoc :channels #{:old-bob}
           :channel-players {:old-bob "bob"})
    ;; This durable write represents a different application process completing
    ;; the owner's kick while this process still has the old roster cached.
    (db/merge! *db* :open-games {:key game-key}
               {:invocation (invocation-for ["alice" ""])
                :readiness {:alice false}})
    (with-redefs [ws/send! (fn [channel message]
                             (swap! sent conj [channel message]))]
      (ws/connect! {:db *db* :game-key game-key :player "bob"} :new-bob)
      (is (= "only players in this game can post messages"
             (:error (ws/update-chat *db* "bob" game-key :new-bob
                                     {:message "forged after kick"}))))
      (let [create-message (->> @sent
                                (filter #(= :new-bob (first %)))
                                (map second)
                                (filter #(= "create" (:type %)))
                                first)]
        (is (= [] (:chat create-message)))
        (is (nil? (:created-by create-message))))
      (is (some (fn [[channel message]]
                  (and (= :old-bob channel)
                       (= "lobby-removed" (:type message))))
                @sent))
      (is (= ["members only"]
             (mapv :message (persist/load-chat *db* game-key)))))))

(deftest stale-transition-cleanup-cannot-delete-a-replacement
  (db/insert! *db* persist/game-transition-collection
              {:game-key "fenced-room" :transition-id "old-token"})
  (db/insert! *db* persist/game-transition-collection
              {:game-key "fenced-room" :transition-id "new-token"})
  (persist/discard-game-transition! *db* "fenced-room" "old-token")
  (is (nil? (persist/find-game-transition *db* "fenced-room" "old-token")))
  (is (= "new-token"
         (:transition-id (persist/find-game-transition *db* "fenced-room" "new-token")))))

(deftest a-superseded-claim-cannot-stage-after-its-replacement
  (open-lobby! "superseded-room" ["alice" "bob"])
  (let [old-token (:token (persist/claim-open-game-start! *db* "superseded-room"))]
    (db/merge! *db* :open-games {:key "superseded-room"}
               {:start-claim {:token "new-token" :expires-at Long/MAX_VALUE}})
    (is (thrown? clojure.lang.ExceptionInfo
                 (persist/stage-game-transition!
                  *db* (transition-state "superseded-room") old-token)))
    (is (nil? (persist/find-game-transition *db* "superseded-room" old-token)))))

(deftest staging-a-replacement-does-not-delete-the-older-token-package
  (let [game-key "handoff-room"
        state (transition-state game-key)]
    (open-lobby! game-key ["alice" "bob"])
    (let [old-claim (persist/claim-open-game-start! *db* game-key)]
      (persist/stage-game-transition! *db* state (:token old-claim))
      (db/merge! *db* :open-games {:key game-key}
                 {:start-claim {:token (:token old-claim) :expires-at 0}})
      (let [new-claim (persist/claim-open-game-start! *db* game-key)]
        (persist/stage-game-transition! *db* state (:token new-claim))
        (is (some? (persist/find-game-transition *db* game-key (:token old-claim))))
        (is (some? (persist/find-game-transition *db* game-key (:token new-claim))))))))

(deftest retirement-is-the-durable-visibility-boundary
  (let [game-key "retired-room"
        state (transition-state game-key)]
    (open-lobby! game-key ["alice" "bob"])
    (let [{:keys [token]} (persist/claim-open-game-start! *db* game-key)]
      (persist/stage-game-transition! *db* state token)
      (is (true? (persist/mark-game-transition-committing! *db* game-key token)))
      (is (true? (persist/retire-claimed-open-game! *db* game-key token)))
      (is (nil? (persist/find-open-game *db* game-key)))
      (is (not-any? #(= game-key (:key %)) (persist/load-open-games *db*)))
      (is (= token (:retired-transition-id
                    (db/one *db* :open-games {:key game-key})))))))

(deftest a-missing-staged-package-cannot-retire-the-lobby
  (let [game-key "missing-stage-room"]
    (open-lobby! game-key ["alice" "bob"])
    (let [{:keys [token]} (persist/claim-open-game-start! *db* game-key)]
      (is (false? (persist/commit-game-transition! *db* game-key token)))
      (is (some? (persist/find-open-game *db* game-key))))))

(deftest concurrent-recovery-materializes-one-initial-history-record
  (let [game-key "concurrent-recovery-room"
        state (transition-state game-key)]
    (open-lobby! game-key ["alice" "bob"])
    (let [{:keys [token]} (persist/claim-open-game-start! *db* game-key)]
      (persist/stage-game-transition! *db* state token)
      (persist/mark-game-transition-committing! *db* game-key token)
      (persist/retire-claimed-open-game! *db* game-key token)
      (let [results (doall (map deref (repeatedly 8 #(future (persist/load-game *db* game-key)))))]
        (is (every? some? results))
        (is (= 1 (db/number *db* (persist/history-key game-key) {})))
        (doseq [player ["alice" "bob"]]
          (is (= 1 (db/number *db* (persist/player-games-key player)
                              {:game game-key}))))))))

(deftest a-lost-start-claim-never-materializes-an-active-game
  (open-lobby! "lost-claim-room" ["alice" "bob"])
  (let [claim (persist/claim-open-game-start! *db* "lost-claim-room")
        token (:token claim)]
    (persist/stage-game-transition! *db* (transition-state "lost-claim-room") token)
    (persist/mark-game-transition-committing! *db* "lost-claim-room" token)
    (db/merge! *db* :open-games {:key "lost-claim-room"}
               {:start-claim {:token "replacement" :expires-at Long/MAX_VALUE}})
    (is (false? (persist/commit-game-transition! *db* "lost-claim-room" token)))
    (is (nil? (db/one *db* :games {:key "lost-claim-room"})))
    (is (some? (persist/find-open-game *db* "lost-claim-room")))))

(deftest an-expired-launch-mutation-cannot-retire-a-newer-lobby
  (let [game-key "expired-launch-mutation"]
    (open-lobby! game-key ["alice" "bob"])
    (let [mutation (:token (persist/claim-open-game-mutation! *db* game-key))]
      (binding [persist/*open-game-mutation-token* mutation]
        (let [start (:token (persist/claim-open-game-start! *db* game-key))]
          (persist/stage-game-transition! *db* (transition-state game-key) start)
          (persist/mark-game-transition-committing! *db* game-key start)
          (db/merge! *db* :open-games {:key game-key}
                     {:mutation-claim {:token "replacement" :expires-at Long/MAX_VALUE}
                      :readiness {"alice" false "bob" false}})
          (is (false? (persist/commit-game-transition! *db* game-key start)))
          (is (= {:alice false :bob false}
                 (:readiness (persist/find-open-game *db* game-key))))
          (is (nil? (db/one *db* :games {:key game-key}))))))))

(deftest loading-a-published-game-finishes-transition-cleanup
  (let [game-key "published-cleanup-room"]
    (open-lobby! game-key ["alice" "bob"])
    (let [mutation (:token (persist/claim-open-game-mutation! *db* game-key))]
      (binding [persist/*open-game-mutation-token* mutation]
        (let [start (:token (persist/claim-open-game-start! *db* game-key))
              delete! db/delete!]
          (persist/stage-game-transition! *db* (transition-state game-key) start)
          (persist/mark-game-transition-committing! *db* game-key start)
          (is (true? (persist/retire-claimed-open-game! *db* game-key start)))
          (is (thrown? Exception
                       (with-redefs [db/delete! (fn [database collection query]
                                                  (if (= persist/game-transition-collection collection)
                                                    (throw (ex-info "simulated cleanup crash" {}))
                                                    (delete! database collection query)))]
                         (persist/materialize-game-transition! *db* game-key start))))
          (is (= "active" (:transition-state (db/one *db* :games {:key game-key}))))
          (is (some? (persist/find-game-transition *db* game-key start)))
          (is (some? (db/one *db* :open-games {:key game-key :lifecycle "retired"})))
          (is (some? (persist/load-game *db* game-key)))
          (is (nil? (persist/find-game-transition *db* game-key start)))
          (is (nil? (db/one *db* :open-games {:key game-key}))))))))

(deftest private-admission-attempts-are-durable
  (persist/create-open-game! *db* "durable-rate" (invocation-for ["alice" ""])
                             "alice" {:visibility "private" :password "correct"})
  (is (= "incorrect lobby password"
         (:error (ws/join-open-game! *db* "durable-rate" 1 "bob" "wrong"))))
  (is (= 1 (db/number *db* :lobby-admission-attempts
                      {:game-key "durable-rate" :player-key "bob"}))))

(deftest settings-updates-do-not-repeat-the-launch-acknowledgement
  (persist/set-player-password! *db* "alice" "hash-a")
  (let [sent (atom [])
        invocation (invocation-for ["alice" ""])]
    (with-redefs [ws/send! (fn [_ message] (swap! sent conj message))]
      (ws/update-create-game *db* "alice" "ack-room" :alice
                             {:type "create" :invocation invocation})
      (ws/update-create-game *db* "alice" "ack-room" :alice
                             {:type "create"
                              :invocation (assoc invocation :description "updated")})
      (is (= 1 (count (filter #(= "lobby-created" (:type %)) @sent))))
      (is (= 1 (count (filter #(= "lobby-updated" (:type %)) @sent)))))))

(deftest settings-broadcasts-contain-only-server-owned-fields
  (persist/set-player-password! *db* "alice" "hash-a")
  (let [broadcasts (atom [])
        invocation (invocation-for ["alice" ""])]
    (with-redefs [ws/send! (fn [& _])
                  ws/send-channels! (fn [_ message] (swap! broadcasts conj message))]
      (ws/update-create-game
       *db* "alice" "narrow-events" :alice
       {:type "create"
        :chat [{:forged true}]
        :readiness {"alice" true}
        :bots #{"forged-bot"}
        :created-by "mallory"
        :invocation invocation}))
    (let [message (first @broadcasts)]
      (is (= #{:type :invocation :visibility} (set (keys message))))
      (is (= "alice" (first (get-in message [:invocation :players])))))))

(deftest generic-seat-edits-cannot-bypass-kick-semantics
  (persist/set-player-password! *db* "alice" "hash-a")
  (persist/set-player-password! *db* "bob" "hash-b")
  (open-lobby! "kick-only" ["alice" "bob"])
  (is (= "use the remove control to open an occupied seat"
         (:error (ws/set-slot! *db* "alice" "kick-only" 1 ""))))
  (is (= ["alice" "bob"] (vec (open-players "kick-only")))))

(deftest newly-seated-members-receive-existing-lobby-chat
  (persist/set-player-password! *db* "alice" "hash-a")
  (persist/set-player-password! *db* "bob" "hash-b")
  (open-lobby! "chat-on-join" ["alice" ""])
  (persist/update-chat! *db* "chat-on-join" {:message "Earlier discussion"})
  (ws/ensure-open-game! *db* "chat-on-join")
  (swap! ws/games update-in [:games "chat-on-join"]
         assoc :channels #{:alice-channel :bob-channel}
         :channel-players {:alice-channel "alice" :bob-channel "bob"})
  (let [sent (atom [])]
    (with-redefs [ws/send! (fn [channel message]
                             (swap! sent conj [channel message]))]
      (let [result (ws/join-open-game! *db* "chat-on-join" 1 "bob")]
        (is (nil? (:error result)))
        (is (= [{:message "Earlier discussion"}] (get-in result [:lobby :chat])))))
    (is (some (fn [[channel message]]
                (and (= :bob-channel channel)
                     (= "create" (:type message))
                     (= [{:message "Earlier discussion"}] (:chat message))))
              @sent))))

(deftest private-lobbies-are-hidden-and-require-their-password
  (persist/create-open-game! *db* "friends" (invocation-for ["alice" ""])
                             "alice" {:visibility "private"
                                      :password "mossy-secret"})
  (is (empty? (persist/load-open-games *db*)))
  (is (= "incorrect lobby password"
         (:error (ws/join-open-game! *db* "friends" 1 "bob" "wrong"))))
  (is (nil? (:error (ws/join-open-game! *db* "friends" 1 "bob" "mossy-secret"))))
  (let [record (db/one *db* :open-games {:key "friends"})]
    (is (nil? (:password record)))
    (is (string? (:password-hash record)))))

(deftest lobby-chat-is-loaded-before-and-carried-into-the-game
  (open-lobby! "chat-room" ["alice" "bob"])
  (ws/ensure-open-game! *db* "chat-room")
  (with-redefs [ws/send! (fn [& _])]
    (is (nil? (:error (ws/update-chat *db* "alice" "chat-room" nil
                                      {:message "Ready when you are"}))))
    (reset! ws/games {:games {}})
    (is (= ["Ready when you are"]
           (mapv :message (:chat (ws/ensure-open-game! *db* "chat-room")))))
    (ws/set-ready! *db* "alice" "chat-room" true nil)
    (ws/set-ready! *db* "bob" "chat-room" true nil)
    (ws/trigger-creation *db* "alice" "chat-room" nil {}))
  (is (= ["Ready when you are"]
         (mapv :message (:chat (persist/load-game *db* "chat-room"))))))

(deftest persisted-chat-preserves-delivery-identity
  (persist/update-chat! *db* "chat-id-room"
                        {:type "chat"
                         :id "chat-delivery-1"
                         :player "alice"
                         :time "12:00"
                         :message "once"})
  (is (= "chat-delivery-1"
         (:id (first (persist/load-chat *db* "chat-id-room"))))))

;; ── the pieces underneath ─────────────────────────────────────────────────

(deftest full-invocation-means-every-seat-taken
  (is (true? (board/full-invocation? (invocation-for ["alice" "bob"]))))
  (is (false? (board/full-invocation? (invocation-for ["alice" ""]))))
  (testing "duplicates are not a full table"
    (is (false? (board/full-invocation? (invocation-for ["alice" "alice"])))))
  (testing "a roster longer than the seats sold is not full either"
    (is (false? (board/full-invocation?
                 (assoc (invocation-for ["alice" "bob"]) :player-count 3))))))

(deftest game-names-keep-their-punctuation
  (testing "the names people actually use stay legal"
    (doseq [name ["Woogachaka's Game" "let's see where this goes!"
                  "2p Testing!" "Poppolopin Jr." "ゲーム"]]
      (is (nil? (board/game-key-problem name)) name)))
  (testing "only what breaks a URL or a collection name is refused"
    (doseq [name ["a/b" "a?b" "a#b" "a%b" "a\\b" "" "  " " padded" "$where"]]
      (is (some? (board/game-key-problem name)) (pr-str name)))))

(deftest a-finished-game-leaves-the-registry
  (testing "the last channel out drops the game, so a reconnect rereads the db"
    (is (= {:games {}}
           (ws/disconnect-game "k" :channel {:games {"k" {:channels [:channel]}}})))
    (is (= {:games {"k" {:channels [:other]}}}
           (ws/disconnect-game "k" :channel
                               {:games {"k" {:channels [:channel :other]}}})))))
