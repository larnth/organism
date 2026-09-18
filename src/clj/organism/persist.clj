(ns organism.persist
  (:require
   [buddy.hashers :as hashers]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [organism.bots :as bots]
   [organism.board :as board]
   [organism.mongo :as db])
  (:import
   [java.util Locale]))

(defn serialize-state
  [state]
  (-> state
      (update :elements pr-str)
      (update :captures pr-str)
      (update :food pr-str)
      (update-in [:player-turn :organism-turns] pr-str)
      (update-in [:player-turn :introduction] pr-str)))

(defn conditional-string
  [s]
  (if (string? s)
    (read-string s)
    s))

(defn deserialize-state
  [state]
  (-> state
      (dissoc :_id)
      (update :elements read-string)
      (update :food conditional-string) ;; TODO: remove this once migrated
      (update :captures conditional-string) ;; TODO: remove this once migrated
      (update-in [:player-turn :organism-turns] read-string)
      (update-in [:player-turn :introduction] conditional-string)))

(defn filter-ids
  [records]
  (map
   (fn [record]
     (dissoc record :_id))
   records))

(defn history-key
  [key]
  (str "history-" key))

(defn chat-key
  [key]
  (str "chat-" key))

(defn player-games-key
  [key]
  (str "player-games-" key))

(def ^:dynamic *open-game-mutation-token* nil)

(defonce ^:private game-locks (atom {}))
(defn game-lock
  "Single-instance lifecycle lock. Order: game lock, lobby claim, ws registry.
   Never acquire another game's lock while holding the registry monitor."
  [key]
  (get (swap! game-locks #(if (contains? % key) % (assoc % key (Object.)))) key))

(declare load-history game-history-count)

(defn- lobby-table-id [record]
  ;; A read-only, deterministic fallback gives historical waiting records an
  ;; identity without taking a lifecycle lock under the registry monitor.
  (or (:table-id record) (when-let [id (:_id record)] (str "lobby:" id))))

(defn create-open-game!
  "Store (or update) an open lobby. `created-by` is whoever opened it, recorded
   so a lobby can be cleaned up by the person who made it the way a created
   game carries :created-by. Omitting it leaves any existing value alone."
  ([db game-key invocation]
   (create-open-game! db game-key invocation nil))
  ([db game-key invocation created-by]
   (create-open-game! db game-key invocation created-by nil))
  ([db game-key invocation created-by {:keys [visibility password]}]
   (locking (game-lock game-key)
   (println "creating open game!" game-key)
   (db/index! db :open-games [:key] {:unique true})
   (let [existing (db/one db :open-games {:key game-key})
         visibility (or visibility (:visibility existing) "open")
         new-private? (= "private" visibility)
         password-hash (cond
                         (and new-private? (seq password)) (hashers/derive password)
                         new-private? (:password-hash existing)
                         :else nil)
         changes (cond-> {:table-id (or (lobby-table-id existing) (str (java.util.UUID/randomUUID)))
                          :invocation invocation
                          :visibility visibility
                          :password-hash password-hash
                          :password-policy (when new-private?
                                             {:scheme "buddy-hashers-default" :version 1})
                          :readiness (or (:readiness existing) {})}
                   (and created-by (nil? (:created-by existing)))
                   (assoc :created-by created-by))
         owner-query (if created-by
                       {:key game-key
                        :$or [{:created-by created-by}
                              {:created-by nil}
                              {:created-by {:$exists false}}]}
                       {:key game-key})]
     (if *open-game-mutation-token*
       (when-not
        (db/find-and-merge!
         db :open-games
         {:key game-key
          :lifecycle {:$ne "retired"}
          :mutation-claim.token *open-game-mutation-token*}
         changes)
         (throw (ex-info "lobby mutation claim was superseded" {:key game-key})))
       (try
         ;; The owner predicate makes first creation atomic across processes: a
         ;; competing creator cannot match the winning record, and its upsert then
         ;; loses against the unique :key index instead of overwriting the lobby.
         (db/upsert! db :open-games owner-query
                     {:$set changes
                      :$setOnInsert {:key game-key}})
         (catch Exception _
           nil)))
     (db/one db :open-games {:key game-key :lifecycle {:$ne "retired"}})))))

(defn update-open-lobby!
  [db game-key changes]
  (if *open-game-mutation-token*
    (or (db/find-and-merge!
         db :open-games
         {:key game-key
          :lifecycle {:$ne "retired"}
          :mutation-claim.token *open-game-mutation-token*}
         changes)
        (throw (ex-info "lobby mutation claim was superseded" {:key game-key})))
    (db/merge! db :open-games {:key game-key} changes)))

(declare find-open-game)

(def lobby-claim-lease-ms 30000)

(defn- claim-open-game!
  [db game-key field]
  (let [now (System/currentTimeMillis)
        token (str (java.util.UUID/randomUUID))
        claim {:token token :expires-at (+ now lobby-claim-lease-ms)}]
    (when (db/find-and-merge!
           db :open-games
           {:key game-key
            :lifecycle {:$ne "retired"}
            :$or [{field {:$exists false}}
                  {field nil}
                  {(keyword (str (name field) ".expires-at")) {:$lte now}}]}
           {field claim})
      claim)))

(defn- release-open-game-claim!
  [db game-key field token]
  (db/find-and-merge!
   db :open-games
   {:key game-key
    (keyword (str (name field) ".token")) token}
   {field nil}))

(defn claim-open-game-start!
  "Atomically reserve a lobby launch across server processes with a crash-expiring lease."
  [db game-key]
  (claim-open-game! db game-key :start-claim))

(defn release-open-game-start!
  [db game-key token]
  (release-open-game-claim! db game-key :start-claim token))

(defn remove-claimed-open-game!
  [db game-key token]
  (pos? (.getN (db/delete! db :open-games
                           {:key game-key :start-claim.token token}))))

(defn retire-claimed-open-game!
  "Atomically hide a claimed lobby and retain its winning token for recovery."
  [db game-key token]
  (boolean
   (db/find-and-merge!
    db :open-games
    (cond-> {:key game-key
             :lifecycle {:$ne "retired"}
             :start-claim.token token}
      *open-game-mutation-token*
      (assoc :mutation-claim.token *open-game-mutation-token*))
    {:lifecycle "retired"
     :retired-transition-id token})))

(defn claim-open-game-mutation!
  "Serialize lobby mutations across application processes with a crash-expiring lease."
  [db game-key]
  (claim-open-game! db game-key :mutation-claim))

(defn release-open-game-mutation!
  [db game-key token]
  (release-open-game-claim! db game-key :mutation-claim token))

(defn with-open-game-mutation!
  ([db game-key mutate]
   (with-open-game-mutation! db game-key false mutate))
  ([db game-key allow-create? mutate]
   (locking (game-lock game-key)
   (if (nil? db)
     (mutate)
     (if-let [{:keys [token]} (claim-open-game-mutation! db game-key)]
       (try
         (binding [*open-game-mutation-token* token]
           (mutate))
         (finally
           (release-open-game-mutation! db game-key token)))
       (if (find-open-game db game-key)
         {:error "this lobby is busy; try again"}
         (if (and allow-create?
                  (nil? (db/one db :open-games {:key game-key :lifecycle "retired"}))
                  (nil? (db/one db :games {:key game-key})))
           (mutate)
           {:error "no such open game"})))))))

(defn record-private-admission-attempt!
  "Durably record one password attempt and report whether it exceeds the window limit."
  [db game-key player-key now window-ms limit]
  (let [where {:game-key game-key :player-key player-key}
        cutoff (- now window-ms)]
    (db/delete! db :lobby-admission-attempts
                (assoc where :attempted-at {:$lte cutoff}))
    (db/insert! db :lobby-admission-attempts
                (assoc where :attempted-at now))
    (> (db/number db :lobby-admission-attempts where) limit)))

(defn remove-created-game!
  "Roll back a newly-created game if the lobby transition cannot finish."
  [db game-key players transition-id]
  (when (db/one db :games {:key game-key :transition-id transition-id})
    (db/delete! db :games {:key game-key :transition-id transition-id})
    (db/delete! db (history-key game-key) {})
    (doseq [player players]
      (db/delete! db (player-games-key player) {:game game-key}))))

(defn remove-open-game!
  [db game-key]
  (println "removing open game!" game-key)
  (db/delete!
   db :open-games
   {:key game-key}))

(defn invocation-colors
  [invocation]
  (board/find-player-colors
   (:players invocation)
   (map last (:colors invocation))))

(defn create-player-game!
  [db game-key invocation player state game-type]
  (let [round (:round state)
        players (:players invocation)
        current-player (-> state :player-turn :player)
        player-colors (invocation-colors invocation)]
    (println "creating" player game-key)
    (db/merge!
     db (player-games-key player)
     {:game game-key}
     {:round round
      :status "active"
      :game-type game-type
      :player-colors player-colors
      :invocation invocation
      :players players
      :current-player current-player
      :witness 0
      :winner nil})))

(defn create-player-games!
  [db game-key invocation state game-type]
  (let [players (:players invocation)]
    (doseq [player (reverse players)]
      (create-player-game! db game-key invocation player state game-type))))

(defn find-player-game
  [db game-key player]
  (db/one db (player-games-key player) {:game game-key}))

(defn update-player-game!
  [db game-key player state]
  (let [current-player (-> state :player-turn :player)
        round (:round state)
        now (quot (System/currentTimeMillis) 1000)]
    (println "updating" player game-key current-player)
    (db/merge-all!
     db (player-games-key player)
     {:game game-key}
     {:round round
      :current-player current-player
      :last-move-at now
      ;; Playing is the veto: any turn cancels a pending deletion.
      :deletion nil})))

(defn update-player-games!
  [db game-key players state]
  (doseq [player (reverse players)]
    (update-player-game! db game-key player state)))

(defn store-witness!
  [db game-key player]
  (locking (game-lock game-key)
    (when-let [record (db/one db :games {:key game-key})]
      (when (some #{player} (get-in record [:invocation :players]))
        (db/merge-all! db (player-games-key player) {:game game-key}
                       {:witness (game-history-count db game-key)
                        :witness-revision (get-in record [:canonical :revision])})))))

(defn complete-player-game!
  [db game-key player winner state]
  (println "completing" player game-key)
  (let [round (:round state)]
    (db/merge-all!
     db (player-games-key player)
     {:game game-key}
     {:round round
      :status "complete"
      :winner winner})))

(defn complete-player-games!
  [db game-key players winner state]
  (doseq [player (reverse players)]
    (complete-player-game! db game-key player winner state)))

(defn player-identity-key
  "Stable case-insensitive account key. Display names remain exactly as chosen."
  [player]
  (when (string? player)
    (.toLowerCase ^String player Locale/ROOT)))

(defn find-player-account
  [db player]
  (or (db/one db :players {:identity-key (player-identity-key player)})
      ;; Compatibility during startup migration of legacy account rows.
      (db/one db :players {:key player})))

(defn find-player-password
  [db player]
  (:password (find-player-account db player)))

(defn canonical-player-name
  [db player]
  (:key (find-player-account db player)))

(defn ensure-player-identity-index!
  "Backfill case-insensitive account keys, then enforce them uniquely.

   Existing case-collisions require an operator decision; startup refuses to
   choose one account silently."
  [db]
  (let [players (vec (db/find-all db :players))
        collisions (->> players
                        (group-by #(player-identity-key (:key %)))
                        (keep (fn [[identity records]]
                                (when (> (count records) 1)
                                  [identity (mapv :key records)])))
                        (into {}))]
    (when (seq collisions)
      (throw (ex-info "case-insensitive player-name collision"
                      {:collisions collisions})))
    (doseq [{:keys [_id key identity-key]} players
            :let [normalized (player-identity-key key)]
            :when (not= identity-key normalized)]
      (db/merge-all! db :players {:_id _id} {:identity-key normalized}))
    (db/index! db :players [:identity-key] {:unique true})))

(defn player-has-password?
  [db player]
  (some? (find-player-password db player)))

(defn claim-player-name!
  "Atomically claim an account identity without overwriting an existing login.
   Returns false when another registration already owns the case-folded name."
  [db player hashed-password preferences]
  (let [identity-key (player-identity-key player)
        existing (find-player-account db player)
        account (merge {:key (or (:key existing) player)
                        :identity-key identity-key
                        :password hashed-password}
                       preferences)]
    (try
      (if existing
        (if (:password existing)
          false
          (do
            (db/merge! db :players
                       {:_id (:_id existing)
                        :password {"$exists" false}}
                       account)
            true))
        (do
          (db/insert! db :players account)
          true))
      (catch Throwable error
        ;; A concurrent claimant will hit the unique identity index (or the
        ;; existing row's _id). Only translate that expected race; surface real
        ;; database failures unchanged.
        (if (player-has-password? db player)
          false
          (throw error))))))

(defn set-player-password!
  [db player hashed-password]
  (let [identity-key (player-identity-key player)
        existing (find-player-account db player)
        canonical-name (or (:key existing) player)
        where (if-let [id (:_id existing)] {:_id id} {:identity-key identity-key})]
    (db/merge!
     db :players
     where
     {:key canonical-name
      :identity-key identity-key
      :password hashed-password})))

(defn update-player-preferences!
  "Only appearance preferences may be caller-controlled; account fields are not."
  [db player preferences]
  (let [identity-key (player-identity-key player)
        existing (find-player-account db player)
        canonical-name (or (:key existing) player)
        where (if-let [id (:_id existing)] {:_id id} {:identity-key identity-key})]
    (db/merge!
     db :players
     where
     (merge {:key canonical-name :identity-key identity-key}
            (select-keys preferences [:color])))))

(defn find-player-preferences
  [db player]
  (dissoc
   (find-player-account db player)
   :_id :password))

(defn replacev
  [v from to]
  (mapv
   (fn [x]
     (if (= x from)
       to
       x))
   v))

(defn rename-players
  [from to has-players]
  (update
   has-players
   :players
   replacev
   from to))

(defn rename-player-games
  [player-games from to]
  (map
   (fn [player-game]
     (let [rename (partial rename-players from to)]
       (-> player-game
           (update
            :invocation
            (partial rename-players from to))
           (update
            :player-colors
            (fn [player-colors]
              (-> player-colors
                  (assoc (keyword to) (get player-colors (keyword from)))
                  (dissoc (keyword from)))))
           (rename from to))))
   player-games))

(defn rename-player-in-game
  [])

(defn rename-player!
  [db from to]
  (let [player-games (db/query db (player-games-key from) {})
        player-games (rename-player-games player-games from to)]
    (doseq [player-game player-games]
      (let [game (db/one db :games {:key (:game player-game)})])
      (db/merge!
       db (player-games-key to)
       {:game (:game player-game)}
       (dissoc player-game :game)))))

(defn create-game!
  [db {:keys [key invocation game chat created-by game-type] :as game-state}]
  (locking (game-lock key)
  (let [game-state (assoc game-state :table-id (or (:table-id game-state) (str (java.util.UUID/randomUUID))))
        game-state (update-in game-state [:game :state] serialize-state)
        game-state (update-in game-state [:game :adjacencies] pr-str)
        game-state (update-in game-state [:game :players] pr-str)
        initial-state (get-in game-state [:game :state])
        player-colors (board/invocation-player-colors invocation)]
    (println "creating game" key)
    (db/index! db :games [:key] {:unique true})
    (db/insert! db :games game-state)
    (db/insert! db (history-key key) initial-state)
    (doseq [player (reverse (:players invocation))]
      (db/index! db (player-games-key player) [:game] {:unique true}))
      ;; (db/merge! db :players {:key player} {:color (get player-colors player)})
    (create-player-games! db key invocation initial-state game-type))))

(def game-transition-collection :game-transitions)

(defn find-game-transition
  [db game-key transition-id]
  (db/one db game-transition-collection
          {:game-key game-key :transition-id transition-id}))

(declare discard-game-transition!)

(defn stage-game-transition!
  "Persist the complete launch payload without publishing any active-game records."
  [db {:keys [key] :as game-state} transition-id]
  (when-not (db/one db :open-games
                    {:key key :start-claim.token transition-id})
    (throw (ex-info "lobby start claim was superseded" {:key key})))
  (db/index! db game-transition-collection [:game-key :transition-id] {:unique true})
  (let [record {:game-key key
                :transition-id transition-id
                :status "prepared"
                :prepared-at (System/currentTimeMillis)
                ;; The generated board contains vector/map keys and lazy values
                ;; that the legacy Mongo codec cannot encode directly.
                :game-state-edn (pr-str (assoc game-state :table-id
                                              (lobby-table-id (db/one db :open-games
                                                                     {:key key :start-claim.token transition-id}))))}]
    (try
      (db/insert! db game-transition-collection record)
      (catch Exception error
        (if-not (find-game-transition db key transition-id)
          (throw error))))
    ;; Close the claim-check/insert race. A replacement claim cannot inherit or
    ;; recover this package, and cleanup is scoped to this exact token.
    (when-not (db/one db :open-games
                      {:key key :start-claim.token transition-id})
      (discard-game-transition! db key transition-id)
      (throw (ex-info "lobby start claim was superseded" {:key key})))
    (find-game-transition db key transition-id)))

(defn mark-game-transition-committing!
  [db game-key transition-id]
  (boolean
   (db/find-and-merge! db game-transition-collection
                       {:game-key game-key
                        :transition-id transition-id
                        :status "prepared"}
                       {:status "committing"})))

(defn discard-game-transition!
  [db game-key transition-id]
  (db/delete! db game-transition-collection
              {:game-key game-key :transition-id transition-id}))

(defn- transition-player-record
  [invocation state game-type transition-id]
  {:round (:round state)
   :status "staging"
   :game-type game-type
   :player-colors (invocation-colors invocation)
   :invocation invocation
   :players (:players invocation)
   :current-player (get-in state [:player-turn :player])
   :witness 0
   :winner nil
   :transition-id transition-id})

(defn- write-transition-player!
  [db game-key invocation player state game-type transition-id]
  (let [collection (player-games-key player)
        existing (db/one db collection {:game game-key})]
    (db/index! db collection [:game] {:unique true})
    (cond
      (nil? existing)
      (try
        (db/insert! db collection
                    (assoc (transition-player-record invocation state game-type transition-id)
                           :_id game-key
                           :game game-key))
        (catch Exception error
          (when-not (= transition-id
                       (:transition-id (db/one db collection {:game game-key})))
            (throw error))))

      (= transition-id (:transition-id existing))
      nil

      ;; Lobby disconnects in older releases could leave a witness-only row
      ;; before the game existed. Atomically adopt that harmless placeholder;
      ;; a real active/completed row still belongs to its original transition.
      (and (nil? (:transition-id existing))
           (nil? (:status existing)))
      (when-not
       (= transition-id
          (:transition-id
           (db/find-and-merge!
            db collection
            {:game game-key
             :transition-id {:$exists false}
             :status {:$exists false}}
            (transition-player-record invocation state game-type transition-id))))
        (throw (ex-info "game transition could not adopt player placeholder"
                        {:key game-key :player player})))

      :else
      (throw (ex-info "game transition lost ownership of player record"
                      {:key game-key :player player})))))

(defn- cleanup-published-transition!
  [db game-key transition-id]
  (discard-game-transition! db game-key transition-id)
  (db/delete! db game-transition-collection
              {:game-key game-key :transition-id {:$ne transition-id}})
  (db/delete! db :open-games
              {:key game-key
               :lifecycle "retired"
               :retired-transition-id transition-id}))

(defn materialize-game-transition!
  "Idempotently publish a committed transition. The game record becomes visible last."
  [db game-key transition-id]
  (when-let [{:keys [status game-state-edn]}
             (find-game-transition db game-key transition-id)]
    (when-not (= "committing" status)
      (throw (ex-info "game transition is not committing" {:key game-key})))
    (when-not (db/one db :open-games
                      {:key game-key
                       :lifecycle "retired"
                       :retired-transition-id transition-id})
      (throw (ex-info "game transition does not own the retired lobby" {:key game-key})))
    (let [{:keys [invocation game game-type] :as game-state} (edn/read-string game-state-edn)
          serialized (-> game-state
                         ;; Old recovery packages predate logical table IDs.
                         (assoc :table-id (or (:table-id game-state)
                                              (lobby-table-id (db/one db :open-games
                                                                     {:key game-key :retired-transition-id transition-id}))))
                         (update-in [:game :state] serialize-state)
                         (update-in [:game :adjacencies] pr-str)
                         (update-in [:game :players] pr-str)
                         (assoc :transition-id transition-id
                                :transition-state "activating"))
          initial-state (get-in serialized [:game :state])
          players (:players invocation)]
      (when-not (db/one db (history-key game-key) {:transition-id transition-id})
        (try
          (db/insert! db (history-key game-key)
                      (assoc initial-state
                             :_id transition-id
                             :transition-id transition-id))
          (catch Exception error
            (when-not (db/one db (history-key game-key)
                              {:transition-id transition-id})
              (throw error)))))
      (doseq [player (reverse players)]
        (write-transition-player! db game-key invocation player initial-state
                                  game-type transition-id))
      (let [existing (db/one db :games {:key game-key})]
        (cond
          (nil? existing)
          (do
            (db/index! db :games [:key] {:unique true})
            (try
              (db/insert! db :games serialized)
              (catch Exception error
                (when-not (= transition-id
                             (:transition-id (db/one db :games {:key game-key})))
                  (throw error)))))

          (not= transition-id (:transition-id existing))
          (throw (ex-info "game transition lost ownership of game record"
                          {:key game-key}))))
      (doseq [player players]
        (db/find-and-merge! db (player-games-key player)
                            {:game game-key :transition-id transition-id}
                            {:status "active"}))
      (db/find-and-merge! db :games
                          {:key game-key :transition-id transition-id}
                          {:transition-state "active"
                           :table-id (:table-id serialized)})
      (cleanup-published-transition! db game-key transition-id)
      true)))

(defn commit-game-transition!
  "Retire the exact claimed lobby, then publish only that transition's payload."
  [db game-key transition-id]
  (if (and (find-game-transition db game-key transition-id)
           (retire-claimed-open-game! db game-key transition-id))
    (if (materialize-game-transition! db game-key transition-id)
      true
      (throw (ex-info "committed transition could not be materialized"
                      {:game-key game-key :transition-id transition-id})))
    (do
      (discard-game-transition! db game-key transition-id)
      false)))

(defn- recover-game-transition!
  [db game-key]
  (when-let [{transition-id :retired-transition-id}
             (db/one db :open-games {:key game-key :lifecycle "retired"})]
    (materialize-game-transition! db game-key transition-id)))

(defn assert-legacy-game!
  "Old raw-history repair tools must never change an adopted canonical game."
  [db key]
  (when (:canonical (db/one db :games {:key key}))
    (throw (ex-info "legacy writer refuses canonical game" {:key key}))))

(defn assert-legacy-database!
  "Preflight before a historical bulk repair, including its destructive purge."
  [db]
  (when (db/one db :games {:canonical {:$exists true}})
    (throw (ex-info "legacy migration refuses a database with canonical games" {}))))

(defn require-quiesced-writers!
  "CLI acknowledgement, NOT a distributed lock or automatic quiescence.
   Stop the app and all other writers before passing --writers-quiesced."
  [args]
  (when-not (some #{"--writers-quiesced"} args)
    (throw (ex-info "Stop the app and all other writers, then pass --writers-quiesced; local locks do not protect a standalone process" {}))))

(defn update-state!
  [db key state]
  (assert-legacy-game! db key)
  (let [serial (serialize-state state)]
    (db/insert! db (history-key key) serial)))

(def command-collection :organism-commands)

(defn find-command
  [db game-key command-id]
  (db/one db command-collection
          {:game-key game-key :command-id command-id}))

(defn reserve-command!
  "Reserve a command ID once across server processes."
  [db record]
  (db/index! db command-collection [:game-key :command-id] {:unique true})
  (let [record (assoc record :status "pending")]
    (try
      (db/insert! db command-collection record)
      {:reserved? true :record record}
      (catch Exception error
        (if-let [existing (find-command db (:game-key record) (:command-id record))]
          {:reserved? false :record existing}
          (throw error))))))

(defn complete-command!
  [db game-key command-id revision]
  (db/merge! db command-collection
             {:game-key game-key :command-id command-id}
             {:status "complete" :revision revision}))

(defn delete-command!
  [db game-key command-id]
  (db/delete! db command-collection
              {:game-key game-key :command-id command-id}))

(declare load-game now-seconds)

(defn repair-mutation!
  "Materialize the canonical outbox idempotently before another CAS replaces it."
  [db key]
  (locking (game-lock key)
  (when-let [canonical (:canonical (db/one db :games {:key key}))]
    (let [{:keys [revision state history-index receipt accepted-at]} canonical]
      (db/merge! db (history-key key) {:_id (str "revision-" revision)}
                 (assoc state :mutation-revision revision :history-index history-index :accepted-at accepted-at))
      (db/index! db command-collection [:game-key :command-id] {:unique true})
      (db/merge! db command-collection
                 {:game-key key :command-id (:command-id receipt)} receipt)))))

(defn commit-mutation!
  "Standalone-Mongo CAS accepts a small state snapshot plus exact receipt.
   History and player indexes are recoverable materializations."
  [db key current next-game receipt history-index]
  (locking (game-lock key)
  (let [expected (or (:revision current) (max 0 (dec (count (:history current)))))
        revision (inc expected)
        canonical {:revision revision :accepted-at (now-seconds)
                   :state (serialize-state (:state next-game))
                   :history-index (or history-index (count (:history current)))
                   :receipt (assoc receipt :status "complete" :revision revision)}
        query {:key key :_id (:incarnation-id current)
               :canonical.revision (if (some? (:revision current))
                                              expected {:$exists false})}
        accepted (try
                   (db/find-and-merge! db :games query {:canonical canonical :deletion nil})
                   (catch Exception error
                     ;; Recover only this exact durable command after an ambiguous write.
                     (let [saved (db/one db :games {:key key})]
                       (if (= canonical (:canonical saved)) saved (throw error)))))]
    (when accepted (load-game db key)))))

(defn mark-mutation-finalized! [db key revision]
  (db/find-and-merge! db :games {:key key :canonical.revision revision}
                      {:finalized-revision revision}))

(defn update-chat!
  [db key line]
  (db/insert! db (chat-key key) line))

(defn accept-chat!
  "Client delivery IDs are scoped to game and authenticated player. The
   deterministic Mongo _id makes duplicate/ambiguous insert retries safe."
  [db key line]
  (let [id (when (:client-id line)
             (str (java.util.UUID/nameUUIDFromBytes
                   (.getBytes (pr-str [(:player line) (:client-id line)]) "UTF-8"))))
        stored (cond-> line id (assoc :_id id))]
    (try
      (update-chat! db key stored)
      {:message line}
      (catch Exception error
        (if-let [existing (when id (db/one db (chat-key key) {:_id id}))]
          (if (= (:message line) (:message existing))
            {:message (select-keys existing [:type :id :player :time :message :client-id])}
            {:error "chat-delivery-id-conflict"})
          (throw error))))))

(defn load-game-state
  [db key]
  (assert-legacy-game! db key)
  (db/find-last db (history-key key) {}))

(defn reset-state!
  [db key]
  (assert-legacy-game! db key)
  (let [history (history-key key)
        recent (db/find-last db history {})]
    (db/delete! db history {:_id (:_id recent)})))

(defn complete-game!
  [db game-key state]
  (locking (game-lock game-key)
  (when-let [game-state (db/one db :games {:key game-key})]
   (let [serialized (serialize-state state)
        winner (:winner state)
        players (-> game-state :invocation :players)]
    (when (or (nil? (:canonical game-state))
              (= serialized (get-in game-state [:canonical :state])))
    (db/find-and-merge!
     db :games
     {:key game-key :_id (:_id game-state)}
     {:game (assoc (:game game-state) :state serialized)})
    (complete-player-games!
     db game-key players
     winner state))))))

;; ── Deletion ───────────────────────────────────────────────────────────
;;
;; A game belongs to everyone in it, so removing one is a small workflow rather
;; than a button:
;;
;;   nothing at stake  ──delete──▶  gone now
;;   live game  ──any player marks──▶  pending  ──deadline, silence──▶  gone
;;                                        ▲ any move or objection clears it
;;
;; Deletion needs silence from everyone, which is exactly when a game is dead.
;; A single objection defeats it, so the player who marks a game cannot stall
;; their way to deleting one they are losing. organism.reap runs the sweep.

(def deletion-grace-seconds
  "How long a marked game waits for an objection before the reaper takes it."
  (* 2 24 60 60))

(defn now-seconds
  []
  (quot (System/currentTimeMillis) 1000))

(defn find-published-game
  "Return only a game that crossed the publication boundary."
  [db game-key]
  (db/one db :games
          {:key game-key
           :$or [{:transition-state "active"}
                 {:transition-state {:$exists false}}]}))

(defn find-game-record
  "The published game or non-retired open lobby for this key. Transition
   internals must not be exposed to deletion or other ordinary readers."
  [db game-key]
  (or (find-published-game db game-key)
      (db/one db :open-games {:key game-key :lifecycle {:$ne "retired"}})))

(defn game-player-names
  "Every real name in a game's roster. Open lobbies carry blank slots and a
   roster can repeat a name, so this is not simply (:players invocation)."
  [db game-key]
  (->> (get-in (find-game-record db game-key) [:invocation :players])
       (remove str/blank?)
       distinct
       vec))

(defn game-history-count
  [db game-key]
  (if-let [canonical (:canonical (db/one db :games {:key game-key}))]
    (inc (:history-index canonical))
    (db/number db (history-key game-key))))

(defn game-head
  "Authoritative serialized state and epoch-second activity, without outbox writes.
   Only unadopted games fall back to legacy history ObjectIds."
  [db record]
  (if-let [canonical (:canonical record)]
    {:state (:state canonical) :at (:accepted-at canonical)}
    (let [row (db/find-last db (history-key (:key record)) {})
          id (:_id row)]
      {:state row :at (or (:accepted-at row)
                         (when (instance? org.bson.types.ObjectId id)
                           (.getTimestamp ^org.bson.types.ObjectId id)))})))

(defn last-activity-at
  "Epoch seconds at the canonical head, with a legacy ObjectId fallback."
  [db game-key]
  (:at (game-head db (or (find-published-game db game-key) {:key game-key}))))

(defn- set-deletion!
  "Write (or clear) the deletion flag on a game and mirror it onto every
   player's row, so the games list can show it without a lookup per row.

   These are updates, never upserts. An upsert here would invent a :games
   document for a lobby that only lives in :open-games, and a statusless
   player-games row for anyone missing one — the exact junk the
   remove-empty-games migration had to go clean up."
  [db game-key deletion]
  (let [collection (if (db/one db :games {:key game-key}) :games :open-games)]
    (db/merge-all! db collection {:key game-key} {:deletion deletion})
    (doseq [name (game-player-names db game-key)]
      (db/merge-all! db (player-games-key name) {:game game-key} {:deletion deletion}))))

(defn mark-game-for-deletion!
  "Flag a game for removal once the grace period runs out."
  [db game-key player]
  (let [now (now-seconds)
        deletion {:marked-by player
                  :marked-at now
                  :deadline (+ now deletion-grace-seconds)}]
    (println "marking for deletion" game-key "by" player)
    (set-deletion! db game-key deletion)
    deletion))

(defn unmark-game-for-deletion!
  "Cancel a pending deletion — an objection, or a move."
  [db game-key]
  (println "clearing deletion mark" game-key)
  (set-deletion! db game-key nil))

(defn- purge-collection!
  "Empty a per-game collection, then drop it. The delete runs first so the data
   is gone even where the drop is refused; the drop keeps dead namespaces from
   accumulating one per deleted game."
  [db collection]
  (db/delete! db collection {})
  (try
    (db/drop! db collection)
    (catch Exception e
      (println "could not drop" (name collection) "-" (.getMessage e)))))

(def ^:dynamic *deletion-claim* nil)

(defn with-game-deletion!
  "Hold the game lifecycle lock through eligibility, purge and live removal.
   An open lobby must also yield BOTH mutation and launch claims. This is not
   a cross-process canonical lock; administrative writers must be quiesced."
  [db game-key decide]
  (locking (game-lock game-key)
    (if (or (= *deletion-claim* game-key) (nil? (find-open-game db game-key)))
      (decide)
      (with-open-game-mutation!
        db game-key
        (fn []
          (if-let [{:keys [token]} (claim-open-game-start! db game-key)]
            (try (binding [*deletion-claim* game-key] (decide))
                 (finally (release-open-game-start! db game-key token)))
            {:error "this lobby is launching; try again"}))))))

(defn delete-game!
  "Remove a game and everything hanging off it.

   A game spans :games, :open-games, its history and chat collections,
   command receipts, and one row in every participant's player-games — and
   missing any of them orphans rows in somebody's list. This is the only place
   that should ever take one apart."
  [db game-key]
  (with-game-deletion!
   db game-key
   (fn []
   (when (db/one db :open-games {:key game-key :lifecycle "retired"})
     (throw (ex-info "cannot delete an unpublished launch; recover it first" {:key game-key})))
  (let [players (game-player-names db game-key)]
    (println "deleting game" game-key "for" players)
    (doseq [name players]
      (db/delete! db (player-games-key name) {:game game-key}))
    (db/delete! db :games {:key game-key})
    (db/delete! db :open-games {:key game-key})
    (db/delete! db command-collection {:game-key game-key})
    (db/delete! db game-transition-collection {:game-key game-key})
    (purge-collection! db (history-key game-key))
    (purge-collection! db (chat-key game-key))
    ((requiring-resolve 'organism.routes.websockets/drop-game!) game-key)
    {:key game-key :players players}))))

(defn deserialize-player-game
  [player-game]
  (-> player-game
      (dissoc :_id)
      (update :player-colors walk/stringify-keys)))

(defn load-open-games
  [db]
  (let [records (db/query db :open-games {:lifecycle {:$ne "retired"}})]
    (->> records
         (filter #(not= "private" (:visibility %)))
         (map #(dissoc % :_id :table-id :password-hash :password-policy
                       :mutation-claim :start-claim)))))

(defn load-players
  [db]
  (filter-ids
   (db/find-all db :players)))

(defn ensure-player-color!
  [db player-key color]
  (update-player-preferences! db player-key {:color color})
  color)

(defn load-player-stats
  "Per-player stats scoped to a single game-type (e.g. \"organism\").
   Players with no games of that type are omitted so the page isn't padded
   with all-zero rows for people who only play other games."
  [db game-type]
  (let [players (load-players db)]
    (->> players
         (map
          (fn [{:keys [key color]}]
            (let [games (->> (db/query db (player-games-key key) {:game-type game-type})
                             (filter #(find-published-game db (:game %))))
                  active (count (filter #(= "active" (:status %)) games))
                  complete (count (filter #(= "complete" (:status %)) games))
                  wins (count (filter #(= key (:winner %)) games))
                  ;; Games this player created *and* is a participant in — excludes
                  ;; all-bot /generate games (created-by the clicker, roster is bots).
                  created (db/number db :games {:created-by key
                                                :game-type game-type
                                                :invocation.players key
                                                :$or [{:transition-state "active"}
                                                      {:transition-state {:$exists false}}]})
                  last-move-at (->> games
                                    (map :last-move-at)
                                    (filter some?)
                                    (apply max 0))
                  player-color (or color (ensure-player-color! db key (board/random-color 0.4 0.8)))]
              {:key key
               :color player-color
               :active active
               :complete complete
               :wins wins
               :created created
               :last-move-at last-move-at})))
         (filter (fn [{:keys [active complete created]}]
                   (pos? (+ active complete created))))
         (sort-by :last-move-at >))))

(defn observe-worthy?
  "Keep only games a spectator would want to see: at least one human player,
   and not an auto-generated all-bot game. Bots are identified by the game's
   stored :bots set, the shared bot registry, or the \"generate-\" key prefix
   that every /generate showcase game carries (some legacy ones predate the
   :bots field, so the prefix is the reliable catch-all)."
  [game]
  (let [players (get-in game [:invocation :players])
        bot-set (set (:bots game))
        generated? (str/starts-with? (str (:key game)) "generate-")]
    (and (not generated?)
         (boolean
          (some (fn [p]
                  (and p
                       (not (contains? bot-set p))
                       (not (bots/bot? "organism" p))))
                players)))))

(defn load-observe-games
  "All human organism games, active and completed (the caller/UI splits them
   by :winner). Completed games carry the winner from their final history state."
  [db]
  (let [all-games (filter-ids
                   (db/query db :games
                             {:$or [{:transition-state "active"}
                                    {:transition-state {:$exists false}}]}))]
    (->> all-games
         (filter observe-worthy?)
         (map (fn [game]
                (let [{last-entry :state last-time :at} (game-head db game)]
                  (-> game
                      (select-keys [:key :invocation :created-by :game-type :bots :deletion])
                      (assoc :last-move-time last-time)
                      (assoc :current-player (get-in last-entry [:player-turn :player]))
                      (assoc :round (:round last-entry))
                      (assoc :winner (:winner last-entry))))))
         (sort-by #(- (or (:last-move-time %) 0))))))

(defn group-player-games
  [db player player-games]
  (reduce
   (fn [sections player-game]
     (let [game-key (:game player-game)
           record (find-published-game db game-key)
           canonical (:canonical record)
           state (:state canonical)
           history-count (game-history-count db game-key)
           player-game (cond-> (assoc player-game :history-count history-count)
                         canonical (assoc :status (if (:winner state) "complete" "active")
                                          :winner (:winner state) :round (:round state)
                                          :deletion (:deletion record)
                                          :current-player (get-in state [:player-turn :player])))
           status (:status player-game)
           unread? (if canonical
                     (< (or (:witness-revision player-game) -1) (:revision canonical))
                     (< (or (:witness player-game) 0) history-count))]
       (if (#{"active" "complete"} status)
         (update sections status conj
                 (cond-> player-game
                   (= "complete" status) (assoc :unread? unread?)))
         sections)))
   {"active" [] "complete" []}
   player-games))

(defn load-player-games
  ([db player]
   (load-player-games db player nil))
  ([db player game-type]
   (let [;; Untyped records are legacy organism (see the backfill migration) —
         ;; only organism inherits them, or journey/eridu/… would list them too.
         query (cond
                 (nil? game-type)          {}
                 (= "organism" game-type)  {"$or" [{:game-type game-type}
                                                   {:game-type {"$exists" false}}]}
                 :else                     {:game-type game-type})
         records (db/query db (player-games-key player) query)
         records (->> records
                     (map deserialize-player-game)
                     (filter (fn [{:keys [game]}]
                               (some? (find-published-game db game)))))
         states (group-player-games db player records)
         open (if game-type
                (filter #(= game-type (:game-type (:invocation %))) (load-open-games db))
                (load-open-games db))]
     (assoc states "open" open))))

(defn load-history
  ([db key] (load-history db key (:canonical (db/one db :games {:key key}))))
  ([db key canonical]
  (let [records (db/query db (history-key key) {})
        legacy (remove :mutation-revision records)
        mutations (cond-> (into {} (map (juxt :mutation-revision identity)
                                        (filter #(and (:mutation-revision %)
                                                      (<= (:mutation-revision %) (or (:revision canonical) -1)))
                                                records)))
                    canonical (assoc (:revision canonical)
                                     (assoc (:state canonical)
                                            :mutation-revision (:revision canonical)
                                            :history-index (:history-index canonical))))]
    (reduce (fn [history row]
              (conj (subvec history 0 (:history-index row))
                    (deserialize-state (dissoc row :mutation-revision :history-index :accepted-at))))
            (mapv (comp deserialize-state #(dissoc % :transition-id)) legacy)
            (map val (sort-by clojure.core/key mutations))))))

(defn load-chat
  [db key]
  (let [records (db/query db (chat-key key) {})]
    (mapv
     (fn [record]
       (select-keys record [:type :id :player :time :message :client-id]))
     records)))

(defn find-open-game
  "The open lobby under this key, or nil.

   The nil matters: threading a missing record through dissoc/assoc produced
   {:chat []}, which is truthy, so every `if-let` on this saw a lobby that was
   not there."
  [db key]
  (when-let [record (db/one db :open-games
                            {:key key :lifecycle {:$ne "retired"}})]
    (-> record
        (assoc :table-id (lobby-table-id record))
        (dissoc :_id)
        (assoc :chat (load-chat db key)))))

(defn load-game
  [db key]
  (locking (game-lock key)
  (let [published-query {:key key
                         :$or [{:transition-state "active"}
                               {:transition-state {:$exists false}}]}
        published (db/one db :games published-query)]
    (if-let [transition-id (:transition-id published)]
      (cleanup-published-transition! db key transition-id)
      (when-not published
        (recover-game-transition! db key)))
    (if-let [game-state (or published (db/one db :games published-query))]
    (let [history (load-history db key (:canonical game-state))
          chat (load-chat db key)]
      (-> game-state
          (assoc :incarnation-id (:_id game-state))
          (cond-> (:canonical game-state)
            (assoc :revision (get-in game-state [:canonical :revision])
                   :needs-finalization? (not= (:finalized-revision game-state)
                                               (get-in game-state [:canonical :revision]))
                   :accepted-command (get-in game-state [:canonical :receipt])))
          (assoc-in [:game :state] (last history))
          (assoc :history history)
          (assoc :chat chat)
          (update :bots set)
          (update-in [:game :players] conditional-string)
          (update-in [:game :adjacencies] read-string)
          (select-keys [:key :invocation :game :chat :history
                        :table-id :incarnation-id :created-by :game-type :bots :revision :accepted-command :needs-finalization?])))))))
