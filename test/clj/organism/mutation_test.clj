(ns organism.mutation-test
  (:require [clojure.test :refer :all]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [muuntaja.core :as m]
            [jsonista.core :as json]
            [org.httpkit.server :as hk]
            [organism.middleware.formats :as formats]
            [organism.api.actions :as actions]
            [organism.api.commands :as commands]
            [organism.api.events :as events]
            [organism.api.projection :as projection]
            [organism.choice :as choice]
            [organism.board :as board]
            [organism.game :as game]
            [organism.examples :as examples]
            [organism.bot-completion-test :as bot-test]
            [organism.leaderboard :as leaderboard]
            [organism.mongo :as mongo]
            [organism.persist :as persist]
            [organism.mutations :as mutations]
            [organism.reap :as reap]
            [organism.resolve-tie :as resolve-tie]
            [organism.scripts.delete-game :as delete-script]
            [organism.scripts.reap-games :as reap-script]
            [organism.scripts.resolve-tie :as tie-script]
            [organism.migrations.apply-extract-mutation :as extract-migration]
            [organism.migrations.fix-extract-mutation :as fix-migration]
            [organism.migrations.player-captures-vector :as captures-migration]
            [organism.migrations.initial-player-games :as indexes-migration]
            [organism.migrations.complete-player-games :as witness-migration]
            [organism.routes.shared :as shared]
            [organism.routes.home :as home]
            [organism.routes.organism :as routes]
            [organism.routes.organism-bot :as bot]
            [organism.routes.websockets :as ws]
            [reitit.ring :as ring]))

(def ^:dynamic *db* nil)
(def initial {:key "mutation-table" :game-type "organism"
              :invocation {:players ["orb" "mass"] :created 1}
              :game examples/two-player-close
              :history [(:state examples/two-player-close)] :chat []})
(use-fixtures :each
  (fn [run]
    (let [db (mongo/connect! {:host "127.0.0.1" :port 27017
                              :database "organism-modernization-backend-test"})
          before @ws/games]
      ;; This database is exclusively the disposable backend test database.
      (mongo/purge! db)
      (persist/create-game! db initial)
      (reset! ws/games {:games {"mutation-table" (assoc initial :channels #{:orb :observer}
                                                       :channel-players {:orb "orb" :observer nil})}})
      (try (binding [*db* db] (run))
           (finally (reset! ws/games before) (mongo/purge! db))))))
(defn body [state id]
  {:actionId (:actionId (first (:actions (actions/action-context (:game state) "orb"))))
   :expectedRevision (commands/current-revision state) :commandId id})
(defn submit [payload]
  (routes/game-command-response *db* {:path-params {:play "mutation-table"}
                                    :session {:player "orb"} :body-params payload}))

(deftest delayed-modern-command-cannot-mutate-a-reused-game-name
  (with-redefs [ws/send! (fn [& _])]
    (let [old (persist/load-game *db* "mutation-table")
          token (:instanceId (projection/project-game old "orb"))
          command (assoc (body old "old-incarnation-command") :expectedInstanceId token)]
      (is (string? token))
      (persist/delete-game! *db* "mutation-table")
      (persist/create-game! *db* initial)
      (is (= 409 (:status (submit command))))
      (is (= "game-replaced" (get-in (submit command) [:body :error])))
      (is (= 0 (commands/current-revision (persist/load-game *db* "mutation-table"))))
      (is (nil? (persist/find-command *db* "mutation-table" "old-incarnation-command"))))))

(deftest deleted-game-command-receipts-do-not-leak-into-a-reused-key
  (with-redefs [ws/send! (fn [& _])]
    (let [payload (body initial "reused-delivery")]
      (is (= 200 (:status (submit payload))))
      (persist/reserve-command! *db* {:game-key "other-table" :command-id "reused-delivery"
                                    :player "orb" :action-id "other" :expected-revision 0})
      (persist/delete-game! *db* "mutation-table")
      (ws/drop-game! "mutation-table")
      (is (nil? (persist/find-command *db* "mutation-table" "reused-delivery")))
      (is (some? (persist/find-command *db* "other-table" "reused-delivery")))
      (persist/create-game! *db* initial)
      (is (= 200 (:status (submit payload))))
      (is (= 1 (commands/current-revision (persist/load-game *db* "mutation-table"))))
      (is (= 2 (count (:history (persist/load-game *db* "mutation-table"))))))))

(deftest unrelated-growth-never-completes-a-pending-command
  (persist/reserve-command! *db* {:game-key "mutation-table" :command-id "ambiguous"
                                :player "orb" :action-id "unaccepted" :expected-revision 0})
  (persist/update-state! *db* "mutation-table" (:state examples/two-player-close))
  (with-redefs [ws/send! (fn [& _])]
    (is (= 409 (:status (submit {:actionId "unaccepted" :commandId "ambiguous"
                                :expectedRevision 0})))))
  (is (= "pending" (:status (persist/find-command *db* "mutation-table" "ambiguous")))))

(deftest failed-legacy-write-does-not-change-live-state-or-publish
  (let [before @ws/games
        result (commands/execute-command initial "orb" (body initial "legacy"))
        sent (atom [])]
    (with-redefs [mongo/find-and-merge! (fn [& _] (throw (ex-info "disk unavailable" {})))
                  ws/send! (fn [_ event] (swap! sent conj event))]
      (try (ws/update-game-state *db* "orb" "mutation-table" :orb
                                {:type "game-state" :game (get-in result [:game :state]) :complete true})
           (catch Exception _))
      (is (= before @ws/games))
      (is (empty? (filter #(#{"game-state" "game.updated"} (:type %)) @sent))))))

(deftest modern-acceptance-publishes-both-protocols-and-retries-once
  (let [sent (atom []) payload (body initial "first")]
    (with-redefs [ws/send! (fn [ch event] (swap! sent conj [ch event]))]
      (is (= 200 (:status (submit payload))))
      (is (= #{"game-state" "game.updated"} (set (map (comp :type second) @sent))))
      (is (= [] (->> @sent (filter #(and (= :observer (first %))
                                         (= "game.updated" (:type (second %)))))
                         first second :projection :legalActions)))
      (is (= 200 (:status (submit payload))))
      (is (= 1 (commands/current-revision (persist/load-game *db* "mutation-table"))))
      (is (= 409 (:status (submit (assoc payload :actionId "different"))))))))

(deftest undo-is-monotonic-durable-and-idempotent
  (with-redefs [ws/send! (fn [& _])]
    (is (= 200 (:status (submit (body initial "first")))))
    (let [undo {:operation "undo" :expectedRevision 1 :commandId "undo-1"}
          response (submit undo)]
      (is (= 200 (:status response)))
      (is (= 2 (get-in response [:body :revision])))
      (is (= (:state examples/two-player-close)
             (get-in (persist/load-game *db* "mutation-table") [:game :state])))
      (is (= 1 (count (:history (persist/load-game *db* "mutation-table")))))
      (is (= 200 (:status (submit undo))))
      (is (= 2 (commands/current-revision (persist/load-game *db* "mutation-table"))))
      (is (= 409 (:status (submit (assoc undo :expectedRevision 2 :commandId "undo-2"))))))))

(deftest committed-command-survives-finalization-failure-and-unrelated-growth
  (let [payload (body initial "accepted")]
    (with-redefs [persist/update-player-games! (fn [& _] (throw (ex-info "index unavailable" {})))
                  ws/send! (fn [& _])]
      (try (submit payload) (catch Exception _)))
    (let [durable (persist/load-game *db* "mutation-table")]
      (is (= 1 (commands/current-revision durable)))
      (with-redefs [ws/send! (fn [& _])]
        (is (= 200 (:status (submit (body durable "second")))))
        (is (= 200 (:status (submit payload))))))
    (is (= 1 (:revision (persist/find-command *db* "mutation-table" "accepted"))))
    (is (= 2 (commands/current-revision (persist/load-game *db* "mutation-table"))))))

(deftest competing-modern-and-legacy-writes-have-one-winner
  (let [payload (body initial "modern")
        candidate (get-in (commands/execute-command initial "orb" payload) [:game :state])
        gate (promise)]
    (with-redefs [ws/send! (fn [& _])]
      (let [modern (future @gate (submit payload))
            legacy (future @gate (ws/update-game-state *db* "orb" "mutation-table" :orb
                                                       {:type "game-state" :game candidate :complete true}))]
        (deliver gate true)
        (is (not= ::timeout (deref modern 5000 ::timeout)))
        (is (not= ::timeout (deref legacy 5000 ::timeout)))
        (is (= 1 (commands/current-revision (persist/load-game *db* "mutation-table"))))))))

(deftest bot-completion-uses-the-durable-version-and-both-client-protocols
  (let [room (assoc initial :key "bot-table" :game (bot-test/winning-position)
                           :invocation {:players ["OBO-A" "human"] :created 1}
                           :history [(:state (bot-test/winning-position))])
        sent (atom []) rated (atom 0)]
    (persist/create-game! *db* room)
    (swap! ws/games assoc-in [:games "bot-table"]
           (assoc room :channels #{:observer} :channel-players {:observer nil}))
    (with-redefs [ws/send! (fn [_ event] (swap! sent conj event))
                  leaderboard/rate-later! (fn [_] (swap! rated inc))]
      (let [task (bot/run-bot-until-human! ws/games "bot-table" #{"human"} 0 nil nil *db*)]
        (is (not= ::timeout (deref task 5000 ::timeout))))
      (let [saved (persist/load-game *db* "bot-table")]
        (is (= "OBO-A" (get-in saved [:game :state :winner])))
        (is (= 1 (:revision saved)))
        (is (= "complete" (get-in saved [:accepted-command :status])))
        (is (= #{"game-state" "game.updated"} (set (map :type @sent))))
        (is (pos? @rated))))))

(deftest chat-retry-is-durable-and-failure-does-not-publish
  (let [send-chat #(ws/update-chat *db* "orb" "mutation-table" :orb %)
        sent (atom [])]
    (with-redefs [ws/send! (fn [_ event] (swap! sent conj event))]
      (let [first-result (send-chat {:message "hello" :client-id "delivery-1"})
            retry-result (send-chat {:message "hello" :client-id "delivery-1"})]
        (is (= first-result retry-result))
        (is (= 1 (count (persist/load-chat *db* "mutation-table")))))
      (is (:error (send-chat {:message "different" :client-id "delivery-1"})))
      (is (= "delivery-1" (:client-id (first (persist/load-chat *db* "mutation-table")))))
      (let [before @ws/games n (count @sent)]
        (with-redefs [persist/update-chat! (fn [& _] (throw (ex-info "chat unavailable" {})))]
          (try (send-chat {:message "lost" :client-id "delivery-2"}) (catch Exception _)))
        (is (= before @ws/games))
        (is (= n (count @sent)))))))

(deftest private-projection-policy-is-identical-without-route-preprocessing
  (let [lobby {:key "private" :visibility "private" :created-by "orb"
               :invocation {:players ["orb" "mass"] :description "private details"}
               :readiness {"orb" true} :chat [{:message "private chat"}]}]
    (is (= ["Occupied" "Occupied"] (get-in (projection/project-game lobby nil) [:invocation "players"])))
    (is (= [] (:chat (projection/project-game lobby nil))))))

(defn api [method path player body]
  (let [response ((ring/ring-handler (ring/router [(routes/modern-api-routes *db*)]))
                  {:request-method method :uri path :headers {"accept" "application/json"}
                   :websocket? (.endsWith path "/events")
                   :session {:player player} :body-params body})]
    (if (instance? java.io.InputStream (:body response))
      (update response :body #(m/decode formats/instance "application/json" %)) response)))

(deftest history-and-cache-contract
  (with-redefs [ws/send! (fn [& _])]
    (submit (body initial "first"))
    (let [snapshot (api :get "/api/v1/organism/games/mutation-table" "orb" nil)
          replay (api :get "/api/v1/organism/games/mutation-table/history/0" "orb" nil)]
      (is (= "private, no-store" (get-in snapshot [:headers "Cache-Control"])))
      (is (= 200 (:status replay)))
      (is (= [] (get-in replay [:body :legalActions])))
      (is (= false (get-in replay [:body :viewer :canAct])))
      (is (= 0 (get-in replay [:body :historyCursor])))
      (is (= 1 (get-in replay [:body :revision]))))
    (submit {:operation "undo" :expectedRevision 1 :commandId "undo"})
    (let [catchup (events/catch-up (persist/load-game *db* "mutation-table") nil 0)]
      (is (= ["snapshot"] (mapv :type (:events catchup)))))))

(deftest member-chat-json-adapter
  (with-redefs [ws/send! (fn [& _])]
    (let [path "/api/v1/organism/games/mutation-table/chat"
          payload {:message "HTTP hello" :clientId "http-1"}
          first-result (api :post path "orb" payload)
          retry-result (api :post path "orb" payload)]
      (is (= 200 (:status first-result)))
      (is (= (:body first-result) (:body retry-result)))
      (is (= "orb" (get-in first-result [:body :message :player])))
      (is (integer? (get-in first-result [:body :message :time])))
      (is (= 403 (:status (api :post path "outsider" payload))))
      (is (= 401 (:status (api :post path nil payload)))))))

(deftest ambiguous-cas-acknowledgement-is-recovered-from-exact-receipt
  (let [write mongo/find-and-merge! payload (body initial "ambiguous-ack")]
    (with-redefs [mongo/find-and-merge! (fn [db collection where what]
                                         (let [result (write db collection where what)]
                                           (if (:canonical what)
                                             (throw (ex-info "ack lost" {})) result)))
                  ws/send! (fn [& _])]
      (is (= 200 (:status (submit payload)))))
    (is (= 1 (:revision (persist/load-game *db* "mutation-table"))))))

(deftest history-materialization-failure-remains-recoverable-after-reconnect
  (let [merge! mongo/merge! payload (body initial "outbox")]
    (with-redefs [mongo/merge! (fn [db collection & args]
                               (if (= collection (persist/history-key "mutation-table"))
                                 (throw (ex-info "history unavailable" {}))
                                 (apply merge! db collection args)))
                  ws/send! (fn [& _])]
      (try (submit payload) (catch Exception _)))
    (is (= 1 (:revision (persist/load-game *db* "mutation-table"))))
    (with-redefs [ws/send! (fn [& _])]
      ;; A resident stale browser must not make a reconnect see pre-commit state.
      (let [loaded (ws/find-game! *db* "mutation-table" "orb" :reconnected)]
        (is (= 1 (:revision loaded)))
        (is (= 2 (count (:history loaded)))))
      (is (= 200 (:status (submit payload)))))
    (is (= 1 (:revision (persist/find-command *db* "mutation-table" "outbox"))))))

(deftest clear-turn-also-persists-before-publishing
  (with-redefs [ws/send! (fn [& _])]
    (submit (body initial "first")))
  (let [before @ws/games sent (atom [])]
    (with-redefs [mongo/find-and-merge! (fn [& _] (throw (ex-info "unavailable" {})))
                  ws/send! (fn [_ event] (swap! sent conj event))]
      (try (ws/clear-player-turn *db* "orb" "mutation-table" :orb {}) (catch Exception _)))
    (is (= before @ws/games))
    (is (empty? (filter #(#{"game-state" "game.updated"} (:type %)) @sent)))))

(deftest nonparticipants-and-turn-boundaries-cannot-mutate
  (with-redefs [ws/send! (fn [& _])]
    (doseq [[actor expected] [[nil 401] ["stranger" 403] ["mass" 403]]]
      (is (= expected (:status (routes/game-command-response
                               *db* {:path-params {:play "mutation-table"}
                                     :session {:player actor} :body-params (body initial "not-yours")})))))
    (is (= 0 (commands/current-revision (persist/load-game *db* "mutation-table"))))))

(deftest canonical-read-never-combines-two-revisions
  (with-redefs [ws/send! (fn [& _])]
    (submit (body initial "first")))
  (let [query mongo/query triggered (atom false)
        accepted (persist/load-game *db* "mutation-table")]
    (with-redefs [mongo/query (fn [db collection where]
                               (when (and (= collection (persist/history-key "mutation-table"))
                                          (compare-and-set! triggered false true))
                                 (with-redefs [ws/send! (fn [& _])]
                                   (submit (body accepted "interleaved"))))
                               (query db collection where))]
      (let [snapshot (persist/load-game *db* "mutation-table")]
        (is (= (:revision accepted) (:revision snapshot)))
        (is (= (:game accepted) (:game snapshot)))
        (is (= (:history accepted) (:history snapshot)))))))

(deftest json-lobby-adapters-preserve-private-admission-and-owner-start
  (doseq [player ["orb" "mass"]]
    (mongo/insert! *db* :players {:key player :identity-key player}))
  (with-redefs [ws/send! (fn [& _])]
    (let [path "/api/v1/organism/lobbies/new-room"
          invocation {:player-count 2 :ring-count 4 :players ["orb" ""]
                      :visibility "private" :lobby-password "test-room-only"
                      :description "private conversation"}
          created (api :post path "orb" {:invocation invocation})]
      (is (= 200 (:status created)))
      (is (= "waiting" (get-in created [:body :status])))
      (is (= "orb" (get-in created [:body :lobby :owner])))
      (is (not (re-find #"test-room-only|password-hash" (pr-str (:body created)))))
      (is (= 401 (:status (api :post (str path "/join") nil {:index 1}))))
      (is (= 403 (:status (api :post (str path "/join") "unregistered"
                                  {:index 1 :password "test-room-only"}))))
      (is (= 409 (:status (api :post (str path "/join") "mass" {:index 1 :password "wrong"}))))
      (is (= 200 (:status (api :post (str path "/join") "mass"
                                  {:index 1 :password "test-room-only"}))))
      (is (= 409 (:status (api :post (str path "/start") "mass" {}))))
      (is (= 409 (:status (api :post (str path "/start") "orb" {}))))
      (doseq [player ["orb" "mass"]]
        (is (= 200 (:status (api :post (str path "/ready") player {:ready true})))))
      (let [chat (api :post "/api/v1/organism/games/new-room/chat" "orb"
                      {:message "before launch" :clientId "lobby-chat"})
            started (api :post (str path "/start") "orb" {})]
        (is (= 200 (:status chat)))
        (is (= 200 (:status started)))
        (is (= "active" (get-in started [:body :status])))
        (is (= "before launch" (get-in started [:body :chat 0 :message])))
        (is (nil? (persist/find-open-game *db* "new-room")))))))

(deftest ordinary-json-stream-reloads-recipient-snapshots
  (let [callbacks (atom nil) sent (atom []) update-seen (promise)]
    (with-redefs [hk/as-channel (fn [_ options] (reset! callbacks options) {:status 101})
                  hk/send! (fn [_ message & _]
                             (when (string? message)
                               (let [event (json/read-value message json/keyword-keys-object-mapper)]
                                 (swap! sent conj event)
                                 (when (= 1 (:revision event)) (deliver update-seen event))))
                             true)
                  ws/send! (fn [& _])]
      (api :get "/api/v1/organism/games/mutation-table/events" nil nil)
      (is (fn? (:on-open @callbacks)))
      (when-let [open (:on-open @callbacks)]
        (try
          (open :json-channel)
          (is (= "snapshot" (:type (first @sent))))
          (submit (body initial "stream-update"))
          (let [event (deref update-seen 5000 ::timeout)]
            (is (not= ::timeout event))
            (is (= [] (get-in event [:projection :legalActions]))))
          (finally ((:on-close @callbacks) :json-channel 1000)))))))

(deftest active-chat-cannot-interleave-a-committed-game-publication
  (let [commit persist/commit-mutation! entered (promise) release (promise)]
    (with-redefs [persist/commit-mutation! (fn [& args]
                                           (let [result (apply commit args)]
                                             (deliver entered true)
                                             (deref release 5000 nil)
                                             result))
                  ws/send! (fn [& _])]
      (let [command (future (submit (body initial "chat-race")))]
        (is (= true (deref entered 5000 ::timeout)))
        (let [chat (future (ws/update-chat *db* "orb" "mutation-table" :orb
                                          {:message "concurrent" :client-id "race-chat"}))]
          (try
            (is (= ::waiting (deref chat 100 ::waiting)))
            (finally (deliver release true)))
          (is (not= ::timeout (deref command 5000 ::timeout)))
          (is (not= ::timeout (deref chat 5000 ::timeout)))
          (is (= "concurrent" (get-in @ws/games [:games "mutation-table" :chat 0 :message])))
          (is (= 1 (count (persist/load-chat *db* "mutation-table")))))))))

(deftest modern-human-handoff-schedules-the-bot-without-an-observer
  (let [[before action]
        (loop [g examples/two-player-close n 100]
          (when (zero? n) (throw (ex-info "no handoff found" {})))
          (let [actor (game/current-player g)
                action (first (:actions (actions/action-context g actor)))
                next-game (:game (first (:matches (actions/resolve-current-action g actor (:actionId action)))))]
            (if (not= actor (game/current-player next-game)) [g action]
                (recur next-game (dec n)))))
        room (assoc initial :key "handoff" :game before :history [(:state before)] :bots #{"mass"})
        scheduled (atom [])]
    (persist/create-game! *db* (dissoc room :history))
    (with-redefs [bot/run-bot-until-human! (fn [_ key humans _ _ _ db]
                                           (swap! scheduled conj [key humans db]))]
      (is (= 200 (:status (routes/game-command-response
                           *db* {:path-params {:play "handoff"} :session {:player "orb"}
                                 :body-params {:actionId (:actionId action) :expectedRevision 0 :commandId "handoff"}})))))
    (is (= [["handoff" #{"orb"} *db*]] @scheduled))))

(deftest direct-mongo-cas-rejects-a-competing-expected-revision
  (with-redefs [ws/send! (fn [& _])]
    (submit (body initial "first")))
  (let [state (persist/load-game *db* "mutation-table")
        next-game (:game (commands/execute-command state "orb" (body state "second")))
        gate (promise)
        attempt (fn [id] @gate (persist/commit-mutation! *db* "mutation-table" state next-game
                                                         {:command-id id :game-key "mutation-table"} nil))
        a (future (attempt "cas-a")) b (future (attempt "cas-b"))]
    (deliver gate true)
    (is (= 1 (count (filter some? [(deref a 5000 ::timeout) (deref b 5000 ::timeout)]))))
    (is (= 2 (:revision (persist/load-game *db* "mutation-table"))))))

(deftest snapshot-reconnect-repairs-an-unfinished-post-commit-hook
  (with-redefs [persist/update-player-games! (fn [& _] (throw (ex-info "index unavailable" {})))
                ws/send! (fn [& _])]
    (try (submit (body initial "recover-hook")) (catch Exception _)))
  (let [updated (atom 0)]
    (with-redefs [persist/update-player-games! (fn [& _] (swap! updated inc))
                  ws/send! (fn [& _])]
      (is (= 200 (:status (api :get "/api/v1/organism/games/mutation-table" "orb" nil))))
      (is (= 1 @updated))
      (api :get "/api/v1/organism/games/mutation-table" "orb" nil)
      (is (= 1 @updated) "successful recovery is not repeated on every poll"))))

(deftest failed-http-write-is-private-json-and-retryable
  (let [payload (body initial "temporary-failure")
        response (with-redefs [mongo/find-and-merge! (fn [& _] (throw (ex-info "database detail" {})))]
                   (try (api :post "/api/v1/organism/games/mutation-table/commands" "orb" payload)
                        (catch Exception _ {:status :uncaught})))]
    (is (= 503 (:status response)))
    (is (= "private, no-store" (get-in response [:headers "Cache-Control"])))
    (is (= {:error "temporarily-unavailable"} (:body response)))
    (is (= 0 (commands/current-revision (persist/load-game *db* "mutation-table"))))
    (with-redefs [ws/send! (fn [& _])]
      (is (= 200 (:status (submit payload)))))))

(deftest modern-snapshot-resumes-a-cold-bot-turn-without-republishing-a-commit
  (let [room (assoc initial :key "cold-bot" :bots #{"orb"})
        scheduled (atom []) sent (atom []) indexes (atom 0)]
    (persist/create-game! *db* (dissoc room :history))
    (swap! ws/games update :games dissoc "cold-bot")
    (is (not (:needs-finalization? (persist/load-game *db* "cold-bot"))))
    (with-redefs [bot/run-bot-until-human! (fn [_ key humans _ _ _ _]
                                           (swap! scheduled conj [key humans]))
                  ws/send! (fn [& event] (swap! sent conj event))
                  persist/update-player-games! (fn [& _] (swap! indexes inc))]
      (is (= 200 (:status (api :get "/api/v1/organism/games/cold-bot" nil nil))))
      (is (= [["cold-bot" #{"mass"}]] @scheduled))
      (is (empty? @sent))
      (is (zero? @indexes)))))

(deftest accepted-head-protects-a-marked-game-before-any-derived-write
  (let [now (persist/now-seconds)]
    (with-redefs [persist/now-seconds (constantly (- now 10))]
      (persist/mark-game-for-deletion! *db* "mutation-table" "mass"))
    (with-redefs [ws/send! (fn [& _])
                  persist/repair-mutation! (fn [& _])
                  persist/update-player-games! (fn [& _] (throw (ex-info "index outage" {})))]
      (try (submit (body initial "marked-acceptance")) (catch Exception _)))
    (let [record (persist/find-game-record *db* "mutation-table")]
      (is (= 1 (get-in record [:canonical :revision])))
      (is (nil? (:deletion record)))
      (is (<= now (or (get-in record [:canonical :accepted-at]) 0)))
      (is (<= now (or (persist/last-activity-at *db* "mutation-table") 0)))
      (is (= 2 (persist/game-history-count *db* "mutation-table"))))
    (is (zero? (:deleted-count (reap/sweep! *db* {:dry-run? true :now (+ now persist/deletion-grace-seconds 20)}))))))

(deftest accepted-move-with-failed-index-finalization-survives-the-real-reaper
  (let [now (persist/now-seconds)]
    (with-redefs [persist/now-seconds (constantly (- now 10))]
      (persist/mark-game-for-deletion! *db* "mutation-table" "mass"))
    (with-redefs [ws/send! (fn [& _])
                  persist/update-player-games! (fn [& _] (throw (ex-info "index outage" {})))]
      (try (submit (body initial "reader-probe")) (catch Exception _)))
    (is (= 1 (:revision (persist/load-game *db* "mutation-table"))))
    (is (zero? (:deleted-count (reap/sweep! *db* {:dry-run? true :now (+ now persist/deletion-grace-seconds 20)}))))))

(defn human-winning-room []
  (let [g (walk/postwalk-replace {"OBO-A" "orb" "human" "mass"} (bot-test/winning-position))]
    (assoc initial :key "human-ending" :game g :history [(:state g)])))

(defn finish-human! []
  (let [room (human-winning-room)]
    (persist/create-game! *db* room)
    (routes/game-command-response *db* {:path-params {:play (:key room)}
                                      :session {:player "orb"} :body-params (body room "human-win")})))

(deftest human-completion-is-rated-from-canonical-head-not-audit-insertion-order
  (with-redefs [ws/send! (fn [& _]) leaderboard/rate-later! leaderboard/rate-all!]
    (is (= 200 (:status (finish-human!)))))
  (let [outcomes (leaderboard/decided-games *db*) ratings (leaderboard/load-ratings *db*)]
    (is (= ["human-ending"] (mapv :key outcomes)))
    (is (= "orb" (:winner (first outcomes))))
    (is (= 1 (get-in ratings ["orb" :games])))
    (is (= 1 (get-in ratings ["orb" :wins])))
    (is (> (get-in ratings ["orb" :elo] 0) 1500)))
  ;; An old audit row appended by repair must not become the current head.
  (mongo/insert! *db* (persist/history-key "human-ending")
                 (assoc (persist/serialize-state (:state (:game initial)))
                        :mutation-revision 0 :history-index 0))
  (is (= "orb" (:winner (first (leaderboard/decided-games *db*))))))

(deftest public-observe-allowlists-presentation-and-reads-unmaterialized-head
  (with-redefs [persist/repair-mutation! (fn [& _])
                persist/complete-game! (fn [& _] (throw (ex-info "completion outage" {})))]
    (try (finish-human!) (catch Exception _)))
  (mongo/merge! *db* :games {:key "human-ending"} {:future-private-field "not presentation"})
  (let [view (first (filter #(= "human-ending" (:key %)) (persist/load-observe-games *db*)))]
    (is (= "orb" (:winner view)))
    (is (number? (:last-move-time view)))
    (is (not-any? #(contains? view %) [:canonical :finalized-revision :future-private-field :game :chat]))))

(deftest completed-unread-is-never-an-active-turn-after-modern-view
  (with-redefs [ws/send! (fn [& _]) leaderboard/rate-later! leaderboard/rate-all!]
    (is (= 200 (:status (finish-human!))))
    (let [before (persist/find-player-game *db* "human-ending" "mass")]
      (is (= "completed" (get-in (api :get "/api/v1/organism/games/human-ending" "mass" nil) [:body :status])))
      (is (= before (persist/find-player-game *db* "human-ending" "mass")) "GET does not acknowledge a result"))
    (let [groups (persist/load-player-games *db* "mass" "organism")
          completed (first (get groups "complete"))
          html (:body (home/organism-home-page *db* {:session {:player "mass"}}))]
      (is (= ["mutation-table"] (mapv :game (get groups "active"))))
      (is (= "human-ending" (:game completed)))
      (is (= "complete" (:status completed)))
      (is (true? (:unread? completed)))
      (doseq [page [#(routes/play-list-page *db* {:session {:player "mass"}})
                    #(routes/player-page *db* {:session {:player "mass"} :path-params {:player "mass"}})]]
        (let [html (:body (page))
              encoded (second (re-find #"var playerGames = ([^\n]+)" html))
              rendered (edn/read-string (json/read-value encoded))]
          (is (= groups rendered) "both game-list routes render the completed/unread grouping")))
      (is (not (str/includes? html "turn waiting"))))))

(deftest logical-history-and-witness-ignore-retained-undo-audit-rows
  (with-redefs [ws/send! (fn [& _])]
    (submit (body initial "before-undo"))
    (submit {:operation "undo" :expectedRevision 1 :commandId "undo-audit"}))
  (is (= 3 (mongo/number *db* (persist/history-key "mutation-table"))))
  (is (= 1 (persist/game-history-count *db* "mutation-table")))
  (persist/store-witness! *db* "mutation-table" "orb")
  (is (= 1 (:witness (persist/find-player-game *db* "mutation-table" "orb"))))
  (is (= 2 (:witness-revision (persist/find-player-game *db* "mutation-table" "orb"))))
  (persist/store-witness! *db* "mutation-table" "outsider")
  (persist/store-witness! *db* "missing-table" "orb")
  (is (nil? (persist/find-player-game *db* "mutation-table" "outsider")))
  (is (nil? (persist/find-player-game *db* "missing-table" "orb"))))

(defn delete-table! []
  (shared/delete-game! {:game-type "organism" :on-delete ws/drop-game!} *db*
                       {:session {:player "orb"} :path-params {:play "mutation-table"}}))

(deftest deletion-waits-for-accepted-completion-and-recovery-before-purge
  (doseq [recovery? [false true]]
    (when recovery?
      ;; Clean a resurrection from the RED schedule before testing recovery.
      (persist/delete-game! *db* "mutation-table")
      (persist/create-game! *db* initial))
    ;; A solo human may delete immediately even after play.
    (mongo/merge! *db* :games {:key "mutation-table"} {:bots ["mass"]})
    (let [complete persist/complete-game! entered (promise) release (promise)
          finish #(mutations/execute! *db* "mutation-table" "orb"
                                      {:commandId "finish" :expectedRevision 0}
                                      (fn [state] {:status :accepted :game (assoc-in (:game state) [:state :winner] "orb")}))]
      (with-redefs [ws/send! (fn [& _]) leaderboard/rate-later! (fn [_])]
        (when recovery?
          (with-redefs [persist/complete-game! (fn [& _] (throw (ex-info "completion outage" {})))]
            (try (finish) (catch Exception _))))
        (with-redefs [persist/complete-game! (fn [& args]
                                             (deliver entered true)
                                             (deref release 5000 nil)
                                             (apply complete args))]
          (let [writer (future (if recovery? (mutations/recover! *db* "mutation-table") (finish)))]
            (is (= true (deref entered 5000 ::timeout)))
            (let [deleting (future (delete-table!))]
              (try (is (= ::waiting (deref deleting 100 ::waiting)))
                   (finally (deliver release true)))
              (is (not= ::timeout (deref writer 5000 ::timeout)))
              (is (= "mutation-table" (get-in (deref deleting 5000 {}) [:body :deleted])))
              (is (nil? (persist/find-game-record *db* "mutation-table")))
              (is (nil? (get-in @ws/games [:games "mutation-table"])))
              (is (zero? (mongo/number *db* (persist/history-key "mutation-table"))))
              (is (nil? (persist/find-player-game *db* "mutation-table" "orb")))
              (is (nil? (persist/find-command *db* "mutation-table" "finish"))))))))))

(deftest delete-eligibility-is-rechecked-after-an-accepted-unmaterialized-move
  (let [commit persist/commit-mutation! entered (promise) release (promise)]
    (with-redefs [ws/send! (fn [& _])
                  persist/repair-mutation! (fn [& _])
                  persist/commit-mutation! (fn [& args]
                                             (let [accepted (apply commit args)]
                                               (deliver entered true)
                                               (deref release 5000 nil)
                                               accepted))]
      (let [writer (future (submit (body initial "eligibility")))]
        (is (= true (deref entered 5000 ::timeout)))
        (let [deleting (future (delete-table!))]
          (try (is (= ::waiting (deref deleting 100 ::waiting)))
               (finally (deliver release true)))
          (is (= 200 (:status (deref writer 5000 {}))))
          (is (= "mutation-table" (get-in (deref deleting 5000 {}) [:body :marked])))
          (is (= 1 (:revision (persist/load-game *db* "mutation-table")))))))))

(deftest legacy-admin-writers-refuse-canonical-games
  (with-redefs [ws/send! (fn [& _])]
    (submit (body initial "adopted")))
  (let [before (persist/load-game *db* "mutation-table")]
    (doseq [write [#(persist/update-state! *db* "mutation-table" (:state (:game initial)))
                   #(persist/reset-state! *db* "mutation-table")
                   #(resolve-tie/resolve-tie! *db* "mutation-table")]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"canonical" (write))))
    (is (= before (persist/load-game *db* "mutation-table")))))

(deftest stale-finalization-cannot-publish-a-removed-or-reused-incarnation
  (with-redefs [ws/send! (fn [& _])]
    (submit (body initial "old-incarnation"))
    (let [old (persist/load-game *db* "mutation-table")]
      (persist/delete-game! *db* "mutation-table")
      (ws/drop-game! "mutation-table")
      (mutations/finalize! *db* "mutation-table" old)
      (persist/complete-game! *db* "mutation-table" (assoc (get-in old [:game :state]) :winner "orb"))
      (is (nil? (persist/find-game-record *db* "mutation-table")))
      (is (nil? (persist/find-player-game *db* "mutation-table" "orb")))
      (is (nil? (get-in @ws/games [:games "mutation-table"])))
      (persist/create-game! *db* initial)
      (mutations/finalize! *db* "mutation-table" old)
      (is (= 0 (commands/current-revision (persist/load-game *db* "mutation-table")))))
      (is (= (get-in initial [:game :state :round])
             (:round (persist/find-player-game *db* "mutation-table" "orb"))))))

(deftest reaper-rechecks-a-stale-candidate-under-the-mutation-lock
  (let [now (persist/now-seconds) classified (promise) release (promise) classify reap/classify]
    (with-redefs [persist/now-seconds (constantly (+ now 1))]
      (persist/mark-game-for-deletion! *db* "mutation-table" "mass"))
    (with-redefs [reap/classify (fn [& args]
                                (let [result (apply classify args)]
                                  (deliver classified true)
                                  (deref release 5000 nil)
                                  result))
                  ws/send! (fn [& _])]
      (let [sweep (future (reap/sweep! *db* {:now (+ now persist/deletion-grace-seconds 20)}))]
        (is (= true (deref classified 5000 ::timeout)))
        (try (is (= 200 (:status (submit (body initial "revived-during-sweep")))))
             (finally (deliver release true)))
        (is (zero? (:deleted-count (deref sweep 5000 {:deleted-count -1}))))
        (is (= 1 (:revision (persist/load-game *db* "mutation-table"))))))))

(deftest destructive-cli-entrypoints-require-quiesced-writers-before-connecting
  (with-redefs [mongo/connect! (fn [& _] (throw (ex-info "connected without safety preflight" {})))]
    (doseq [run [#(delete-script/-main "mutation-table" "--force")
                 #(reap-script/-main)
                 #(tie-script/-main "mutation-table" "--apply")]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"writers-quiesced" (run))))))

(deftest game-key-cannot-be-reused-until-the-purge-finishes
  (let [drop mongo/drop! entered (promise) release (promise)]
    (with-redefs [mongo/drop! (fn [db collection]
                              (when (= collection (persist/history-key "mutation-table"))
                                (deliver entered true)
                                (deref release 5000 nil))
                              (drop db collection))
                  ws/send! (fn [& _])]
      (let [deleting (future (persist/delete-game! *db* "mutation-table"))]
        (is (= true (deref entered 5000 ::timeout)))
        (let [creating (future (persist/create-open-game! *db* "mutation-table"
                                                          {:players ["orb" "mass"]} "orb"))]
          (try (is (= ::waiting (deref creating 100 ::waiting)))
               (finally (deliver release true)))
          (is (not= ::timeout (deref deleting 5000 ::timeout)))
          (is (not= ::timeout (deref creating 5000 ::timeout)))
          (is (some? (persist/find-open-game *db* "mutation-table"))))))))

(deftest stale-pre-adoption-cas-cannot-enter-a-reused-game-key
  (let [old (persist/load-game *db* "mutation-table")]
    (with-redefs [ws/send! (fn [& _])]
      (persist/delete-game! *db* "mutation-table"))
    (persist/create-game! *db* initial)
    (is (nil? (persist/commit-mutation! *db* "mutation-table" old (:game old)
                                       {:command-id "stale-incarnation"} nil)))
    (is (zero? (commands/current-revision (persist/load-game *db* "mutation-table"))))))

(deftest legacy-objectid-completions-remain-rated-and-completed-when-unread
  (persist/update-state! *db* "mutation-table" (assoc (get-in initial [:game :state]) :winner "orb"))
  (persist/complete-game! *db* "mutation-table" (assoc (get-in initial [:game :state]) :winner "orb"))
  (let [outcome (first (leaderboard/decided-games *db*))
        groups (persist/load-player-games *db* "mass" "organism")]
    (is (= "orb" (:winner outcome)))
    (is (number? (:finished-at outcome)))
    (is (= (:finished-at outcome) (persist/last-activity-at *db* "mutation-table")))
    (is (empty? (get groups "active")))
    (is (= "complete" (:status (first (get groups "complete")))))
    (is (true? (:unread? (first (get groups "complete")))))))

(deftest legacy-migrations-preflight-the-whole-database-before-writing
  (with-redefs [ws/send! (fn [& _])]
    (submit (body initial "migration-guard")))
  (let [before (persist/find-game-record *db* "mutation-table")]
    (doseq [run [extract-migration/migrate! fix-migration/migrate! captures-migration/migrate!
                 indexes-migration/purge-player-games! witness-migration/migrate!]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"canonical" (run *db*))))
    (is (= before (persist/find-game-record *db* "mutation-table")))
    (is (some? (persist/find-player-game *db* "mutation-table" "orb")))))

(deftest deleting-a-game-retires-bot-ownership-before-key-reuse
  ;; Ownership must start on an actual durable bot turn, not a caller's empty
  ;; human set. The replacement below deliberately restores a human turn.
  (mongo/merge! *db* :games {:key "mutation-table"} {:bots ["orb"]})
  (let [entered (promise) release (promise)
        owner (var-get #'bot/running-games)
        before @owner]
    (try
      (with-redefs [bot/make-agent-step+key (fn [_]
                                            (deliver entered true)
                                            (deref release 5000 nil)
                                            (fn [_] (throw (ex-info "retired bot must not act" {}))))
                    ws/send! (fn [& _])]
        (let [task (bot/run-bot-until-human! ws/games "mutation-table" #{} 0 nil nil *db*)]
          (is (= true (deref entered 5000 ::timeout)))
          (persist/delete-game! *db* "mutation-table")
          (is (not (contains? @owner "mutation-table")))
          (persist/create-game! *db* initial)
          (deliver release true)
          (is (not= ::timeout (deref task 5000 ::timeout)))
          (is (zero? (commands/current-revision (persist/load-game *db* "mutation-table"))))))
      (finally (deliver release true) (reset! owner before)))))

(deftest deleted-during-a-cold-load-cannot-be-republished
  (let [load ws/load-game entered (promise) release (promise)]
    (swap! ws/games update :games dissoc "mutation-table")
    (with-redefs [ws/load-game (fn [& args]
                                (let [state (apply load args)]
                                  (deliver entered true)
                                  (deref release 5000 nil)
                                  state))
                  ws/send! (fn [& _])]
      (let [loading (future (ws/find-game! *db* "mutation-table" "orb" :cold))]
        (is (= true (deref entered 5000 ::timeout)))
        (let [deleting (future (delete-table!))]
          (try (is (= ::waiting (deref deleting 100 ::waiting)))
               (finally (deliver release true)))
          (is (not= ::timeout (deref loading 5000 ::timeout)))
          (is (= "mutation-table" (get-in (deref deleting 5000 {}) [:body :deleted])))
          (is (nil? (get-in @ws/games [:games "mutation-table"]))))))))

(deftest delayed-bot-entry-cannot-acquire-a-replacement-human-turn
  (doseq [all-bots? [false true]]
    (let [entered (promise) release (promise)
          run (var-get #'bot/run-durable-bots!)
          steps (atom 0)]
      (mongo/merge! *db* :games {:key "mutation-table"}
                    {:bots ["orb"] :invocation {:players ["orb" "old-human"] :created 1}})
      (with-redefs-fn
        {#'bot/run-durable-bots! (fn [& args]
                                 ;; Before the durable runner acquires ownership.
                                 (deliver entered true)
                                 (deref release 5000 nil)
                                 (apply run args))
         #'bot/make-agent-step+key
         (fn [_] (fn [g] (swap! steps inc)
                   [:done (assoc-in g [:state :winner] "orb")]))
         #'ws/send! (fn [& _]) #'leaderboard/rate-later! (fn [_])}
        (fn []
          (let [scheduling (future
                             (if all-bots?
                               (bot/run-bot-turns! ws/games "mutation-table" 0 nil nil *db*)
                               (bot/run-bot-until-human! ws/games "mutation-table" #{"old-human"} 0 nil nil *db*)))]
            (is (= true (deref entered 5000 ::timeout)))
            (persist/delete-game! *db* "mutation-table")
            (persist/create-game! *db* initial)
            (let [before (persist/load-game *db* "mutation-table")]
              (deliver release true)
              (let [task (deref scheduling 5000 ::timeout)]
                (is (not= ::timeout task))
                (when (future? task) (is (not= ::timeout (deref task 5000 ::timeout)))))
              (is (zero? @steps))
              (is (= before (persist/load-game *db* "mutation-table")))
              (is (zero? (mongo/number *db* persist/command-collection {:game-key "mutation-table"}))))))))))

(deftest legacy-scheduling-decision-holds-lifecycle-through-ownership-entry
  (mongo/merge! *db* :games {:key "mutation-table"} {:bots ["orb"]})
  (let [entered (promise) release (promise) run bot/run-bot-until-human!
        scheduling-lock? (atom false)]
    (with-redefs [bot/run-bot-until-human!
                  (fn [& args]
                    (reset! scheduling-lock? (Thread/holdsLock (mutations/game-lock "mutation-table")))
                    (deliver entered true)
                    (deref release 5000 nil)
                    (apply run args))
                  bot/make-agent-step+key
                  (fn [_] (fn [g] [:done (assoc-in g [:state :winner] "orb")]))
                  ws/send! (fn [& _]) leaderboard/rate-later! (fn [_])]
      (let [scheduling (future (#'ws/maybe-run-bot-turns! *db* "mutation-table"))]
        (is (= true (deref entered 5000 ::timeout)))
        (is @scheduling-lock?)
        (let [replacing (future (persist/delete-game! *db* "mutation-table")
                                (persist/create-game! *db* initial))]
          (try (is (= ::waiting (deref replacing 100 ::waiting)))
               (finally (deliver release true)))
          (let [runner (deref scheduling 5000 ::timeout)]
            (is (not= ::timeout runner))
            (is (not= ::timeout (deref replacing 5000 ::timeout)))
            (when (future? runner) (is (not= ::timeout (deref runner 5000 ::timeout)))))
          (is (zero? (commands/current-revision (persist/load-game *db* "mutation-table"))))
          (is (nil? (:accepted-command (persist/load-game *db* "mutation-table")))))))))

(deftest runner-incarnation-fence-does-not-depend-on-token-retirement
  (mongo/merge! *db* :games {:key "mutation-table"} {:bots ["orb"]})
  (let [entered (promise) release (promise) steps (atom 0)]
    (with-redefs [bot/make-agent-step+key
                  (fn [_] (deliver entered true) (deref release 5000 nil)
                    (fn [g] (swap! steps inc) [:done (assoc-in g [:state :winner] "orb")]))
                  ws/send! (fn [& _]) leaderboard/rate-later! (fn [_])]
      (let [task (bot/run-bot-until-human! ws/games "mutation-table" #{} 0 nil nil *db*)]
        (is (= true (deref entered 5000 ::timeout)))
        ;; Isolate physical incarnation fencing: retain the token deliberately.
        (with-redefs [bot/stop-game! (fn [_])]
          (persist/delete-game! *db* "mutation-table"))
        (persist/create-game! *db* (assoc initial :bots #{"orb"}))
        (deliver release true)
        (is (not= ::timeout (deref task 5000 ::timeout)))
        (is (zero? @steps))
        (is (zero? (commands/current-revision (persist/load-game *db* "mutation-table")))))
      (is (not (contains? @(var-get #'bot/running-games) "mutation-table"))))))

(deftest generation-persistence-publication-and-scheduling-are-one-lifetime
  (let [create persist/create-game! entered (promise) release (promise)
        scheduled (atom []) key "generated-cycle2"]
    (with-redefs-fn
      {#'routes/generate-game-name (constantly key)
       #'persist/create-game! (fn [db state]
                               (let [result (create db state)]
                                 (when (= key (:key state))
                                   (deliver entered true)
                                   (deref release 5000 nil))
                                 result))
       #'bot/run-bot-turns! (fn [& _]
                             (swap! scheduled conj
                                    (get-in @ws/games [:games key :bots])))
       #'ws/send! (fn [& _])}
      (fn []
        (let [generating (future (routes/generate-game! *db* {}))]
          (is (= true (deref entered 5000 ::timeout)))
          (let [replacing (future
                            (persist/delete-game! *db* key)
                            ;; Bypass only the test barrier, not persistence locks.
                            (create *db* (assoc initial :key key))
                            (ws/find-game! *db* key "orb" nil))]
            (try (is (= ::waiting (deref replacing 150 ::waiting)))
                 (finally (deliver release true)))
            (is (not= ::timeout (deref generating 5000 ::timeout)))
            (is (not= ::timeout (deref replacing 5000 ::timeout)))
            (is (= (get-in initial [:invocation :players])
                   (get-in @ws/games [:games key :invocation :players])))
            (is (= (get-in initial [:game :state])
                   (get-in (persist/load-game *db* key) [:game :state])))
            (is (= 1 (count @scheduled)))))))))

(defn cycle2-lobby! [key players]
  (persist/create-open-game!
   *db* key {:players players :player-count (count players) :ring-count 4
             :player-captures (vec (repeat (count players) board/default-player-captures))
             :colors (vec (board/generate-colors (take 4 board/total-rings)))
             :organism-victory 3 :mutations {} :game-type "organism"} "orb"))

(defn cycle2-request [key payload]
  {:path-params {:play key} :session {:player "orb"} :body-params payload})

(defn cycle2-instance [key]
  (get-in (routes/game-projection-response *db* (cycle2-request key nil)) [:body :instanceId]))

(deftest create-only-intent-cannot-reconfigure-an-existing-table
  (persist/set-player-password! *db* "orb" "test-only-hash")
  (cycle2-lobby! "create-only-existing" ["orb" "carol"])
  (with-redefs [ws/send! (fn [& _])]
    (doseq [key ["create-only-existing" "mutation-table"]]
      (let [before (persist/find-game-record *db* key)
            response (routes/lobby-command-response
                      *db* :configure (cycle2-request key
                        {:createOnly true :invocation {:player-count 2 :ring-count 5
                                                      :players ["orb" ""] :description "Must not replace settings"}}))]
        (is (= 409 (:status response)))
        (is (= "game-already-exists" (get-in response [:body :error])))
        (is (= before (persist/find-game-record *db* key)))))))

(deftest simultaneous-create-only-requests-create-just-once
  (persist/set-player-password! *db* "orb" "test-only-hash")
  (with-redefs [ws/send! (fn [& _])]
    (let [start (promise)
          request (cycle2-request "create-once" {:createOnly true
                     :invocation {:player-count 2 :ring-count 4 :players ["orb" ""]}})
          calls (doall (repeatedly 2 #(future @start (routes/lobby-command-response *db* :configure request))))]
      (deliver start true)
      (is (= [200 409] (sort (map #(get (deref % 5000 {}) :status) calls))))
      (is (string? (cycle2-instance "create-once")))))
  (doseq [invalid [nil "true" 1 {}]]
    (is (= 400 (:status (routes/lobby-command-response
                        *db* :configure (cycle2-request "invalid-create-only"
                          {:createOnly invalid :invocation {:player-count 2 :ring-count 4 :players ["orb" ""]}})))))))

(deftest waiting-identity-is-nonnull-stable-opaque-and-replaced-with-the-table
  (with-redefs [ws/send! (fn [& _])]
    (cycle2-lobby! "identity-room" ["orb" "mass"])
    (let [token (cycle2-instance "identity-room")
          raw (mongo/one *db* :open-games {:key "identity-room"})]
      (is (string? token))
      (is (not= (str (:_id raw)) token))
      (is (= token (cycle2-instance "identity-room")))
      (persist/delete-game! *db* "identity-room")
      (cycle2-lobby! "identity-room" ["orb" "carol"])
      (is (not= token (cycle2-instance "identity-room"))))))

(deftest delayed-chat-ready-and-kick-cannot-cross-waiting-replacement
  (persist/set-player-password! *db* "orb" "test-only-hash")
  (with-redefs [ws/send! (fn [& _])]
    (doseq [operation [:chat :ready :kick :configure :join :seat :start]]
      (let [key (str "delayed-" (name operation))]
        (cycle2-lobby! key ["orb" "mass"])
        (mongo/merge! *db* :open-games {:key key} {:visibility "private"})
        (let [token (cycle2-instance key)
              entered (promise) release (promise)
              payload {:expectedInstanceId token :message "old private conversation"
                       :clientId "delayed-delivery" :ready true :index 1 :player ""
                       :invocation {:player-count 2 :ring-count 5 :players ["orb" ""]}}
              delayed (future (deliver entered true) @release
                        (if (= :chat operation)
                          (routes/game-chat-response *db* (cycle2-request key payload))
                          (routes/lobby-command-response *db* operation (cycle2-request key payload))))]
          (is (= true (deref entered 5000 ::timeout)))
          (persist/delete-game! *db* key)
          (cycle2-lobby! key ["orb" "carol"])
          (mongo/merge! *db* :open-games {:key key} {:visibility "private"})
          (let [before (persist/find-open-game *db* key)]
            (deliver release true)
            (let [response (deref delayed 5000 {})]
              (is (= 409 (:status response)) (name operation))
              (is (= "game-replaced" (get-in response [:body :error])) (name operation)))
            (is (= before (persist/find-open-game *db* key)))
            (is (empty? (persist/load-chat *db* key)))
            (is (nil? (persist/load-game *db* key)))))))))

(deftest expected-table-identity-is-optional-but-when-present-must-be-valid
  (persist/set-player-password! *db* "orb" "test-only-hash")
  (cycle2-lobby! "fenced-room" ["orb" "mass"])
  (with-redefs [ws/send! (fn [& _])]
    (doseq [invalid [nil "" "   " 42 (apply str (repeat 201 "x"))]]
      (doseq [operation [:chat :ready]]
        (let [request (cycle2-request "fenced-room"
                                     {:expectedInstanceId invalid :message "no write"
                                      :clientId "invalid" :ready true})
              result (if (= :chat operation) (routes/game-chat-response *db* request)
                         (routes/lobby-command-response *db* operation request))]
          (is (= 400 (:status result)))
          (is (= "expected-instance-id-invalid" (get-in result [:body :error]))))))
    (is (empty? (persist/load-chat *db* "fenced-room")))
    (is (= {} (:readiness (persist/find-open-game *db* "fenced-room"))))
    (is (= 200 (:status (routes/lobby-command-response
                        *db* :ready (cycle2-request "fenced-room" {:ready true})))))))

(deftest waiting-identity-survives-launch-rollback-and-materialization-recovery
  (persist/set-player-password! *db* "orb" "test-only-hash")
  (with-redefs [ws/send! (fn [& _])]
    (doseq [legacy? [false true] failure [:none :before-retirement :after-retirement]]
      (let [key (str "continuity-" legacy? "-" (name failure))]
        (cycle2-lobby! key ["orb" "mass"])
        (when legacy?
          (mongo/upsert! *db* :open-games {:key key} {:$unset {:table-id ""}}))
        (let [token (cycle2-instance key)
              waiting-id (:_id (mongo/one *db* :open-games {:key key}))]
          (is (string? token))
          (ws/set-ready! *db* "orb" key true nil)
          (ws/set-ready! *db* "mass" key true nil)
          (case failure
            :before-retirement
            (with-redefs [persist/mark-game-transition-committing! (fn [& _] false)]
              (is (:error (ws/trigger-creation *db* "orb" key nil {}))))
            :after-retirement
            (with-redefs [persist/materialize-game-transition!
                          (fn [& _] (throw (ex-info "test publication outage" {})))]
              (is (:error (ws/trigger-creation *db* "orb" key nil {}))))
            nil)
          (when (not= :after-retirement failure)
            (is (= token (cycle2-instance key)))
            (is (= 200 (:status (routes/lobby-command-response
                                *db* :start (cycle2-request key {:expectedInstanceId token}))))))
          (let [active (persist/load-game *db* key)]
            (is (some? (:game active)))
            (is (= token (commands/instance-id active)))
            (is (not= waiting-id (:incarnation-id active)))
            (is (= token (cycle2-instance key)))
            (is (= 200 (:status (routes/game-chat-response
                                *db* (cycle2-request key {:expectedInstanceId token
                                                         :message "survives launch" :clientId "launch-chat"})))))))))))

(deftest table-fence-remains-locked-through-chat-and-every-lobby-service
  (persist/set-player-password! *db* "orb" "test-only-hash")
  (with-redefs [ws/send! (fn [& _])]
    (doseq [[operation service]
            [[:chat #'ws/update-chat] [:ready #'ws/set-ready!] [:kick #'ws/kick-player!]
             [:configure #'ws/update-create-game] [:join #'ws/join-open-game!]
             [:seat #'ws/set-slot!] [:start #'ws/trigger-creation]]]
      (let [key (str "locked-" (name operation)) entered (promise) release (promise)
            held? (atom false) write (var-get service)]
        (cycle2-lobby! key ["orb" "mass"])
        (let [payload {:expectedInstanceId (cycle2-instance key) :ready true :index 1
                       :message "old table only" :clientId "before-deletion" :player ""
                       :invocation {:players ["orb" "mass"] :player-count 2 :ring-count 4}}]
          (with-redefs-fn
            {service (fn [& args]
                       (reset! held? (Thread/holdsLock (mutations/game-lock key)))
                       (deliver entered true)
                       (deref release 5000 nil)
                       (apply write args))}
            (fn []
              (let [writing (future
                              (if (= :chat operation)
                                (routes/game-chat-response *db* (cycle2-request key payload))
                                (routes/lobby-command-response *db* operation (cycle2-request key payload))))]
                (is (= true (deref entered 5000 ::timeout)))
                (is @held? (name operation))
                (let [replacing (future (persist/delete-game! *db* key)
                                        (cycle2-lobby! key ["orb" "carol"]))]
                  (try (is (= ::waiting (deref replacing 100 ::waiting)))
                       (finally (deliver release true)))
                  (is (not= ::timeout (deref writing 5000 ::timeout)))
                  (is (not= ::timeout (deref replacing 5000 ::timeout)))
                  (is (= ["orb" "carol"] (get-in (persist/find-open-game *db* key) [:invocation :players])))
                  (is (empty? (persist/load-chat *db* key))))))))))))

(deftest active-chat-and-command-validation-share-the-table-fence
  (with-redefs [ws/send! (fn [& _])]
    (let [token (cycle2-instance "mutation-table")
          old-chat {:expectedInstanceId token :message "private to old participants" :clientId "old-active"}]
      (persist/delete-game! *db* "mutation-table")
      (persist/create-game! *db* initial)
      (let [response (routes/game-chat-response *db* (cycle2-request "mutation-table" old-chat))]
        (is (= 409 (:status response)))
        (is (= "game-replaced" (get-in response [:body :error]))))
      (is (empty? (persist/load-chat *db* "mutation-table")))))
  (doseq [invalid [nil "" " " 42 (apply str (repeat 201 "x"))]]
    (is (= 400 (:status (submit (assoc (body initial "invalid-instance") :expectedInstanceId invalid))))))
  (is (zero? (commands/current-revision (persist/load-game *db* "mutation-table")))))

(deftest partially-materialized-historical-launch-preserves-waiting-identity
  (let [key "historical-materialization" merge! mongo/find-and-merge!]
    (cycle2-lobby! key ["orb" "mass"])
    (mongo/upsert! *db* :open-games {:key key} {:$unset {:table-id ""}})
    (let [{:keys [token]} (persist/claim-open-game-start! *db* key)
          expected (cycle2-instance key)]
      (persist/stage-game-transition!
       *db* (ws/complete-game-state (persist/find-open-game *db* key)) token)
      ;; Emulate an old package, whose already-inserted activating record also
      ;; predates :table-id, and a crash immediately before active publication.
      (let [package (persist/find-game-transition *db* key token)]
        (mongo/merge! *db* persist/game-transition-collection {:game-key key :transition-id token}
                      {:game-state-edn (pr-str (dissoc (edn/read-string (:game-state-edn package)) :table-id))}))
      (persist/mark-game-transition-committing! *db* key token)
      (persist/retire-claimed-open-game! *db* key token)
      (with-redefs [mongo/find-and-merge!
                    (fn [db collection query changes]
                      (if (and (= :games collection) (= "active" (:transition-state changes)))
                        (throw (ex-info "before active publication" {}))
                        (merge! db collection query changes)))]
        (is (thrown? clojure.lang.ExceptionInfo (persist/load-game *db* key))))
      (mongo/upsert! *db* :games {:key key} {:$unset {:table-id ""}})
      (is (string? expected))
      (is (= expected (commands/instance-id (persist/load-game *db* key))))
      (is (= expected (cycle2-instance key))))))
