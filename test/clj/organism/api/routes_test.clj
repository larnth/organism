(ns organism.api.routes-test
  (:require
   [clojure.test :refer :all]
   [muuntaja.core :as m]
   [organism.api.actions :as actions]
   [organism.middleware.formats :as formats]
   [organism.persist :as persist]
   [organism.routes.organism :as routes]
   [reitit.ring :as ring]
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
  (ring/ring-handler
   (ring/router [(routes/modern-api-routes :test-db)])))

(defn- json-request
  [path player]
  (-> (mock/request :get path)
      (mock/header "accept" "application/json")
      (assoc :session {:player player})))

(defn- decode-json
  [response]
  (m/decode formats/instance "application/json" (:body response)))

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
