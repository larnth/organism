(ns organism.routes.home-test
  (:require
   [clojure.test :refer :all]
   [organism.layout :as layout]
   [organism.persist :as persist]
   [organism.routes.home :as home]))

(deftest public-registration-validates-player-names
  (is (home/valid-player-name? "Ada Lovelace"))
  (is (home/valid-player-name? "player-one_2"))
  (is (not (home/valid-player-name? "")))
  (is (not (home/valid-player-name? (apply str (repeat 33 "a")))))
  (is (not (home/valid-player-name? "<script>"))))

(deftest public-registration-requires-a-substantial-password
  (is (home/valid-registration-password? "correct horse battery staple"))
  (is (not (home/valid-registration-password? "short")))
  (is (not (home/valid-registration-password? (apply str (repeat 201 "x"))))))

(deftest unknown-login-does-not-enumerate-registered-player-names
  (let [checks (atom 0)]
    (with-redefs [persist/find-player-password (fn [_ _] nil)
                  buddy.hashers/check (fn [_ _] (swap! checks inc) false)
                  layout/render (fn [_ _ context] context)]
      (let [response (home/login-submit
                      ::db
                      {:params {:player "unknown"
                                :password "not-a-real-password"}})]
      (is (= "Invalid player name or password" (:error response)))
        (is (nil? (:register-url response)))
        (is (= 1 @checks))))))

(deftest invite-login-explains-the-return-to-seat-selection
  (with-redefs [layout/render (fn [_ template context]
                               {:template template :context context})]
    (let [{:keys [template context]}
          (home/login-page {:query-params {"redirect" "/organism/create/moss-table"}})]
      (is (= "login.html" template))
      (is (true? (:lobby-return context)))
      (is (= "/organism/create/moss-table" (:redirect context))))
    (is (false? (get-in (home/login-page {:query-params {"redirect" "/organism/play"}})
                        [:context :lobby-return])))))
