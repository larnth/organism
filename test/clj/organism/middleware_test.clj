(ns organism.middleware-test
  (:require
   [clojure.test :refer :all]
   [organism.middleware :as middleware]))

(deftest production-session-settings-require-a-strong-secret
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"SESSION_SECRET"
                        (middleware/session-store-for {:prod true})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"at least 32"
                        (middleware/session-store-for
                         {:prod true :session-secret "too-short"})))
  (is (some? (middleware/session-store-for
              {:prod true
               :session-secret (apply str (repeat 32 "s"))
               :public-origin "https://organism.example"}))))

(deftest production-session-cookie-is-secure-and-same-site
  (is (= {:http-only true
          :max-age 2592000
          :same-site :lax
          :secure true}
         (middleware/session-cookie-attrs {:prod true})))
  (is (false? (:secure (middleware/session-cookie-attrs {:dev true})))))

(deftest production-mutations-require-the-configured-origin
  (let [called (atom 0)
        app (middleware/wrap-require-origin
             (fn [_]
               (swap! called inc)
               {:status 200})
             {:prod true
              :session-secret (apply str (repeat 32 "s"))
              :public-origin "https://organism.example"})]
    (is (= 403 (:status (app {:request-method :post :headers {}}))))
    (is (= 403 (:status (app {:request-method :post
                              :headers {"origin" "https://attacker.example"}}))))
    (is (= 200 (:status (app {:request-method :post
                              :headers {"origin" "https://organism.example"}}))))
    (is (= 200 (:status (app {:request-method :get :headers {}}))))
    (is (= 2 @called))))

(deftest production-websockets-require-the-configured-origin
  (let [app (middleware/wrap-require-origin
             (constantly {:status 101})
             {:prod true
              :session-secret (apply str (repeat 32 "s"))
              :public-origin "https://organism.example"})]
    (is (= 403 (:status (app {:request-method :get
                              :headers {"upgrade" "websocket"
                                        "origin" "https://attacker.example"}}))))
    (is (= 101 (:status (app {:request-method :get
                              :headers {"upgrade" "websocket"
                                        "origin" "https://organism.example"}}))))))

(deftest production-origin-protection-requires-a-public-origin
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"PUBLIC_ORIGIN"
                        (middleware/validate-production-config!
                         {:prod true
                          :session-secret (apply str (repeat 32 "s"))}))))

(deftest production-requests-use-the-configured-public-origin
  (let [wrapped (middleware/wrap-public-origin
                 (fn [request]
                   {:status 200
                    :body (assoc (select-keys request [:scheme :server-name :server-port])
                                 :host (get-in request [:headers "host"]))})
                 {:prod true
                  :session-secret (apply str (repeat 32 "s"))
                  :public-origin "https://organism.example"})]
    (is (= {:scheme :https
            :server-name "organism.example"
            :server-port 443
            :host "organism.example"}
           (:body (wrapped {:scheme :http
                            :server-name "app"
                            :server-port 11551
                            :headers {"host" "app:11551"}}))))))
