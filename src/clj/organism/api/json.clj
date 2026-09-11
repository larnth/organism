(ns organism.api.json)

(def ^:private private-field-names
  #{"_id" "password" "password-hash" "passwordHash"})

(defn- key-name
  [value]
  (cond
    (keyword? value) (if-let [namespace (namespace value)]
                       (str namespace "/" (name value))
                       (name value))
    (string? value) value
    :else nil))

(defn- private-field?
  [key]
  (contains? private-field-names (key-name key)))

(declare json-safe)

(defn- json-safe-map
  [value]
  (let [visible (remove (comp private-field? key) value)]
    (if (every? (comp some? key-name key) visible)
      (into (sorted-map)
            (map (fn [[key item]]
                   [(key-name key) (json-safe item)]))
            visible)
      (->> visible
           (sort-by (comp pr-str key))
           (mapv (fn [[key item]]
                   [(json-safe key) (json-safe item)]))))))

(defn json-safe
  "Convert Clojure values to deterministic data that ordinary JSON can encode.

   Maps with coordinate or other compound keys become ordered key/value pairs;
   private persistence and authentication fields are removed recursively."
  [value]
  (cond
    (keyword? value) (key-name value)
    (map? value) (json-safe-map value)
    (set? value) (->> value (sort-by pr-str) (mapv json-safe))
    (vector? value) (mapv json-safe value)
    (sequential? value) (mapv json-safe value)
    (or (nil? value)
        (string? value)
        (number? value)
        (boolean? value)) value
    :else (str value)))
