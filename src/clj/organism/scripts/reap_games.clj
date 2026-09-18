(ns organism.scripts.reap-games
  "Remove games whose deletion grace period expired with no objection.

     lein run -m organism.scripts.reap-games --dry-run
     lein run -m organism.scripts.reap-games --writers-quiesced

   Run it dry first, and keep running it dry until the list looks right —
   deletion is not reversible. Stop the app and all other writers before the
   non-dry run. --writers-quiesced acknowledges that operational precondition;
   it does not stop writers or provide a cross-process lock."
  (:require
   [organism.handler :as handler]
   [organism.mongo :as db]
   [organism.persist :as persist]
   [organism.reap :as reap]))

(defn -main
  [& args]
  (let [dry-run? (boolean (some #{"--dry-run" "-n"} args))
        _ (when-not dry-run? (persist/require-quiesced-writers! args))
        connection (db/connect! handler/mongo-connection)
        {:keys [deleted-count kept-count]} (reap/sweep! connection {:dry-run? dry-run?})]
    (println (if dry-run?
               (str "DRY RUN - would delete " deleted-count " game(s)")
               (str "deleted " deleted-count " game(s)")))
    (when (pos? kept-count)
      (println "cleared" kept-count "stale mark(s) on games that moved again"))
    (System/exit 0)))
