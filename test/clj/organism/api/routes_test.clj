(ns organism.api.routes-test
  (:require
   [clojure.test :refer :all]
   [muuntaja.core :as m]
   [organism.api.actions :as actions]
   [organism.examples :as examples]
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

(def command-game-state
  {:key "canonical-game"
   :invocation {:players ["orb" "mass"]}
   :game examples/two-player-close
   :history [examples/two-player-close]
   :chat []})

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
