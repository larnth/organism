(ns organism.routes.home
  (:require
   [clojure.string :as str]
   [organism.board :as board]
   [organism.layout :as layout]
   [organism.choice :as choice]
   [organism.persist :as persist]
   [organism.examples :as examples]
   [organism.routes.shared :as shared]
   [hiccup.core :as up]
   [organism.middleware :as middleware]
   [buddy.hashers :as hashers]
   [ring.util.response :as response]
   [ring.util.http-response :as http-response]))

(def home-game
  (atom {}))

(def all-rings
  ["A" "B" "C" "D" "E" "F" "G"])

(defn empty-game
  [starting-game]
  {:colors (board/generate-colors all-rings)
   :games (choice/random-walk starting-game)})

(defn require-player-auth
  "Guard a player's own pages.

   Someone logged out is sent to login and returned here afterward, the way
   `shared/require-auth` does it. Someone logged in as a *different* player is
   sent home instead — carrying the redirect there would bounce them straight
   back to a page they still can't see."
  [handler]
  (fn [request]
    (let [player (-> request :path-params :player)
          session-player (get-in request [:session :player])]
      (cond
        (= (persist/player-identity-key session-player)
           (persist/player-identity-key player)) (handler request)
        (nil? session-player)     (response/redirect (str "/login?redirect=" (:uri request)))
        :else                     (response/redirect "/")))))

(defn catalog-page
  [request]
  (layout/render request "home.html" {}))

(defn root-redirect
  "Root goes straight to the ORGANISM landing page. The multi-game catalog is
   no longer surfaced in the frontend; the other games remain reachable by
   direct URL (e.g. /journey)."
  [_request]
  (response/redirect "/organism"))

(defn- game-count-label
  [count singular plural]
  (str count " " (if (= 1 count) singular plural)))

(defn- games-action-detail
  [player active-games]
  (let [active-count (count active-games)
        player-key (persist/player-identity-key player)
        pending-count (count (filter #(= player-key
                                        (persist/player-identity-key (:current-player %)))
                                     active-games))]
    (cond
      (pos? pending-count)
      (str (game-count-label pending-count "turn waiting" "turns waiting")
           " · "
           (game-count-label active-count "active game" "active games"))

      (pos? active-count)
      (str (game-count-label active-count "active game" "active games")
           " · continue or join another")

      :else
      "Find or join a table")))

(defn organism-home-page
  ([request]
   (organism-home-page nil request))
  ([db request]
   (let [player (get-in request [:session :player])
         player (when (and (string? player) (not (str/blank? player))) player)
         encoded (when player
                   (-> (java.net.URLEncoder/encode player "UTF-8")
                       (str/replace "+" "%20")))
         player-games (when (and db player)
                        (persist/load-player-games db player "organism"))
         active-games (vec (get player-games "active" []))]
     (layout/render
      request
      "organism/home.html"
      {:session-player player
       :account-url (when player (str "/player/" encoded "/account"))
       :player-initial (when player (str/upper-case (subs player 0 1)))
       :games-action-detail (when player
                              (games-action-detail player active-games))}))))

(defn learn-page
  [request]
  (layout/render request "learn.html" {:session-player (get-in request [:session :player])}))

(defn safe-redirect
  "Only allow local paths, including after browser URL normalization."
  [redirect _player]
  (if (and redirect
           (string? redirect)
           (clojure.string/starts-with? redirect "/")
           (not (clojure.string/starts-with? redirect "//"))
           (not (re-find #"[\\\u0000-\u001f\u007f]" redirect)))
    redirect
    "/"))

(defn- game-title
  "Derive a display title from the redirect path."
  [redirect]
  (cond
    (and redirect (clojure.string/starts-with? redirect "/journey"))  "JOURNEY"
    (and redirect (clojure.string/starts-with? redirect "/oroboros")) "UNIVERSAL"
    :else "ORGANISM"))

(defn- lobby-return?
  [redirect]
  (and (string? redirect)
       (or (str/starts-with? redirect "/organism/create/")
           (str/starts-with? redirect "/organism/play/"))))

(defn login-page
  [request]
  (let [redirect (get-in request [:query-params "redirect"])]
    (layout/render request "login.html"
                   {:redirect redirect
                    :lobby-return (lobby-return? redirect)
                    :game-title (game-title redirect)})))

(defn register-url
  "Link to registration carrying the name they typed and where they were headed,
   so \"register instead?\" lands on a form that is already filled in."
  [player redirect]
  (let [encode #(java.net.URLEncoder/encode (str %) "UTF-8")
        params (cond-> []
                 (not (str/blank? player))   (conj (str "player=" (encode player)))
                 (not (str/blank? redirect)) (conj (str "redirect=" (encode redirect))))]
    (if (seq params)
      (str "/register?" (str/join "&" params))
      "/register")))

(def ^:private registration-policy
  {:player-max-length 32
   ;; This Unicode pattern works in Java and HTML/JavaScript pattern syntax.
   :player-pattern "[\\p{L}\\p{N}][\\p{L}\\p{N} _.\\-]{0,31}"
   :password-min-length 12
   :password-max-length 200})

(defn valid-player-name?
  [player]
  (boolean
   (and (string? player)
        (re-matches (re-pattern (:player-pattern registration-policy)) player))))

(defn valid-registration-password?
  [password]
  (and (string? password)
       (<= (:password-min-length registration-policy)
           (count password)
           (:password-max-length registration-policy))))

(defonce ^:private dummy-password-hash
  (hashers/derive "public-login-timing-placeholder"))

(defn login-submit
  [db request]
  (let [params (:params request)
        player (:player params)
        password (:password params)
        redirect (safe-redirect (:redirect params) player)
        stored-hash (persist/find-player-password db player)
        password-valid? (hashers/check (or password "")
                                       (or stored-hash dummy-password-hash))
        fail (fn [error extra]
               (layout/render request "login.html"
                              (merge {:error error
                                      :player player
                                      :redirect (:redirect params)
                                      :lobby-return (lobby-return? (:redirect params))
                                      :game-title (game-title (:redirect params))}
                                     extra)))]
    (cond
      (and stored-hash password-valid?)
      (let [canonical-player (persist/canonical-player-name db player)]
        (-> (response/redirect redirect)
            (assoc :session {:player canonical-player})))

      :else
      (fail "Invalid player name or password" nil))))

(defn- render-registration
  [request {:keys [player redirect error error-field]}]
  (let [redirect (safe-redirect redirect player)
        title (game-title redirect)]
    (-> (layout/render
         request
         (if (= title "ORGANISM") "organism/register.html" "register.html")
         {:player (when (string? player) player)
          :redirect redirect
          :game-title title
          :registration-policy registration-policy
          :login-url (str "/login?redirect=" (java.net.URLEncoder/encode redirect "UTF-8"))
          :error error
          :error-field error-field})
        (assoc-in [:headers "Cache-Control"] "no-store"))))

(defn register-page
  [request]
  (render-registration request {:redirect (get-in request [:query-params "redirect"])
                                :player (get-in request [:query-params "player"])}))

(defn register-submit
  [db request]
  (let [params (:params request)
        player (:player params)
        password (:password params)
        password-confirm (:password-confirm params)
        redirect (safe-redirect (:redirect params) player)
        fail (fn [error field]
               (render-registration request {:player player
                                             :redirect redirect
                                             :error error
                                             :error-field field}))]
    (cond
      (or (not (string? player)) (str/blank? player))
      (fail "Choose a player name" "player")

      (not (valid-player-name? player))
      (fail "Player names must be 1–32 characters, starting with a letter or number; spaces, dots, underscores, and hyphens are also allowed"
            "player")

      (or (not (string? password)) (str/blank? password))
      (fail "Choose a password, not only spaces" "password")

      (not (valid-registration-password? password))
      (fail (str "Passwords must be between " (:password-min-length registration-policy)
                 " and " (:password-max-length registration-policy) " characters")
            "password")

      (not= password password-confirm)
      (fail "Passwords do not match" "password-confirm")

      (persist/player-has-password? db player)
      (fail "That player name is already taken" "player")

      :else
      (let [hashed (hashers/derive password)
            claimed? (persist/claim-player-name!
                      db player hashed {:color (board/random-color 0.4 0.8)})]
        (if claimed?
          (let [canonical-player (persist/canonical-player-name db player)]
            (-> (response/redirect redirect)
                (assoc :session {:player canonical-player})))
          (fail "That player name is already taken" "player"))))))

(defn logout
  [request]
  (-> (response/redirect "/")
      (assoc :session nil)))

(defn player-page
  [db request]
  (let [requested-player (-> request :path-params :player)
        player-key (or (persist/canonical-player-name db requested-player)
                       requested-player)
        preferences (persist/find-player-preferences db player-key)
        player-games (persist/load-player-games db player-key)]
    (layout/render
     request
     "player.html"
     {:player player-key
      :preferences preferences
      :player-games (pr-str player-games)})))

(defn eternal-page [request]
  (http-response/content-type
   (http-response/ok
    (do
      (if (empty? (deref home-game))
        (reset! home-game (empty-game (examples/six-player-game))))
      (let [home (deref home-game)
            {:keys [games colors]} home
            game (first games)
            board (board/build-board 6 50 2.1 colors all-rings (:turn-order game) true)]
        (swap! home-game update :games rest)
        (up/html (board/render-game board game)))))
   "text/html; charset=utf-8"))

(defn apply-player-preferences
  [db request]
  (let [requested-player (-> request :path-params :player)
        player (or (persist/canonical-player-name db requested-player)
                   requested-player)]
    (persist/update-player-preferences! db player (select-keys (:params request) [:color]))
    (response/response {:ok true :status :success})))

(defn account-page
  [db request]
  (let [requested-player (-> request :path-params :player)
        player (or (persist/canonical-player-name db requested-player)
                   requested-player)
        preferences (persist/find-player-preferences db player)
        color (or (:color preferences) "#888888")
        encoded-player (-> (java.net.URLEncoder/encode player "UTF-8")
                           (str/replace "+" "%20"))]
    (layout/render
     request
     "account.html"
     {:player player
      :encoded-player encoded-player
      :color color})))

(defn account-submit
  [db request]
  (let [requested-player (-> request :path-params :player)
        player (or (persist/canonical-player-name db requested-player)
                   requested-player)]
    (persist/update-player-preferences! db player (select-keys (:params request) [:color]))
    (response/response {:ok true})))

(defn home-routes
  [db]
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/" {:get root-redirect}]
   ["/api/search-players" {:get (partial shared/search-players db)}]
   ["/organism" {:get (partial organism-home-page db)}]
   ["/eternal" {:get eternal-page}]
   ["/login" {:get login-page
              :post (partial login-submit db)}]
   ["/register" {:get register-page
                 :post (partial register-submit db)}]
   ["/logout" {:get logout}]
   ["/learn" {:get learn-page}]
   ["/player/:player" {:get (partial player-page db)}]
   ["/player/:player/" {:get (partial player-page db)}]
   ["/player/:player/account" {:get (partial account-page db)
                               :post (partial account-submit db)
                               :middleware [require-player-auth]}]
   ["/player/:player/preferences" {:post (partial apply-player-preferences db)
                                   :middleware [require-player-auth]}]])
