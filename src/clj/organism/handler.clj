(ns organism.handler
  (:require
   [organism.mongo :as db]
   [organism.middleware :as middleware]
   [organism.layout :refer [error-page]]
   [organism.routes.home :refer [home-routes]]
   ;; [organism.routes.api :refer [api-routes]]
   [organism.routes.organism :refer [modern-api-routes organism-routes]]
   [organism.routes.journey :refer [journey-routes]]
   [organism.routes.journey-ws :refer [journey-ws-routes]]
   [organism.routes.journey-bots :refer [journey-bot-routes organism-bot-routes]]
   [organism.routes.oroboros :refer [oroboros-routes]]
   [organism.routes.oroboros-ws :refer [oroboros-ws-routes]]
   [organism.routes.eridu :refer [eridu-routes]]
   [organism.routes.eridu-ws :refer [eridu-ws-routes]]
   [organism.routes.future :refer [future-routes]]
   [organism.routes.future-ws :refer [future-ws-routes]]
   [organism.routes.websockets :refer [websocket-routes]]
   [organism.persist :as persist]
   [organism.config :refer [env]]
   [reitit.ring :as ring]
   [ring.util.response :as response]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.webjars :refer [wrap-webjars]]
   [organism.env :refer [defaults]]
   [mount.core :as mount]))

(mount/defstate init-app
  :start ((or (:init defaults) (fn [])))
  :stop  ((or (:stop defaults) (fn []))))

(defn mongo-config
  [config]
  {:host (or (:mongo-host config) "localhost")
   :port (let [port (or (:mongo-port config) 27017)]
           (if (string? port) (parse-long port) port))
   :database (or (:mongo-database config) "organism")})

;; Backward-compatible default used by standalone migration entry points.
(def mongo-connection (mongo-config {}))

(defn prepare-database!
  [database]
  (persist/ensure-player-identity-index! database)
  database)

(defn modern-client-entry
  [_]
  (if-let [index (response/resource-response "public/modern/index.html")]
    (-> index
        (response/content-type "text/html; charset=utf-8")
        (response/header "Cache-Control" "no-cache"))
    (-> (response/response "The game client has not been built. Run npm run build --prefix client.")
        (response/status 503)
        (response/content-type "text/plain; charset=utf-8")
        (response/header "Cache-Control" "no-store"))))

(defn health-response
  [database]
  (try
    (db/collections database)
    (-> (response/response "{\"status\":\"ok\"}")
        (response/content-type "application/json; charset=utf-8")
        (assoc-in [:headers "Cache-Control"] "no-store"))
    (catch Throwable _
      (-> (response/response "{\"status\":\"unavailable\"}")
          (response/status 503)
          (response/content-type "application/json; charset=utf-8")
          (assoc-in [:headers "Cache-Control"] "no-store")))))

(mount/defstate app-routes
  :start
  (ring/ring-handler
   (ring/router
    (let [db (prepare-database! (db/connect! (mongo-config env)))]
      [["/healthz" {:get (fn [_] (health-response db))}]
       ["/modern" {:get modern-client-entry}]
       ["/modern/" {:get modern-client-entry}]
       (home-routes db)
       (modern-api-routes db)
       (organism-routes db)
       (journey-routes db)
       (journey-bot-routes db)
       (organism-bot-routes db)
       (oroboros-routes db)
       
       (eridu-routes db)
       (future-routes db)
       (websocket-routes db)
       (journey-ws-routes db)
       (oroboros-ws-routes)
       (eridu-ws-routes db)
       (future-ws-routes)]))
   (ring/routes
    (ring/create-resource-handler
     {:path "/"})
    (wrap-content-type
     (wrap-webjars (constantly nil)))
    (ring/create-default-handler
     {:not-found
      (constantly (error-page {:status 404, :title "404 - Page not found"}))
      :method-not-allowed
      (constantly (error-page {:status 405, :title "405 - Not allowed"}))
      :not-acceptable
      (constantly (error-page {:status 406, :title "406 - Not acceptable"}))}))))

(defn app []
  (middleware/wrap-base #'app-routes))
