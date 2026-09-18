(ns organism.landing-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [organism.persist :as persist]
   [organism.routes.home :as home]))

(defn- landing [request]
  (:body (home/organism-home-page request)))

(deftest visitor-landing-offers-the-approved-play-dock
  (let [html (landing {})]
    (is (str/includes? html "class=\"organism-landing\""))
    (is (str/includes? html "Make your move."))
    (is (str/includes? html "Let’s play"))
    (is (str/includes? html "id=\"landing-account-dialog\""))
    (is (str/includes? html "href=\"/login?redirect=/organism/play\""))
    (is (str/includes? html "href=\"/register?redirect=/organism/play\""))
    (is (not (str/includes? html "href=\"/organism/create\"")))
    (is (not (str/includes? html "href=\"/logout\"")))))

(deftest signed-in-landing-uses-the-server-account
  (let [html (landing {:session {:player "Ada Lovelace"}})]
    (is (str/includes? html "Welcome back."))
    (is (str/includes? html "Ada Lovelace"))
    (is (str/includes? html "href=\"/player/Ada%20Lovelace/account\""))
    (doseq [path ["/organism/play" "/organism/create" "/organism/bots" "/logout"]]
      (is (str/includes? html (str "href=\"" path "\"")) path))
    (is (not (str/includes? html "id=\"landing-account-dialog\"")))
    (is (not (str/includes? html "Create account")))))

(deftest signed-in-landing-presents-games-and-tables-as-one-destination
  (let [html (landing {:session {:player "Ada Lovelace"}})]
    (is (str/includes? html "Games &amp; tables"))
    (is (str/includes? html "Find or join a table"))
    (is (not (str/includes? html ">Find a game<")))))

(deftest signed-in-landing-summarizes-active-games-and-pending-turns
  (with-redefs [persist/load-player-games
                (fn [_db _player _game-type]
                  {"active" [{:game "one" :current-player "ada lovelace"}
                              {:game "two" :current-player "Grace Hopper"}]})]
    (let [html (:body (home/organism-home-page
                       ::db
                       {:session {:player "Ada Lovelace"}}))]
      (is (str/includes? html "Games &amp; tables"))
      (is (str/includes? html "1 turn waiting · 2 active games")))))

(deftest signed-in-landing-keeps-joining-visible-when-no-turn-is-pending
  (with-redefs [persist/load-player-games
                (fn [_db _player _game-type]
                  {"active" [{:game "one" :current-player "Grace Hopper"}
                              {:game "two" :current-player "Katherine Johnson"}]})]
    (let [html (:body (home/organism-home-page
                       ::db
                       {:session {:player "Ada Lovelace"}}))]
      (is (str/includes? html "2 active games · continue or join another")))))

(deftest query-parameters-cannot-select-a-signed-in-landing
  (let [html (landing {:params {:player "admin" :state "player"}
                       :query-params {"player" "admin" "state" "player"}})]
    (is (str/includes? html "Let’s play"))
    (is (not (str/includes? html "/player/admin/account"))))
  (is (not (str/includes? (landing {:session {:player ""}}) "<span>Welcome back.</span>"))))

(deftest account-text-and-path-segments-are-escaped
  (let [html (landing {:session {:player "a\"><script>alert(1)</script>"}})]
    (is (not (str/includes? html "<script>alert(1)</script>")))
    (is (str/includes? html "&lt;script&gt;"))
    (is (str/includes? html "%3Cscript%3E")))
  (is (str/includes? (landing {:session {:player "ゲーム"}})
                    "/player/%E3%82%B2%E3%83%BC%E3%83%A0/account")))

(deftest artwork-and-creator-credits-remain-visible
  (doseq [request [{} {:session {:player "moss"}}]
          :let [html (landing request)]]
    (is (str/includes? html "src=\"/img/rulebook-cover-01.png\""))
    (is (str/includes? html "width=\"1316\" height=\"1316\""))
    (is (str/includes? html "Ryan Spangler"))
    (is (str/includes? html "Wyn Tiedmers"))
    (is (str/includes? html "href=\"/img/organism-rulebook.pdf\""))))

(deftest public-exploration-remains-reachable
  (doseq [request [{} {:session {:player "moss"}}]
          :let [html (landing request)]]
    (doseq [path ["/organism/observe" "/organism/players" "/organism/learn" "/organism/generate"]]
      (is (str/includes? html (str "href=\"" path "\"")) path))
    (is (str/includes? html "More to explore"))
    (is (str/includes? html "Review and create an all-bot game"))))

(deftest landing-loads-only-its-versioned-presentation-assets
  (let [html (landing {})]
    (is (str/includes? html "/css/landing.css?v=20260915-play-dock"))
    (is (str/includes? html "/js/landing.js?v=20260915-play-dock"))
    (is (not (str/includes? html "bulma.min.css")))
    (is (not (str/includes? html "/js/organism.js")))
    (is (not (str/includes? html "preview-state")))
    (is (not (str/includes? html "Design preview")))))
