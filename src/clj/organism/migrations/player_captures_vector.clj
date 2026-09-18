(ns organism.migrations.player-captures-vector
  (:require
   [organism.board :as board]
   [organism.mongo :as db]
   [organism.persist :as persist]
   [organism.handler :as handler]))

(defn migrate!
  [db]
  (persist/assert-legacy-database! db)
  (let [games (db/find-all db :games)]
    (doseq [game games]
      (let [game-key (:key game)
            {:keys [players player-captures] :as invocation} (:invocation game)
            captures (mapv (fn [player] (get player-captures player board/default-player-captures)) players)
            updated-invocation (assoc invocation :player-captures captures)]
        (println "converting player captures for" game-key ":" captures)
        (db/merge! db :games {:key game-key} {:invocation updated-invocation})))))

(defn -main
  [& args]
  (persist/require-quiesced-writers! args)
  (let [db (db/connect! handler/mongo-connection)]
    (println "migrating initial player games")
    (migrate! db)))
