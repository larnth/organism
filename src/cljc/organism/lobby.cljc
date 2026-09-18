(ns organism.lobby
  (:require
   [clojure.string :as str]))

(defn identity-key
  [player]
  (when (string? player)
    (str/lower-case player)))

(defn same-player?
  [left right]
  (and (seq left)
       (seq right)
       (= (identity-key left) (identity-key right))))

(defn permissions
  [creator current-player players]
  (let [signed-in? (seq current-player)
        owner? (boolean (and signed-in? (same-player? creator current-player)))
        seated? (boolean (and signed-in?
                              (some #(same-player? current-player %) players)))]
    {:owner? owner?
     :seated? seated?
     :can-edit? owner?
     :can-join? (boolean (and signed-in? (not seated?)))}))

(defn human-seat?
  "True when the authenticated viewer occupies a non-bot roster seat."
  [players viewer bot?]
  (boolean
   (and (seq viewer)
        (some #(and (same-player? viewer %)
                    (not (bot? %)))
              players))))

(defn member?
  "True when the authenticated viewer owns the lobby or occupies a human seat.
   Bot display names never grant account membership."
  [creator players viewer bot?]
  (boolean
   (or (same-player? creator viewer)
       (human-seat? players viewer bot?))))

(defn visible-invocation
  "Return the lobby details this viewer may see before joining. Private-room
   outsiders can see capacity and field size plus which seats are available,
   but not member identities or the private description."
  ([visibility invocation viewer]
   (visible-invocation visibility invocation viewer (constantly false)))
  ([visibility invocation viewer bot?]
   (let [member? (member? (first (:players invocation))
                          (:players invocation) viewer bot?)]
     (if (or (not= "private" visibility) member?)
       invocation
       (-> (select-keys invocation [:player-count :ring-count])
           (assoc :players
                  (mapv #(if (str/blank? %) "" "Occupied")
                        (:players invocation))))))))

(defn visible-creator
  ([visibility creator players viewer]
   (visible-creator visibility creator players viewer (constantly false)))
  ([visibility creator players viewer bot?]
   (when (or (not= "private" visibility)
             (member? creator players viewer bot?))
     creator)))

(defn ready?
  [readiness player]
  (let [key (identity-key player)]
    (true? (or (get readiness key)
               (get readiness (keyword key))))))

(defn append-chat-once
  "Append a chat event unless this delivery ID is already present. Legacy
   history without IDs remains append-only."
  [chat message]
  (if (and (:id message)
           (some #(= (:id message) (:id %)) chat))
    chat
    (conj (vec chat) message)))

(defn chat-delivery-acknowledges?
  "True when an authoritative chat event confirms the currently pending draft."
  [pending-client-id message]
  (and (some? pending-client-id)
       (= pending-client-id (:client-id message))))

(defn normalize-readiness
  "Keep readiness only for the current human roster. Missing players default to
   not ready; callers may omit bots before passing the roster."
  [players readiness]
  (let [readiness (into {}
                        (keep (fn [[player value]]
                                (when-let [key (identity-key (name player))]
                                  [key value])))
                        readiness)]
    (into {}
          (keep (fn [player]
                  (when-let [key (identity-key player)]
                    [key (ready? readiness player)])))
          (remove str/blank? players))))

(defn- natural-list
  [items]
  (case (count items)
    0 ""
    1 (first items)
    2 (str (first items) " and " (second items))
    (str (str/join ", " (butlast items)) ", and " (last items))))

(defn launch-blocker
  "Return one concise reason a lobby cannot start, or nil when it can."
  [players readiness bots]
  (let [empty-count (count (filter str/blank? players))
        bots (set (map identity-key bots))
        waiting (->> players
                     (remove str/blank?)
                     (remove #(contains? bots (identity-key %)))
                     (remove #(ready? readiness %))
                     vec)]
    (cond
      (pos? empty-count)
      (str "Waiting for " empty-count " player" (when (not= 1 empty-count) "s") ".")

      (seq waiting)
      (str "Waiting for " (natural-list waiting) " to ready up.")

      :else nil)))
