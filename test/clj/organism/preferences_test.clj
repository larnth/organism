(ns organism.preferences-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [monger.collection :as mongo]
   [organism.board :as board]
   [organism.mongo :as db]
   [organism.persist :as persist]
   [organism.routes.home :as home]))

;; Synthetic inputs only. Capture the actual Mongo update and HTTP persistence
;; arguments without opening a database or printing account/request values.
(def hostile-preferences
  {:key "Other"
   :identity-key "other"
   :password "synthetic-marker"
   :_id "other-id"
   :player "Other"
   :identity {:role :admin}
   :auth {:admin true}
   :roles [:admin]
   :admin true
   :session {:player "Other"}
   :unknown-appearance "not-supported"
   :password.hash "synthetic-marker"
   :$set {:password "synthetic-marker"}
   "$unset" {"password" ""}
   "key" "Other"
   "identity-key" "other"
   "password" "synthetic-marker"})

(defn- capture-update
  [existing preferences]
  (let [calls (atom [])]
    (with-redefs [db/one (fn [_ _ _] existing)
                  mongo/update (fn [& args] (swap! calls conj (vec args)))]
      (persist/update-player-preferences! ::db "aLiCe" preferences))
    @calls))

(deftest persistence-allowlists-preferences-before-building-the-mongo-update
  (doseq [[existing canonical where]
          [[{:_id "account-id" :key "Alice"} "Alice" {:_id "account-id"}]
           [{:key "Alice"} "Alice" {:identity-key "alice"}]
           [nil "aLiCe" {:identity-key "alice"}]]]
    (let [calls (capture-update existing (assoc hostile-preferences :color "#aBc123"))
          expected [[::db "players" where
                     {:$set {:key canonical :identity-key "alice" :color "#aBc123"}}
                     {:upsert true}]]]
      (is (true? (= expected calls))
          "Only server-owned identity fields and the supported color reach Mongo"))))

(deftest persistence-preserves-existing-color-formats
  (doseq [color ["#abc" "#aBc123" "#abcd" "#aBc123ff" "green"
                 "rgb(12, 34, 56)" "rgba(12, 34, 56, 0.5)"
                 "hsl(120, 50%, 40%)" "hsla(120, 50%, 40%, 1.0)"
                 (board/random-color 0.4 0.8)]]
    (let [calls (capture-update {:_id "account-id" :key "Alice"} {:color color})]
      (is (true? (= color (get-in calls [0 3 :$set :color])))
          "Preference filtering must not rewrite or narrow existing CSS colors"))))

(deftest absent-color-does-not-clear-a-stored-preference
  (doseq [preferences [nil {} hostile-preferences]]
    (let [calls (capture-update {:_id "account-id" :key "Alice"} preferences)]
      (is (true? (= {:key "Alice" :identity-key "alice"}
                    (get-in calls [0 3 :$set])))
          "Missing color must not become a color reset or an arbitrary field write"))))

(defn- capture-submit
  [handler params]
  (let [calls (atom [])
        lookups (atom [])
        result (atom nil)
        errors (java.io.StringWriter.)
        output (binding [*err* errors]
                 (with-out-str
                   (with-redefs [persist/canonical-player-name
                                 (fn [database player]
                                   (swap! lookups conj [database player])
                                   "Alice")
                                 persist/update-player-preferences!
                                 (fn [& args] (swap! calls conj (vec args)))]
                     (reset! result
                             (handler ::db {:path-params {:player "aLiCe"}
                                            :session {:player "Alice"}
                                            :params params})))))]
    {:calls @calls :lookups @lookups :response @result
     :output output :errors (str errors)}))

(deftest http-entry-points-only-forward-supported-preferences
  (doseq [handler [home/apply-player-preferences home/account-submit]]
    (let [{:keys [calls lookups response]}
          (capture-submit handler (assoc hostile-preferences :color "hsla(120, 50%, 40%, 1.0)"))]
      (is (true? (= [[::db "Alice" {:color "hsla(120, 50%, 40%, 1.0)"}]] calls))
          "Request identity/auth fields must not reach the preferences writer")
      (is (true? (= [[::db "aLiCe"]] lookups))
          "Account selection comes from the guarded path, never the request body")
      (is (= 200 (:status response)))
      (is (true? (get-in response [:body :ok]))))))

(deftest http-entry-points-do-not-invent-a-missing-color
  (doseq [handler [home/apply-player-preferences home/account-submit]
          params [nil {} hostile-preferences]]
    (is (true? (= [[::db "Alice" {}]] (:calls (capture-submit handler params))))
        "An omitted color is not forwarded as nil")))

(deftest preference-submissions-do-not-log-request-data
  (doseq [handler [home/apply-player-preferences home/account-submit]]
    (let [{:keys [output errors]} (capture-submit handler hostile-preferences)]
      (is (str/blank? output) "Do not log raw preference submissions to stdout")
      (is (str/blank? errors) "Do not log raw preference submissions to stderr"))))

(deftest both-registered-post-routes-require-the-owning-player
  (doseq [path ["/player/:player/preferences" "/player/:player/account"]]
    (let [route (some #(when (= path (first %)) (second %))
                      (drop 2 (home/home-routes ::db)))
          handler (reduce (fn [handler middleware] (middleware handler))
                          (:post route) (reverse (:middleware route)))
          calls (atom [])]
      (with-redefs [persist/canonical-player-name (constantly "Alice")
                    persist/update-player-preferences!
                    (fn [& args] (swap! calls conj (vec args)))]
        (testing "logged-out and other-player requests cannot write"
          (doseq [session [{} {:player "Other"}]]
            (let [response (handler {:uri "/player/aLiCe/preferences"
                                     :path-params {:player "aLiCe"}
                                     :session session
                                     :params {:color "#abc"}})]
              (is (= 302 (:status response)))
              (is (empty? @calls)))))
        (testing "the owner may use a mixed-case path"
          (let [response (atom nil)]
            (with-out-str
              (reset! response
                      (handler {:path-params {:player "aLiCe"}
                                :session {:player "Alice"}
                                :params {:color "#abc"}})))
            (is (= 200 (:status @response)))
            (is (true? (= [[::db "Alice" {:color "#abc"}]] @calls)))))))))
