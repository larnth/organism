(ns organism.player-identity-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [organism.mongo :as db]
   [organism.persist :as persist]
   [organism.routes.home :as home]
   [organism.layout :as layout]
   [buddy.hashers :as hashers]))

(def test-connection
  {:host "localhost" :port 27017 :database "organism-player-identity-test"})

(def ^:dynamic *db* nil)

(use-fixtures
  :each
  (fn [run]
    (let [connection (db/connect! test-connection)]
      (db/delete! connection :players {})
      (binding [*db* connection] (run))
      (db/delete! connection :players {}))))

(defn- registration-request
  [player]
  {:params {:player player
            :password "a-long-enough-password"
            :password-confirm "a-long-enough-password"
            :redirect "/organism"}})

(deftest registration-rejects-a-name-that-only-differs-by-case
  (with-redefs [hashers/derive (constantly "hashed")
                layout/render (fn [_ _ context] context)]
    (home/register-submit *db* (registration-request "Alice"))
    (let [response (home/register-submit *db* (registration-request "alice"))]
      (is (= "That player name is already taken" (:error response)))
      (is (= 1 (db/number *db* :players))))))

(deftest claiming-a-name-never-overwrites-an-existing-password
  (is (true? (persist/claim-player-name! *db* "Alice" "first-hash" {:color "green"})))
  (is (false? (persist/claim-player-name! *db* "aLiCe" "second-hash" {:color "red"})))
  (is (= "first-hash" (persist/find-player-password *db* "ALICE")))
  (is (= "Alice" (persist/canonical-player-name *db* "alice"))))

(deftest login-is-case-insensitive-and-preserves-the-canonical-name
  (persist/set-player-password! *db* "Alice" "hashed")
  (with-redefs [hashers/check (fn [_ hash] (= "hashed" hash))]
    (let [response (home/login-submit
                    *db*
                    {:params {:player "ALICE"
                              :password "a-long-enough-password"
                              :redirect "/organism"}})]
      (is (= "Alice" (get-in response [:session :player]))))))

(deftest player-pages-resolve-mixed-case-paths-to-the-canonical-account
  (persist/set-player-password! *db* "Alice" "hashed")
  (with-redefs [layout/render (fn [_ _ context] context)]
    (let [response (home/player-page
                    *db*
                    {:path-params {:player "aLiCe"}})]
      (is (= "Alice" (:player response))))))

(deftest player-auth-compares-account-identities-case-insensitively
  (let [response ((home/require-player-auth (constantly {:status 204}))
                  {:path-params {:player "aLiCe"}
                   :session {:player "Alice"}})]
    (is (= 204 (:status response)))))

(deftest identity-index-backfill-refuses-existing-case-collisions
  (db/insert! *db* :players {:key "Alice" :password "one"})
  (db/insert! *db* :players {:key "alice" :password "two"})
  (testing "the migration stops rather than silently choosing an account"
    (let [ensure-index (ns-resolve 'organism.persist 'ensure-player-identity-index!)]
      (is (ifn? ensure-index))
      (when ensure-index
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"case-insensitive player-name collision"
             (ensure-index *db*)))))))
