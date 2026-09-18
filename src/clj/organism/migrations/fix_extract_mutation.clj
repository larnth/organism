(ns organism.migrations.fix-extract-mutation
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
      (let [inner-game (:game game)
            game-key (:key inner-game)]
        (db/merge!
         db :games
         {:key game-key}
         inner-game)))))

(defn -main
  [& args]
  (persist/require-quiesced-writers! args)
  (let [db (db/connect! handler/mongo-connection)]
    (println "migrating mutations for existing games")
    (migrate! db)))
