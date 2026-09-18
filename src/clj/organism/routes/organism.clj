(ns organism.routes.organism
  (:require
   [clojure.string :as str]
   [organism.api.commands :as commands]
   [organism.api.events :as events]
   [organism.api.projection :as projection]
   [organism.api.stream :as stream]
   [org.httpkit.server :as hk]
   [organism.board :as board]
   [organism.bots :as bots]
   [organism.config :refer [env]]
   [organism.game :as game]
   [organism.layout :as layout]
   [organism.leaderboard :as leaderboard]
   [organism.lobby :as lobby]
   [organism.persist :as persist]
   [organism.middleware :as middleware]
   [organism.mutations :as mutations]
   [organism.routes.organism-bot :as bot]
   [organism.routes.shared :as shared]
   [organism.routes.websockets :as ws]
   [ring.util.response :as response]))

(defn modern-play-page
  "Enter the modern same-origin client while preserving the session cookie."
  [request]
  (let [game-key (get-in request [:path-params :play])
        encoded (-> (java.net.URLEncoder/encode game-key "UTF-8")
                    (str/replace "+" "%20"))]
    (response/redirect (str "/modern/?game=" encoded))))

(defn client-page
  "Default to React; retain a UI-only rollback on the same durable backend."
  [legacy-handler request]
  (if (or (= "legacy" (:organism-client env))
          (= "legacy" (get-in request [:query-params "client"])))
    (legacy-handler request)
    (if (get-in request [:path-params :play])
      (modern-play-page request)
      (response/redirect "/modern/?view=create"))))

(defn- load-modern-game
  [db game-key viewer]
  (or (ws/recover-modern-game! db game-key)
      (when-let [open (persist/find-open-game db game-key)]
        (assoc open :key game-key :game nil :history []))))

(defn modern-session-response
  [db request]
  (let [session-player (get-in request [:session :player])
        player (when session-player (persist/canonical-player-name db session-player))]
    (response/response
      (projection/json-safe
        {:player player
         :defaults (assoc (board/empty-invocation (or player ""))
                          :visibility "open"
                          :colors (vec (board/generate-colors (take 7 board/total-rings))))
         :limits {:playerCounts (vec (range 1 11))
                  :ringCounts (vec (range 3 8))
                  :gameNameMaxLength 120
                  :chatMaxLength 1000}
         :bots (bots/list-bots "organism")}))))

(defn game-projection-response
  "Return a recipient-safe, read-only snapshot for the modern client."
  [db request]
  (let [game-key (get-in request [:path-params :play])
        viewer (get-in request [:session :player])
        query (:query-params request)
        raw-after (get query "afterRevision" (get query :afterRevision))
        after-revision (when (some? raw-after)
                         (if (integer? raw-after)
                           raw-after
                           (parse-long raw-after)))]
    (if-let [game-state (load-modern-game db game-key viewer)]
      (cond
        (and (some? raw-after) (nil? after-revision))
        (response/bad-request {:error "after-revision-invalid"})

        (some? after-revision)
        (response/response (events/catch-up game-state viewer after-revision))

        :else
        (response/response (projection/project-game game-state viewer)))
      (response/not-found {:error "game-not-found"
                           :gameId game-key}))))

(defn- error-response
  [{:keys [http-status] :as result}]
  (-> (response/response (dissoc result :status :http-status))
      (response/status http-status)))

(defn game-command-response
  [db request]
  (let [game-key (get-in request [:path-params :play])
        player (get-in request [:session :player])
        body (or (:body-params request) (:params request) {})
        result (mutations/execute! db game-key player body
                                   #(commands/execute-command % player body))]
    (if (= :accepted (:status result))
      (response/response (projection/project-game (:game-state result) player))
      (error-response result))))

(defn game-history-response [db request]
  (let [key (get-in request [:path-params :play])
        cursor (parse-long (get-in request [:path-params :cursor]))
        viewer (get-in request [:session :player])]
    (if-let [state (persist/load-game db key)]
      (if (and cursor (<= 0 cursor) (< cursor (count (:history state))))
        (response/response
         (assoc (projection/project-game
                 (-> state (assoc :replay? true)
                     (assoc-in [:game :state] (nth (:history state) cursor))) viewer)
                :historyCursor cursor))
        (response/bad-request {:error "history-cursor-invalid"}))
      (response/not-found {:error "game-not-found"}))))

(defn- with-table-instance!
  "Fence modern writes before any lobby claim, retaining the lifecycle lock
   across authorization, persistence and publication. Never lock the registry
   before entering this boundary. Omitted fields allow first creation/old clients."
  [db key body write]
  (locking (mutations/game-lock key)
    (let [state (or (persist/load-game db key) (persist/find-open-game db key))
          create-only (mutations/field body :createOnly)]
      (cond
        (and (or (contains? body :createOnly) (contains? body "createOnly"))
             (not (boolean? create-only)))
        (error-response (mutations/reject 400 "create-only-must-be-boolean"))

        (and create-only state)
        (error-response (mutations/reject 409 "game-already-exists"))

        :else
        (if-let [problem (mutations/instance-problem state body)]
          (error-response problem)
          (write))))))

(defn game-chat-response [db request]
  (let [key (get-in request [:path-params :play])
        player (get-in request [:session :player])
        body (:body-params request)
        client-id (mutations/field body :clientId)]
    (cond
      (nil? player) (error-response (mutations/reject 401 "authentication-required"))
      (or (not (string? client-id)) (str/blank? client-id))
      (response/bad-request {:error "client-id-required"})
      :else
      (with-table-instance! db key body
       (fn []
      (if (load-modern-game db key player)
          (let [result (ws/update-chat db player key nil
                                       {:message (mutations/field body :message) :client-id client-id})]
            (if (:error result)
              (-> (response/response result)
                  (response/status (if (= "only players in this game can post messages" (:error result)) 403 409)))
              (response/response result)))
          (response/not-found {:error "game-not-found"})))))))

(defn- wrap-private-api [handler]
  (fn [request]
    (-> (handler request)
        (response/header "Cache-Control" "private, no-store")
        (response/header "Vary" "Cookie"))))

(defn- wrap-api-errors [handler]
  (fn [request]
    (try (handler request)
         (catch Exception _
           ;; Acceptance can be ambiguous. Clients retain the exact command ID
           ;; and retry; never expose database details or credentials.
           (-> (response/response {:error "temporarily-unavailable"})
               (response/status 503))))))

(defn game-events-response [db request]
  (let [key (get-in request [:path-params :play])
        viewer (get-in request [:session :player])]
    (if (:websocket? request)
      (hk/as-channel request
                     (stream/callbacks
                      #(if-let [state (load-modern-game db key viewer)]
                         (events/snapshot state viewer)
                         {:type "game.deleted" :version events/version :gameId key})))
      (-> (response/response {:error "websocket-required"}) (response/status 426)))))

(defn lobby-command-response
  "Thin JSON adapters. The existing fenced lobby services remain authoritative."
  [db operation request]
  (let [key (get-in request [:path-params :play])
        session-player (get-in request [:session :player])
        player (when session-player (persist/canonical-player-name db session-player))
        body (:body-params request)
        field #(mutations/field body %)]
    (cond
      (nil? session-player) (error-response (mutations/reject 401 "authentication-required"))
      (nil? player) (error-response (mutations/reject 403 "registered-account-required"))
      (and (= operation :ready) (not (boolean? (field :ready))))
      (response/bad-request {:error "ready-must-be-boolean"})
      (and (= operation :configure) (not (map? (field :invocation))))
      (response/bad-request {:error "invocation-required"})
      :else
      (with-table-instance! db key body
       (fn []
      (let [result
            (case operation
              :configure
              (let [submitted (field :invocation)
                    invocation (into {} (map (fn [k] [k (mutations/field submitted k)])
                                             [:player-count :ring-count :players :description
                                              :visibility :lobby-password]))]
                (ws/update-create-game db player key nil {:invocation invocation}))
              :join (ws/join-open-game! db key (field :index) player (field :password))
              :ready (ws/set-ready! db player key (field :ready) nil)
              :start (ws/trigger-creation db player key nil {})
              :seat (ws/set-slot! db player key (field :index) (field :player))
              :kick (ws/kick-player! db player key (field :index) nil))
            ;; A fenced launch may have committed before publication failed.
            ;; load-game performs the established transition recovery.
            state (load-modern-game db key player)]
        (if (and (:error result) (not (and (= :start operation) (:game state))))
          (-> (response/response (select-keys result [:error])) (response/status 409))
          (if state (response/response (projection/project-game state player))
              (response/not-found {:error "game-not-found"})))))))))

(defn modern-api-routes
  [db]
  ["/api/v1/organism"
   {:middleware [wrap-private-api middleware/wrap-require-origin
                 middleware/wrap-formats wrap-api-errors]}
   ["/session" {:get (partial modern-session-response db)}]
   ["/games/:play" {:get (partial game-projection-response db)}]
   ["/games/:play/history/:cursor" {:get (partial game-history-response db)}]
   ["/games/:play/events" {:get (partial game-events-response db)}]
   ["/games/:play/chat" {:post (partial game-chat-response db)}]
   ["/lobbies/:play" {:get (partial game-projection-response db)
                       :post (partial lobby-command-response db :configure)}]
   ["/lobbies/:play/join" {:post (partial lobby-command-response db :join)}]
   ["/lobbies/:play/ready" {:post (partial lobby-command-response db :ready)}]
   ["/lobbies/:play/start" {:post (partial lobby-command-response db :start)}]
   ["/lobbies/:play/seat" {:post (partial lobby-command-response db :seat)}]
   ["/lobbies/:play/kick" {:post (partial lobby-command-response db :kick)}]
   ["/games/:play/commands" {:post (partial game-command-response db)}]])

;; ── Learn page clips ─────────────────────────────────────────────────────

(def action-clips
  "The short silent loops on the learn page, in teaching order: the four things
   an element can do, then what happens between players, then how you win."
  [{:file "clip_eat.mp4"        :title "eat"
    :caption "take food into your organism"}
   {:file "clip_move.mp4"       :title "move"
    :caption "move a fed/mobile element to an open space"}
   {:file "clip_grow.mp4"       :title "grow"
    :caption "spend food to make a new element"}
   {:file "clip_circulate.mp4"  :title "circulate"
    :caption "send half the food from one element to another"}
   {:file "clip_conflict.mp4"   :title "conflict"
    :caption "elements of different players conflict"}
   {:file "clip_perish.mp4"     :title "perish"
    :caption "if an organism is missing any element type, it perishes"}
   {:file "clip_power.mp4"      :title "power"
    :caption "for each turn you held the center, and each element you unravel"}
   {:file "clip_two_org.mp4"    :title "two organisms"
    :caption "each organism you control gets its own turn"}
   {:file "clip_three_org.mp4"  :title "three organisms"
    :caption "if you ever have three living organisms at any time on your turn you win (!)"}])

;; ── Game spec ────────────────────────────────────────────────────────────

(def organism-spec
  {:game-type        "organism"
   :title            "ORGANISM"
   :template-prefix  "organism"
   :home-path        "/organism"
   :create-path      "/organism/create"
   :play-path        "/organism/play"
   :ws-prefix        "/ws/organism/play/"
   :load-observe     persist/load-observe-games
   :load-player-stats leaderboard/player-stats
   ;; After a real deletion, forget the game in the ws registry and tell any
   ;; tab still sitting on it.
   :on-delete        ws/drop-game!
   :learn-params     {:clips (mapv #(assoc % :poster (str/replace (:file %) #"\.mp4$" ".jpg"))
                                  action-clips)}})

;; ── Page handlers ─────────────────────────────────────────────────────────

(defn play-list-page
  "Show the logged-in player's games — reuses the player page."
  [db request]
  (let [player (get-in request [:session :player])
        preferences (persist/find-player-preferences db player)
        player-games (persist/load-player-games db player "organism")]
    (layout/render
     request
     "organism/player.html"
     {:player player
      :session-player player
      :preferences preferences
      :player-games (pr-str player-games)})))

(defn create-page
  [db request]
  (let [player (get-in request [:session :player])
        play-key (-> request :path-params :play)
        preferences (persist/find-player-preferences db player)
        open-game (when play-key (persist/find-open-game db play-key))
        game-type (or (get-in open-game [:invocation :game-type]) "organism")
        bot? (partial bots/bot? game-type)
        visible-invocation (when open-game
                             (lobby/visible-invocation (:visibility open-game)
                                                       (:invocation open-game)
                                                       player
                                                       bot?))]
    (layout/render
     request
     "organism/create.html"
     {:session-player player
      :preferences preferences
      :play-key (or play-key "")
      :lobby-creator (if open-game
                       (lobby/visible-creator (:visibility open-game)
                                              (or (:created-by open-game)
                                                  (first (get-in open-game [:invocation :players])))
                                              (get-in open-game [:invocation :players])
                                              player
                                              bot?)
                       player)
      :open-invocation (if visible-invocation (pr-str visible-invocation) "")})))

(defn play-page
  [db request]
  (let [play-key (-> request :path-params :play)
        player-key (get-in request [:session :player])
        preferences (persist/find-player-preferences db player-key)]
    (layout/render
     request
     "organism/play.html"
     {:player player-key
      :play play-key
      :preferences preferences})))

(defn player-page
  [db request]
  (let [player-key (-> request :path-params :player)
        preferences (persist/find-player-preferences db player-key)
        player-games (persist/load-player-games db player-key "organism")]
    (layout/render
     request
     "organism/player.html"
     {:player player-key
      ;; Anyone can view anyone's list — the viewer is who decides whether the
      ;; delete controls render.
      :session-player (get-in request [:session :player])
      :preferences preferences
      :player-games (pr-str player-games)})))

(defn join-game!
  "Take a seat in an open lobby straight from the games list, without a trip
   through the create page. Joining never begins play; the owner starts after
   the complete table has readied up."
  [db request]
  (let [player (get-in request [:session :player])
        game-key (-> request :path-params :play)
        params (or (:body-params request) (:params request))
        raw (get params :index (get params "index"))
        index (if (string? raw) (parse-long raw) raw)
        password (get params :password (get params "password"))
        result (ws/join-open-game! db game-key index player password)]
    (if (:error result)
      (response/bad-request result)
      (response/response
       {:joined game-key
        :begun (boolean (:begun? result))
        :lobby (:lobby result)
        :players (vec (get-in result [:invocation :players]))}))))

;; ── Generate page (all-bot game) ─────────────────────────────────────────

(def ^:private generate-bot-names
  ["oroboros" "helios" "selene" "atlas" "aurora"])

(def ^:private generate-words
  ["solar" "lunar" "stellar" "cosmic" "astral" "void" "nebula" "nova"
   "drift" "pulse" "ember" "spark" "flame" "frost" "tide" "storm"
   "crystal" "prism" "cipher" "rune" "glyph" "sigil" "nexus" "apex"])

(defn- generate-game-name []
  (str "generate-" (str/join "-" (repeatedly 3 #(rand-nth generate-words)))))

(defn- make-generate-invocation
  "Build an invocation suitable for an all-bot generated game."
  [players]
  (let [n (count players)
        ring-count 6
        colors (board/generate-colors-buffer board/total-rings ring-count n)
        captures (vec (repeat n board/default-player-captures))]
    {:ring-count ring-count
     :player-count n
     :players (vec players)
     :player-captures captures
     :organism-victory 3
     :mutations {}
     :colors colors
     :description "auto-generated bot game"
     :game-type "organism"}))

(defn generate-page
  "Explain the bot sandbox before creating anything."
  [request]
  (layout/render request "organism/generate.html" {}))

(defn generate-game!
  "Create an all-bot organism game, render the play page immediately, and
   start the bot turns in the background so observers can watch it unfold."
  [db request]
  (let [players generate-bot-names
        bot-set (set players)
        game-key (generate-game-name)
        player-key (get-in request [:session :player])
        invocation (make-generate-invocation players)
        base-state {:key game-key
                    :invocation invocation
                    :game nil
                    :chat []
                    :history []
                    :channels #{}}
        complete (ws/complete-game-state base-state)
        initial-state (assoc complete :bots bot-set :table-id (str (java.util.UUID/randomUUID)))]
    (locking (mutations/game-lock game-key)
    ;; Persist initial game so the play page can load it
    (persist/create-game! db (assoc (dissoc initial-state :channels)
                                    :created-by (or player-key "system")
                                    :game-type "organism"))
    (swap! ws/games assoc-in [:games game-key] initial-state)
    ;; Run the bot in the background. Each turn the bot broadcasts the full
    ;; resulting state (like journey) so observers render it directly. We do
    ;; NOT send choice keys for client-side replay: an all-bot game streams
    ;; turns continuously, and any replay divergence sends the client's
    ;; find-next-choices into a spin that freezes the tab.
    (bot/run-bot-turns!
     ws/games game-key 200 nil nil db))
    (client-page
     #(layout/render % "organism/play.html"
                     {:player (or player-key "") :play game-key :preferences "{}"})
     (assoc-in request [:path-params :play] game-key))))

;; ── Routes ────────────────────────────────────────────────────────────────

(defn organism-routes
  [db]
  ["/organism"
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/create"        {:get (partial client-page (partial create-page db))
                      :middleware [shared/require-auth]}]
   ["/create/:play"  {:get (partial client-page (partial create-page db))
                      :middleware [shared/require-auth]}]
   ["/create/:play/" {:get (partial client-page (partial create-page db))
                      :middleware [shared/require-auth]}]
   ["/play"          {:get (partial play-list-page db)
                      :middleware [shared/require-auth]}]
   ["/play/:play"    {:get (partial client-page (partial play-page db))}]
   ["/play/:play/"   {:get (partial client-page (partial play-page db))}]
   ["/modern/:play"  {:get modern-play-page}]
   ["/modern/:play/" {:get modern-play-page}]
   ["/play/:play/join"   {:post (partial join-game! db)
                          :middleware [shared/require-auth]}]
   ["/play/:play/delete" {:post (partial shared/delete-game! organism-spec db)
                          :middleware [shared/require-auth]}]
   ["/play/:play/keep"   {:post (partial shared/keep-game! db)
                          :middleware [shared/require-auth]}]
   ["/observe"       {:get (partial shared/observe-page organism-spec db)}]
   ["/players"       {:get (partial shared/players-page organism-spec db)}]
   ["/learn"         {:get (partial shared/learn-page organism-spec)}]
   ["/generate"      {:get generate-page
                       :post (partial generate-game! db)}]
   ["/player/:player"  {:get (partial player-page db)}]
   ["/player/:player/" {:get (partial player-page db)}]])
