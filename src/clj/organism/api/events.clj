(ns organism.api.events
  (:require
   [organism.api.commands :as commands]
   [organism.api.projection :as projection]))

(def version 1)

(defn snapshot
  [game-state viewer]
  (let [projected (projection/project-game game-state viewer)]
    {:type "snapshot"
     :version version
     :revision (:revision projected)
     :projection projected}))

(defn game-updated
  [game-state viewer]
  (let [projected (projection/project-game game-state viewer)]
    {:type "game.updated"
     :version version
     :revision (:revision projected)
     :projection projected}))

(defn- state-at-revision
  [game-state revision]
  (let [history (vec (or (:history game-state) []))]
    (-> game-state
        (assoc :history (subvec history 0 (inc revision)))
        (assoc-in [:game :state] (nth history revision)))))

(defn- update-event
  [game-state viewer revision]
  {:type "game.updated"
   :version version
   :revision revision
   :projection (projection/project-game
                (state-at-revision game-state revision)
                viewer)})

(defn catch-up
  "Return ordered game events after a client revision, or a current snapshot
   when the requested revision cannot be satisfied from retained history."
  [game-state viewer after-revision]
  (let [current (commands/current-revision game-state)
        available? (and (integer? after-revision)
                        (<= 0 after-revision current))]
    {:gameId (:key game-state)
     :fromRevision after-revision
     :toRevision current
     :events (if available?
               (mapv #(update-event game-state viewer %)
                     (range (inc after-revision) (inc current)))
               [(snapshot game-state viewer)])}))
