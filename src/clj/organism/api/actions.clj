(ns organism.api.actions
  (:require
   [clojure.string :as str]
   [organism.api.json :as json]
   [organism.choice :as choice]
   [organism.game :as game])
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]))

(defn- sha-256
  [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn action-id
  [phase raw-choice]
  (sha-256 (pr-str [(json/json-safe phase)
                    (json/json-safe raw-choice)])))

(defn- coordinate-label
  [coordinate]
  (if (and (vector? coordinate) (= 2 (count coordinate)))
    (str (name (first coordinate)) " " (second coordinate))
    (str coordinate)))

(defn- choice-label
  [raw-choice]
  (cond
    (keyword? raw-choice) (-> raw-choice name (str/replace "-" " "))
    (number? raw-choice) (str raw-choice)
    (vector? raw-choice) (coordinate-label raw-choice)
    :else (str raw-choice)))

(defn- action-label
  [phase raw-choice]
  (case phase
    :introduce "Place starting elements"
    :choose-organism (str "Use organism " (choice-label raw-choice))
    :choose-action-type (str "Plan " (choice-label raw-choice) " actions")
    :choose-action (if (= raw-choice :pass)
                     "Finish this organism"
                     (str "Use " (choice-label raw-choice)))
    :eat-to (str "Eat with element at " (choice-label raw-choice))
    :eat-from (str "Take food from " (choice-label raw-choice))
    :grow-element (str "Grow an " (choice-label raw-choice) " element")
    :grow-from "Choose food for growth"
    :grow-to (str "Grow into " (choice-label raw-choice))
    :move-from (str "Move element at " (choice-label raw-choice))
    :move-to (str "Move to " (choice-label raw-choice))
    :circulate-from (str "Circulate food from " (choice-label raw-choice))
    :circulate-to (str "Circulate food to " (choice-label raw-choice))
    :pass "Finish this organism"
    (str "Choose " (choice-label raw-choice))))

(def ^:private source-phases
  #{:eat-to :move-from :circulate-from})

(def ^:private target-phases
  #{:eat-from :grow-to :move-to :circulate-to})

(def ^:private option-phases
  #{:choose-organism :choose-action-type :choose-action :grow-element})

(defn- descriptor
  [phase raw-choice actor]
  (let [safe-choice (json/json-safe raw-choice)
        introduction-spaces (when (= phase :introduce)
                              (keys (:spaces raw-choice)))
        contribution (when (= phase :grow-from) raw-choice)]
    {:actionId (action-id phase raw-choice)
     :kind (name phase)
     :label (action-label phase raw-choice)
     :actor actor
     :source (when (contains? source-phases phase) safe-choice)
     :targets (cond
                (contains? target-phases phase) [safe-choice]
                (= phase :introduce) (->> introduction-spaces
                                           (sort-by pr-str)
                                           (mapv json/json-safe))
                :else [])
     :options (cond
                (contains? option-phases phase) [safe-choice]
                (= phase :introduce) [(json/json-safe raw-choice)]
                (= phase :grow-from) [(json/json-safe contribution)]
                :else [])
     :cost (when contribution (reduce + 0 (vals contribution)))
     :consequences []}))

(defn describe-actions
  "Describe immediate legal choices without exposing their resulting games."
  [phase choices actor]
  (->> (keys choices)
       (map #(descriptor phase % actor))
       (sort-by :actionId)
       vec))

(defn resolve-action
  "Return every current legal choice matching an advertised action ID.

   A valid ID resolves to exactly one entry. Returning a collection makes a
   collision detectable instead of silently selecting an arbitrary state."
  [phase choices requested-id]
  (->> choices
       (keep (fn [[raw-choice next-game]]
               (when (= requested-id (action-id phase raw-choice))
                 {:choice raw-choice :game next-game})))
       vec))

(defn action-context
  "Auto-advance forced engine states and describe choices for the acting player."
  [initial-game actor]
  (let [[stable-game phase choices] (choice/find-next-choices initial-game)
        current-player (game/current-player stable-game)
        can-act (and (= actor current-player)
                     (nil? (get-in stable-game [:state :winner])))]
    {:game stable-game
     :phase phase
     :actions (if can-act
                (describe-actions phase choices actor)
                [])}))
