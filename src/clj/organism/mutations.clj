(ns organism.mutations
  "Single-app-instance mutation owner. Mongo CAS is the durable acceptance
   boundary; local locks also serialize validation, recovery and notification."
  (:require [clojure.string :as str]
            [organism.api.commands :as commands]
            [organism.persist :as persist]))

(defn game-lock [key]
  (persist/game-lock key))

(defn field [body k] (get body k (get body (name k))))
(defn receipt [key player body]
  (cond-> {:game-key key :player player :command-id (field body :commandId)
           :action-id (field body :actionId) :operation (or (field body :operation) "action")
           :expected-revision (field body :expectedRevision)}
    (some? (field body :expectedInstanceId))
    (assoc :expected-instance-id (field body :expectedInstanceId))))
(defn same-command? [a b]
  (= (select-keys (update a :operation #(or % "action"))
                  [:game-key :player :command-id :action-id :operation :expected-revision :expected-instance-id])
     b))
(defn reject [status error]
  {:status :rejected :http-status status :error error})

(defn instance-problem
  "Optional wire fence shared by commands and table writes. Omission alone
   preserves old clients; explicit null/blank/non-string/oversize is invalid.
   Call under the lifecycle lock held through the write or lobby claim."
  [state body]
  (when (or (contains? body :expectedInstanceId) (contains? body "expectedInstanceId"))
    (let [expected (field body :expectedInstanceId)]
      (cond
        (not (and (string? expected) (not (str/blank? expected)) (<= (count expected) 200)))
        (reject 400 "expected-instance-id-invalid")
        (nil? state) (reject 404 "game-not-found")
        (not= expected (commands/instance-id state)) (reject 409 "game-replaced")))))

(defn finalize! [db key state]
  (locking (game-lock key)
    (when-let [current (persist/load-game db key)]
      (when (= (select-keys current [:incarnation-id :revision])
               (select-keys state [:incarnation-id :revision]))
        ;; All derived writes are retryable. No accepted event precedes Mongo CAS.
        (persist/repair-mutation! db key)
        ((requiring-resolve 'organism.routes.websockets/post-commit!) db state)
        (when (:revision state)
          (persist/mark-mutation-finalized! db key (:revision state)))
        (dissoc state :needs-finalization?)))))

(defn recover!
  "Snapshot/reconnect recovery also repairs derived indexes and bot handoff.
   The marker avoids replaying the hook on every successful poll."
  [db key]
  (locking (game-lock key)
    (when-let [state (persist/load-game db key)]
      (if (:needs-finalization? state) (finalize! db key state) state))))

(defn execute!
  "Resolver runs under the same lock for HTTP, legacy, Undo and bots. It returns
   the validated next :game and optionally a logical :history-index for Undo."
  [db key player body resolver]
  (locking (game-lock key)
    (if-let [state (persist/load-game db key)]
      (let [record (receipt key player body)
            previous (or (when (= (:command-id record)
                                   (get-in state [:accepted-command :command-id]))
                           (:accepted-command state))
                         (persist/find-command db key (:command-id record)))]
        (cond
          (instance-problem state body)
          (instance-problem state body)

          (and previous (not (same-command? previous record)))
          (reject 409 "command-id-conflict")

          previous
          (if (= "complete" (:status previous))
            {:status :accepted :game-state (if (:needs-finalization? state)
                                            (finalize! db key state) state)}
            (reject 409 "command-in-progress"))

          :else
          (let [result (resolver state)]
            (if (not= :accepted (:status result)) result
                (do
                  ;; Never overwrite the previous receipt before archiving it.
                  (persist/repair-mutation! db key)
                  (let [accepted (persist/commit-mutation!
                                  db key state (:game result) record (:history-index result))]
                    (if accepted
                      {:status :accepted :game-state (finalize! db key accepted)}
                      (reject 409 "stale-revision"))))))))
      (reject 404 "game-not-found"))))
