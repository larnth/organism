(ns organism.reap
  "Sweep away games whose deletion grace period ran out with nobody objecting.

   A mark is atomically cleared by canonical acceptance and by any
   participant pressing keep, so a game only ever reaches the reaper when the
   whole table stayed silent for the entire window.

   Activity comes from the canonical head, with an ObjectId fallback for old
   history. Candidate classification is rechecked under the mutation lock.
   Standalone callers must stop app writers; local locks are not distributed."
  (:require
   [organism.mongo :as db]
   [organism.persist :as persist]))

(defn classify
  "Marked games whose deadline has passed, split by whether anything happened
   after the mark. {true [revived...] false [doomed...]}"
  [db now]
  (->> (db/query db :games {"deletion.deadline" {"$lt" now}})
       (map
        (fn [{:keys [key deletion] :as game}]
          (let [activity (persist/last-activity-at db key)
                marked-at (or (:marked-at deletion) 0)]
            {:key key
             :deletion deletion
             :players (get-in game [:invocation :players])
             :last-activity activity
             :revived? (boolean (and activity (> activity marked-at)))})))
       (group-by :revived?)))

(defn sweep!
  "Delete every expired game, and clear the mark on any that moved again since
   they were marked. With :dry-run? true nothing is written — run it that way
   first, because deletion does not come back."
  ([db] (sweep! db {}))
  ([db {:keys [dry-run? now]}]
   (let [now (or now (persist/now-seconds))
         candidates (mapcat val (classify db now))
         results
         (reduce
          (fn [result {:keys [key]}]
            (locking (persist/game-lock key)
              ;; Classification is only a candidate list. Re-read the mark and
              ;; authoritative activity inside the same boundary as acceptance.
              (let [record (persist/find-published-game db key)
                    {:keys [deadline marked-at]} (:deletion record)
                    activity (when record (persist/last-activity-at db key))]
                (if (and deadline (< deadline now))
                  (if (and activity (> activity (or marked-at 0)))
                    (do (when-not dry-run? (persist/unmark-game-for-deletion! db key))
                        (update result :kept conj key))
                    (do (when-not dry-run? (persist/delete-game! db key))
                        (update result :deleted conj key)))
                  result))))
          {:deleted [] :kept []} candidates)]
     {:dry-run? (boolean dry-run?)
      :deleted-count (count (:deleted results))
      :deleted (:deleted results)
      :kept-count (count (:kept results))
      :kept (:kept results)})))
