(ns organism.scripts.delete-game
  "Remove a game outright, skipping the mark-and-wait the UI puts live games
   through. For when you know you want it gone.

     java -cp organism.jar clojure.main -m organism.scripts.delete-game \"the name\"
     java -cp organism.jar clojure.main -m organism.scripts.delete-game \"the name\" --force --writers-quiesced

   Without --force it only reports what would go. Deletion takes the game, its
   history and its chat, and every player's row, and does not come back.
   Stop the app and all other writers before --writers-quiesced. This flag is
   an operator acknowledgement, not an automatic stop or cross-process lock."
  (:require
   [organism.handler :as handler]
   [organism.mongo :as db]
   [organism.persist :as persist]))

(defn -main
  [& args]
  (let [game-key (first (remove #(.startsWith ^String % "--") args))
        force? (boolean (some #{"--force" "-f"} args))]
    (when force? (persist/require-quiesced-writers! args))
    (if-not game-key
      (do (println "usage: delete-game <game-key> [--force --writers-quiesced]")
          (System/exit 1))
      (let [connection (db/connect! handler/mongo-connection)
            record (persist/find-game-record connection game-key)]
        (if-not record
          (do (println "no game called" (pr-str game-key))
              (System/exit 1))
          (let [players (persist/game-player-names connection game-key)
                open? (nil? (db/one connection :games {:key game-key}))
                states (persist/game-history-count connection game-key)]
            (println (str (pr-str game-key) (if open? " - open lobby" " - game")))
            (println "  players:      " players)
            (println "  created by:   " (:created-by record))
            (println "  states:       " states)
            (if force?
              (do (when-let [error (:error (persist/delete-game! connection game-key))]
                    (throw (ex-info error {:key game-key})))
                  (println "  -> deleted."))
              (println "  -> dry run. Stop all writers, then use --force --writers-quiesced to delete."))
            (System/exit 0)))))))
