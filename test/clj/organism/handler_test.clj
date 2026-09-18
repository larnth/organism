(ns organism.handler-test
  (:require
    [clojure.test :refer :all]
    [clojure.string]
    [ring.mock.request :refer :all]
    [organism.mongo :as db]
    [organism.persist :as persist]
    [organism.handler :refer :all]
    [organism.middleware.formats :as formats]
    [ring.util.response :as response]
    [muuntaja.core :as m]
    [mount.core :as mount]))

(defn parse-json [body]
  (m/decode formats/instance "application/json" body))

(use-fixtures
  :once
  (fn [f]
    (mount/start #'organism.config/env
                 #'organism.handler/app-routes)
    (f)))

(deftest test-app
  (testing "root redirects to the organism landing page"
    ;; the multi-game catalog is no longer surfaced — see routes.home/root-redirect
    (let [response ((app) (request :get "/"))]
      (is (= 302 (:status response)))
      ;; ring absolutizes the Location against the request host
      (is (clojure.string/ends-with? (get-in response [:headers "Location"])
                                     "/organism"))))

  (testing "and that landing page renders"
    (let [response ((app) (request :get "/organism"))]
      (is (= 200 (:status response)))))

  (testing "not-found route"
    (let [response ((app) (request :get "/invalid"))]
      (is (= 404 (:status response))))))

(deftest production-mongo-configuration-comes-from-the-environment
  (is (= {:host "mongo"
          :port 27018
          :database "organism-production"}
         (mongo-config {:mongo-host "mongo"
                        :mongo-port "27018"
                        :mongo-database "organism-production"}))))

(deftest modern-entry-serves-its-index-without-losing-the-game-query
  (let [paths (atom [])]
    (with-redefs [response/resource-response
                  (fn [path & _]
                    (when (= "public/modern/index.html" path)
                      (swap! paths conj path)
                      (response/response "<html><body>modern entry fixture</body></html>")))]
      (doseq [url ["/modern/?game=pond%20life" "/modern?game=pond%20life"]]
        (let [result ((app) (request :get url))]
          (is (= 200 (:status result)))
          (is (= "no-cache" (get-in result [:headers "Cache-Control"])))
          (is (clojure.string/includes? (str (:body result)) "modern entry fixture"))))
      (is (= ["public/modern/index.html" "public/modern/index.html"] @paths)))))

(deftest application-startup-enforces-case-insensitive-player-identities
  (let [calls (atom [])
        prepare (ns-resolve 'organism.handler 'prepare-database!)]
    (is (ifn? prepare))
    (when prepare
      (with-redefs [persist/ensure-player-identity-index!
                    (fn [database] (swap! calls conj database))]
        (is (= ::database (prepare ::database)))
        (is (= [::database] @calls))))))

(deftest health-check-reflects-database-readiness
  (with-redefs [db/collections (constantly #{"games"})]
    (is (= 200 (:status (health-response :database)))))
  (with-redefs [db/collections (fn [_] (throw (Exception. "offline")))]
    (is (= 503 (:status (health-response :database))))))
