(ns organism.middleware
  (:require
    [organism.env :refer [defaults]]
    [clojure.tools.logging :as log]
    [organism.layout :refer [error-page]]
    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
    [organism.middleware.formats :as formats]
    [organism.middleware.range :refer [wrap-range]]
    [muuntaja.middleware :refer [wrap-format wrap-params]]
    [organism.config :refer [env]]
    [ring.middleware.flash :refer [wrap-flash]]
    [ring.middleware.session :refer [wrap-session]]
    [ring.middleware.session.cookie :refer [cookie-store]]
    [ring.middleware.defaults :refer [site-defaults wrap-defaults]])
  (:import
   [java.net URI]
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]
   [java.util Arrays])
  )

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (log/error t (.getMessage t))
        (error-page {:status 500
                     :title "Something very bad has happened!"
                     :message "We've dispatched a team of highly trained gnomes to take care of the problem."})))))

(defn wrap-csrf [handler]
  (wrap-anti-forgery
    handler
    {:error-response
     (error-page
       {:status 403
        :title "Invalid anti-forgery token"})}))


(defn wrap-formats [handler]
  (let [wrapped (-> handler wrap-params (wrap-format formats/instance))]
    (fn [request]
      ;; disable wrap-formats for websockets
      ;; since they're not compatible with this middleware
      ((if (:websocket? request)
         handler
         wrapped)
       request))))

(defn validate-production-config!
  [config]
  (when (:prod config)
    (let [secret (:session-secret config)
          public-origin (:public-origin config)]
      (when-not (and (string? secret) (>= (count secret) 32))
        (throw (ex-info "SESSION_SECRET must contain at least 32 characters in production" {})))
      (when-not (and (string? public-origin)
                     (re-matches #"https://[^/]+" public-origin))
        (throw (ex-info "PUBLIC_ORIGIN must be one HTTPS origin in production" {})))))
  config)

(defn- cookie-key
  [config]
  (let [secret (or (:session-secret config) "organism-development-session-secret")
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes secret StandardCharsets/UTF_8))]
    (Arrays/copyOf digest 16)))

(defn session-store-for
  [config]
  (validate-production-config! (assoc config :public-origin
                                      (or (:public-origin config)
                                          (when-not (:prod config) "http://localhost"))))
  (cookie-store {:key (cookie-key config)}))

(defn session-cookie-attrs
  [config]
  {:http-only true
   :max-age (* 60 60 24 30)
   :same-site :lax
   :secure (boolean (:prod config))})

(defn- websocket-request?
  [request]
  (= "websocket" (some-> (get-in request [:headers "upgrade"]) clojure.string/lower-case)))

(defn wrap-require-origin
  ([handler]
   (wrap-require-origin handler env))
  ([handler config]
   (validate-production-config! config)
   (fn [request]
     (let [protected? (or (#{:post :put :patch :delete} (:request-method request))
                          (websocket-request? request))
           origin (get-in request [:headers "origin"])]
       (if (and (:prod config)
                protected?
                (not= (:public-origin config) origin))
         {:status 403
          :headers {"Content-Type" "application/json; charset=utf-8"
                    "Cache-Control" "no-store"}
          :body "{\"error\":\"origin-not-allowed\"}"}
         (handler request))))))

(defn wrap-public-origin
  ([handler]
   (wrap-public-origin handler env))
  ([handler config]
   (if-not (:prod config)
     handler
     (let [origin (URI. (:public-origin (validate-production-config! config)))
           scheme (keyword (.getScheme origin))
           host (.getHost origin)
           port (let [configured (.getPort origin)]
                  (if (neg? configured)
                    (if (= :https scheme) 443 80)
                    configured))]
       (fn [request]
         (handler (-> request
                      (assoc :scheme scheme
                             :server-name host
                             :server-port port)
                      (assoc-in [:headers "host"] host))))))))

(defn wrap-base [handler]
  (validate-production-config! env)
  (-> ((:middleware defaults) handler)
      wrap-flash
      (wrap-session {:store (session-store-for env)
                     :cookie-attrs (session-cookie-attrs env)})
      (wrap-defaults
        (-> site-defaults
            (assoc-in [:security :anti-forgery] false)
            (dissoc :session)))
      ;; Outside wrap-defaults on purpose: site-defaults' :static is what
      ;; actually serves resources/public, so this is the only layer that sees
      ;; those responses. It only touches file-backed bodies, so pages fall
      ;; through untouched.
      wrap-range
      wrap-public-origin
      wrap-internal-error))
