;; Opt-in test entry point, never packaged in the production jar. The normal
;; application still starts unchanged; only an owned acceptance database is seeded.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[organism.core :as core]
         '[organism.mongo :as mongo]
         '[organism.persist :as persist]
         '[organism.routes.organism-bot :as bot]
         '[organism.routes.websockets :as ws])

(let [database (System/getenv "MONGO_DATABASE")
      host (System/getenv "MONGO_HOST")
      owner (when (and database (re-matches #"organism-acceptance-[0-9a-f-]{36}" database))
              (subs database (count "organism-acceptance-")))]
  (assert (and owner (= "127.0.0.1" host)) "Acceptance requires an owned loopback database")
  (let [db (mongo/connect! {:host host
                            :port (parse-long (System/getenv "MONGO_PORT"))
                            :database database})]
    (assert (mongo/one db :acceptanceOwnership {:_id owner}) "Acceptance ownership marker missing")
    (assert (or (zero? (mongo/number db :games))
                (mongo/one db :games {:key "acceptance-bot-completion" :acceptance-fixture true}))
            "Nonempty acceptance database has no owned fixture")
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(.close (.getMongo db))))
    (let [{:keys [invocation game source]} (edn/read-string (slurp (io/resource "organism/bot-completion.edn")))
          key "acceptance-bot-completion"]
      (assert (nil? (get-in game [:state :winner])) "Fixture must precede winner declaration")
      (when-not (persist/load-game db key)
        (persist/create-game! db {:key key :game-type "organism" :invocation invocation
                                 :game game :chat [] :history [] :created-by "acceptance-fixture"
                                 :bots (:players invocation) :acceptance-fixture true}))
      (core/start-app *command-line-args*)
      ;; No observer connects or clicks: the real durable bot runner must save
      ;; the engine's declaration before the browser's result/reopen checks.
      (when-let [task (bot/run-bot-turns! ws/games key 0 nil nil db)]
        (assert (not= ::timeout (deref task 10000 ::timeout)) "Completion runner timed out"))
      ;; A completed record must not acquire another runner on restart.
      (assert (= (:expected-winner source) (get-in (persist/load-game db key) [:game :state :winner]))
              "The actual durable bot runner did not record the fixture's winner")
      (println "Acceptance fixture ready:" key))))
