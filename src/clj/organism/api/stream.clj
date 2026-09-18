(ns organism.api.stream
  "Read-only ordinary JSON WebSocket snapshots. HTTP remains the mutation and
   catch-up transport. Each connection is scoped to its authenticated session."
  (:require [jsonista.core :as json]
            [org.httpkit.server :as hk]))

(defn callbacks [snapshot]
  (let [closed? (atom false)
        task (atom nil)
        send-snapshot! (fn [channel value]
                         (hk/send! channel (json/write-value-as-string value) false))]
    {:on-open
     (fn [channel]
       (let [initial (snapshot)]
         (send-snapshot! channel initial)
         (reset! task
                 (future
                   (try
                     (loop [previous initial]
                       (Thread/sleep 1000)
                       (when-not @closed?
                         (let [current (snapshot)]
                           (when (not= current previous) (send-snapshot! channel current))
                           (recur current))))
                     (catch InterruptedException _)
                     (catch Exception _
                       ;; No exception details or persistence fields cross the wire.
                       (when-not @closed?
                         (send-snapshot! channel {:type "snapshot.unavailable" :version 1}))))))
         (when @closed? (future-cancel @task))))
     :on-close (fn [_ _]
                 (reset! closed? true)
                 (when-let [running @task] (future-cancel running)))
     ;; There is deliberately no client message dispatcher on this transport.
     :on-receive (fn [_ _] nil)}))
