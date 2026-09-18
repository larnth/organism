(ns organism.websockets
  (:require
   [cognitect.transit :as t]))

(defonce ws-channel (atom nil))
(defonce ^:private reconnect-timer (atom nil))
(defonce ^:private intentionally-closed? (atom false))
(defonce ^:private connection-generation (atom 0))
(def json-reader (t/reader :json))
(def json-writer (t/writer :json))

(defn receive-transit-message!
  [update-fn]
  (fn [raw]
    (let [message (->> raw .-data (t/read json-reader))]
      (update-fn message))))

(defn send-transit-message!
  [message]
  (if (and @ws-channel
           (= (.-readyState @ws-channel) js/WebSocket.OPEN))
    (try
      (.send @ws-channel (t/write json-writer message))
      (catch js/Object e
        (println "websocket send failed")))
    (throw (js/Error. "websocket not connected; reconnecting"))))

(defn try-send-transit-message!
  "Send only through an open socket and report whether the browser accepted it."
  [message]
  (if (and @ws-channel
           (= (.-readyState @ws-channel) js/WebSocket.OPEN))
    (try
      (.send @ws-channel (t/write json-writer message))
      true
      (catch js/Object e
        (println "websocket send failed")
        false))
    false))

(defn- cancel-reconnect-timer!
  []
  (when-let [timer @reconnect-timer]
    (js/clearTimeout timer)
    (reset! reconnect-timer nil)))

(declare connect-websocket!)

(defn- schedule-websocket-reconnect!
  [url receive-handler on-open attempt generation]
  (when (and (not @intentionally-closed?)
             (= generation @connection-generation))
    (cancel-reconnect-timer!)
    (let [delay (min 10000 (* 500 (js/Math.pow 2 attempt)))]
      (reset! reconnect-timer
              (js/setTimeout
               (fn []
                 (reset! reconnect-timer nil)
                 (when (= generation @connection-generation)
                   (connect-websocket! url receive-handler on-open
                                       (inc attempt) generation)))
               delay)))))

(defn- connect-websocket!
  [url receive-handler on-open attempt generation]
  (when (= generation @connection-generation)
   (println "connecting to websocket:" url)
   (try
     (if-let [channel (js/WebSocket. url)]
       (let [opened? (atom false)]
         (set! (.-onmessage channel)
               (let [receive! (receive-transit-message! receive-handler)]
                 (fn [raw]
                   (when (and (= generation @connection-generation)
                              (identical? channel @ws-channel))
                     (receive! raw)))))
         (set! (.-onopen channel)
               (fn [_]
                 (when (and (= generation @connection-generation)
                            (identical? channel @ws-channel))
                   (reset! opened? true)
                   (cancel-reconnect-timer!)
                   (when on-open (on-open)))))
         (set! (.-onclose channel)
               (fn [_]
                 (when (and (= generation @connection-generation)
                            (identical? channel @ws-channel))
                   (reset! ws-channel nil)
                   (schedule-websocket-reconnect! url receive-handler on-open
                                                  (if @opened? 0 attempt)
                                                  generation))))
         (set! (.-onerror channel) (fn [_] (.close channel)))
         (reset! ws-channel channel)
         (println "websocket connection established with" url))
       (println "websocket connection FAILED with" url))
     (catch :default e
       (println "websocket error for url" url ":" (.-message e))
       (schedule-websocket-reconnect! url receive-handler on-open attempt
                                      generation)))))

(defn make-websocket!
  ([url receive-handler]
   (make-websocket! url receive-handler nil))
  ([url receive-handler on-open]
   (reset! intentionally-closed? false)
   (cancel-reconnect-timer!)
   (let [generation (swap! connection-generation inc)]
     (when-let [channel @ws-channel]
       (set! (.-onmessage channel) nil)
       (set! (.-onopen channel) nil)
       (set! (.-onclose channel) nil)
       (.close channel)
       (reset! ws-channel nil))
     (connect-websocket! url receive-handler on-open 0 generation))))

(defn close-websocket!
  []
  (reset! intentionally-closed? true)
  (swap! connection-generation inc)
  (cancel-reconnect-timer!)
  (when @ws-channel
    (set! (.-onmessage @ws-channel) nil)
    (set! (.-onopen @ws-channel) nil)
    (set! (.-onclose @ws-channel) nil)
    (.close @ws-channel)
    (reset! ws-channel nil)))
