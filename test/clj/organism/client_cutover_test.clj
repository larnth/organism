(ns organism.client-cutover-test
  (:require [clojure.test :refer :all]
            [organism.config :as config]
            [organism.layout :as layout]
            [organism.persist :as persist]
            [organism.routes.organism :as routes]
            [reitit.ring :as ring]
            [ring.middleware.params :refer [wrap-params]]
            [ring.mock.request :as mock]))

(defn- request-page [path]
  ((wrap-params (ring/ring-handler (ring/router [(routes/organism-routes :test-db)])))
   (assoc (mock/request :get path) :session {:player "alice"})))

(use-fixtures :each
  (fn [test]
    (with-redefs [config/env {}
                  persist/find-player-preferences (constantly {})
                  persist/find-open-game (constantly nil)
                  layout/render (fn [_ template _] {:status 200 :body template})]
      (test))))

(deftest public-creation-enters-modern-client-by-default
  (let [response (request-page "/organism/create")]
    (is (= 302 (:status response)))
    (is (= "/modern/?view=create" (get-in response [:headers "Location"])))))

(deftest existing-lobby-and-game-bookmarks-preserve-identity
  (doseq [prefix ["create" "play"] suffix ["" "/"]]
    (let [response (request-page (str "/organism/" prefix "/pond%20%26%20life" suffix))]
      (is (= 302 (:status response)))
      (is (= "/modern/?game=pond%20%26%20life" (get-in response [:headers "Location"]))))))

(deftest legacy-client-can-be-selected-without-rolling-back-durable-backend
  (doseq [[path template] [["/organism/create" "organism/create.html"]
                          ["/organism/play/pond" "organism/play.html"]]]
    (is (= template (:body (request-page (str path "?client=legacy")))))
    (with-redefs [config/env {:organism-client "legacy"}]
      (is (= template (:body (request-page path)))))))

(deftest explicit-modern-bookmarks-remain-modern-during-ui-rollback
  (with-redefs [config/env {:organism-client "legacy"}]
    (is (= "/modern/?game=pond" (get-in (request-page "/organism/modern/pond") [:headers "Location"])))))
