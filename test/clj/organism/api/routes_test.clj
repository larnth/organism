(ns organism.api.routes-test
  (:require
   [clojure.test :refer :all]
   [clojure.string :as str]
   [muuntaja.core :as m]
   [organism.api.actions :as actions]
   [organism.examples :as examples]
   [organism.game :as game]
   [organism.middleware.formats :as formats]
   [organism.persist :as persist]
   [organism.routes.organism :as routes]
   [reitit.ring :as ring]
   [ring.middleware.params :refer [wrap-params]]
   [ring.mock.request :as mock]))

(def route-game-state
  {:key "pond-life"
   :invocation {:players ["alice" "bob"]}
   :game {:state {:round 1
                  :elements {}
                  :food {}
                  :captures {"alice" [] "bob" []}
                  :player-turn {:player "alice"}}}
   :history [{:round 0} {:round 1}]
   :chat []})

(defn- api-app
  []
  (wrap-params
   (ring/ring-handler
    (ring/router [(routes/modern-api-routes :test-db)]))))

(defn- json-request
  [path player]
  (-> (mock/request :get path)
      (mock/header "accept" "application/json")
      (assoc :session {:player player})))

(defn- decode-json
  [response]
  (m/decode formats/instance "application/json" (:body response)))

(defn- command-request
  [game-id player body]
  (-> (mock/request :post (str "/api/v1/organism/games/" game-id "/commands"))
      (mock/header "accept" "application/json")
      (assoc :session {:player player}
             :body-params body)))

(deftest returns-a-revisioned-game-projection
  (with-redefs [persist/load-game (fn [db game-id]
                                    (is (= :test-db db))
                                    (is (= "pond-life" game-id))
                                    route-game-state)
                persist/find-open-game (constantly nil)
                actions/action-context
                (fn [game actor]
                  (is (= (:game route-game-state) game))
                  (is (= "alice" actor))
                  {:game game
                   :phase :move-from
                   :actions [{:actionId "move-red-0"
                              :kind "move-from"}]})]
    (let [response ((api-app) (json-request "/api/v1/organism/games/pond-life" "alice"))
          body (decode-json response)]
      (is (= 200 (:status response)))
      (is (= "application/json; charset=utf-8"
             (get-in response [:headers "Content-Type"])))
      (is (= "pond-life" (:gameId body)))
      (is (= 1 (:revision body)))
      (is (= true (get-in body [:viewer :canAct])))
      (is (= [{:actionId "move-red-0" :kind "move-from"}]
             (:legalActions body))))))

(deftest returns-catch-up-events-after-a-known-revision
  (with-redefs [persist/load-game (constantly route-game-state)
                persist/find-open-game (constantly nil)
                actions/action-context
                (fn [game actor]
                  {:game game
                   :actions [{:actionId (str "act-" actor)}]})]
    (let [request (-> (json-request "/api/v1/organism/games/pond-life" "alice")
                      (mock/query-string {:afterRevision "0"}))
          response ((api-app) request)
          body (decode-json response)]
      (is (= 200 (:status response)))
      (is (= 1 (:toRevision body)))
      (is (= [1] (mapv :revision (:events body))))
      (is (= ["game.updated"] (mapv :type (:events body)))))))

(deftest rejects-an-invalid-catch-up-revision
  (with-redefs [persist/load-game (constantly route-game-state)
                persist/find-open-game (constantly nil)]
    (let [request (-> (json-request "/api/v1/organism/games/pond-life" "alice")
                      (mock/query-string {:afterRevision "recent"}))
          response ((api-app) request)
          body (decode-json response)]
      (is (= 400 (:status response)))
      (is (= "after-revision-invalid" (:error body))))))

(deftest returns-not-found-for-an-unknown-game
  (with-redefs [persist/load-game (constantly nil)
                persist/find-open-game (constantly nil)]
    (let [response ((api-app) (json-request "/api/v1/organism/games/missing" nil))
          body (decode-json response)]
      (is (= 404 (:status response)))
      (is (= {:error "game-not-found" :gameId "missing"}
             body)))))

(deftest loads-an-open-lobby-when-no-created-game-exists
  (with-redefs [persist/load-game (constantly nil)
                persist/find-open-game (fn [_ game-id]
                                         {:key game-id
                                          :invocation {:players ["alice" nil]}
                                          :game nil
                                          :history []
                                          :chat []})]
    (let [response ((api-app) (json-request "/api/v1/organism/games/waiting-room" "alice"))
          body (decode-json response)]
      (is (= 200 (:status response)))
      (is (= "waiting" (:status body)))
      (is (= false (get-in body [:viewer :canAct]))))))

(deftest private-http-projections-do-not-treat-a-bot-name-as-membership
  (with-redefs [persist/load-game (constantly nil)
                persist/find-open-game
                (fn [_ game-id]
                  {:key game-id
                   :visibility "private"
                   :created-by "alice"
                   :invocation {:players ["alice" "OBO-A"]
                                :player-count 2
                                :ring-count 4
                                :description "member-only details"}
                   :readiness {"alice" true}
                   :chat [{:player "alice" :message "member-only chat"}]})]
    (let [response ((api-app) (json-request "/api/v1/organism/games/private-room" "OBO-A"))
          body (decode-json response)]
      (is (= 200 (:status response)))
      (is (= ["Occupied" "Occupied"] (get-in body [:invocation :players])))
      (is (nil? (get-in body [:invocation :description])))
      (is (empty? (:chat body)))
      (is (nil? (:createdBy body))))))

(deftest modern-player-page-preserves-the-game-id-in-the-client-route
  (let [response (routes/modern-play-page
                  {:path-params {:play "pond life/alpha"}})]
    (is (= 302 (:status response)))
    (is (= "/modern/?game=pond%20life%2Falpha"
           (get-in response [:headers "Location"])))))

(deftest modern-session-bootstrap-uses-server-identity-and-field-limits
  (with-redefs [persist/canonical-player-name (fn [_ player] (when (= player "alice") player))]
   (doseq [player [nil "alice"]]
    (let [response ((api-app) (json-request "/api/v1/organism/session" player))
          body (when (= 200 (:status response)) (decode-json response))]
      (is (= 200 (:status response)))
      (is (= player (:player body)))
      (is (= [(or player "") ""] (get-in body [:defaults :players])))
      (is (= 4 (get-in body [:defaults :ring-count])))
      (is (= "open" (get-in body [:defaults :visibility])))
      (is (= (vec (range 1 11)) (get-in body [:limits :playerCounts])))
      (is (= (vec (range 3 8)) (get-in body [:limits :ringCounts])))
      (is (= 7 (count (get-in body [:defaults :colors]))))
      (is (some #(= "OBO" (:name %)) (:bots body)))
      (is (not (re-find #"password|session-key|csrf-token" (str (:body response)))))))))

(def command-game-state
  {:key "canonical-game"
   :invocation {:players ["orb" "mass"]}
   :game examples/two-player-close
   :history [examples/two-player-close]
   :chat []})

;; Route units emulate the atomic persistence seam; mutation-test uses Mongo.
(use-fixtures :each
  (fn [run]
    (with-redefs [persist/repair-mutation! (fn [& _])
                  persist/commit-mutation!
                  (fn [db key current next-game receipt _]
                    (persist/update-state! db key (:state next-game))
                    (persist/complete-command! db key (:command-id receipt)
                                               (count (:history current)))
                    (persist/load-game db key))]
      (run))))

(defn- advance-to-phase
  [initial-game requested-phase]
  (loop [current initial-game
         remaining 100]
    (let [actor (game/current-player current)
          context (actions/action-context current actor)]
      (cond
        (= requested-phase (:phase context)) (:game context)
        (zero? remaining) (throw (ex-info "phase not reached" {:phase requested-phase}))
        :else (let [actions (:actions context)
                    preferred (some #(when (str/includes? (str/lower-case (:label %)) "move") %)
                                    actions)
                    action-id (:actionId (or preferred (first actions)))
                    resolution (actions/resolve-current-action current actor action-id)]
                (recur (:game (first (:matches resolution))) (dec remaining)))))))

(deftest persists-a-legal-command-and-returns-the-new-projection
  (let [stored (atom command-game-state)
        persisted-command (atom nil)
        player-updates (atom [])
        action-id (:actionId (first (:actions (actions/action-context
                                               (:game command-game-state)
                                               "orb"))))]
    (with-redefs [persist/load-game (fn [_ _] @stored)
                  persist/find-command (constantly nil)
                  persist/reserve-command! (fn [_ record]
                                             {:reserved? true :record record})
                  persist/update-state! (fn [_ _ state]
                                          (swap! stored
                                                 (fn [game-state]
                                                   (-> game-state
                                                       (assoc-in [:game :state] state)
                                                       (update :history conj state)))))
                  persist/update-player-games! (fn [_ game-id players state]
                                                 (swap! player-updates conj
                                                        [game-id players state]))
                  persist/complete-game! (fn [& _]
                                           (throw (ex-info "not complete" {})))
                  persist/complete-command! (fn [_ game-id command-id revision]
                                              (reset! persisted-command
                                                      [game-id command-id revision]))]
      (let [response ((api-app)
                      (command-request "canonical-game" "orb"
                                       {:actionId action-id
                                        :expectedRevision 0
                                        :commandId "command-1"}))
            body (decode-json response)]
        (is (= 200 (:status response)))
        (is (= 1 (:revision body)))
        (is (= ["canonical-game" "command-1" 1]
               @persisted-command))
        (is (= 1 (count @player-updates)))
        (is (not= examples/two-player-close (:game @stored)))))))

(deftest persists-a-source-and-destination-as-one-revision
  (let [move-game (advance-to-phase (:game command-game-state) :move-from)
        compound-game-state (assoc command-game-state
                                   :game move-game
                                   :history [move-game])
        actor (game/current-player move-game)
        stored (atom compound-game-state)
        source (first (:actions (actions/action-context
                                 move-game
                                 actor)))
        target (first (:nextActions source))]
    (is (some? target))
    (with-redefs [persist/load-game (fn [_ _] @stored)
                  persist/find-command (constantly nil)
                  persist/reserve-command! (fn [_ record]
                                             {:reserved? true :record record})
                  persist/update-state! (fn [_ _ state]
                                          (swap! stored
                                                 (fn [game-state]
                                                   (-> game-state
                                                       (assoc-in [:game :state] state)
                                                       (update :history conj state)))))
                  persist/update-player-games! (fn [& _])
                  persist/complete-game! (fn [& _]
                                           (throw (ex-info "not complete" {})))
                  persist/complete-command! (fn [& _])]
      (let [response ((api-app)
                      (command-request "canonical-game" actor
                                       {:actionId [(:actionId source) (:actionId target)]
                                        :expectedRevision 0
                                        :commandId "compound-command"}))
            body (decode-json response)]
        (is (= 200 (:status response)))
        (is (= 1 (:revision body)))
        (is (= 2 (count (:history @stored))))))))

(deftest retries-a-completed-command-without-applying-it-again
  (let [writes (atom 0)
        existing {:game-key "canonical-game"
                  :command-id "command-1"
                  :player "orb"
                  :action-id "same-action"
                  :expected-revision 0
                  :status "complete"
                  :revision 1}]
    (with-redefs [persist/load-game (constantly command-game-state)
                  persist/find-command (fn [_ _ _] existing)
                  persist/update-player-games! (fn [& _])
                  persist/update-state! (fn [& _] (swap! writes inc))]
      (let [response ((api-app)
                      (command-request "canonical-game" "orb"
                                       {:actionId "same-action"
                                        :expectedRevision 0
                                        :commandId "command-1"}))]
        (is (= 200 (:status response)))
        (is (zero? @writes))))))

(deftest rejects-reuse-of-a-command-id-for-a-different-command
  (with-redefs [persist/load-game (constantly command-game-state)
                persist/find-command
                (fn [_ _ _]
                  {:game-key "canonical-game"
                   :command-id "command-1"
                   :player "orb"
                   :action-id "original-action"
                   :expected-revision 0
                   :status "complete"})]
    (let [response ((api-app)
                    (command-request "canonical-game" "orb"
                                     {:actionId "different-action"
                                      :expectedRevision 0
                                      :commandId "command-1"}))
          body (decode-json response)]
      (is (= 409 (:status response)))
      (is (= "command-id-conflict" (:error body))))))
