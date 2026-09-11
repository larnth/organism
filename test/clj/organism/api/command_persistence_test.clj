(ns organism.api.command-persistence-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [organism.mongo :as db]
   [organism.persist :as persist]))

(def test-connection
  {:host "localhost" :port 27017 :database "organism-command-test"})

(def ^:dynamic *db* nil)

(use-fixtures
  :each
  (fn [run]
    (let [connection (db/connect! test-connection)]
      (db/delete! connection persist/command-collection {})
      (binding [*db* connection] (run))
      (db/delete! connection persist/command-collection {}))))

(deftest reserves-each-command-id-once-per-game
  (let [record {:game-key "pond-life"
                :command-id "command-1"
                :player "alice"
                :action-id "action-1"
                :expected-revision 3}
        first-attempt (persist/reserve-command! *db* record)
        second-attempt (persist/reserve-command! *db* record)]
    (is (true? (:reserved? first-attempt)))
    (is (false? (:reserved? second-attempt)))
    (is (= "pending" (get-in second-attempt [:record :status])))
    (persist/complete-command! *db* "pond-life" "command-1" 4)
    (is (= {:game-key "pond-life"
            :command-id "command-1"
            :player "alice"
            :action-id "action-1"
            :expected-revision 3
            :status "complete"
            :revision 4}
           (dissoc (persist/find-command *db* "pond-life" "command-1") :_id)))))

(deftest permits-the-same-command-id-in-another-game
  (is (true? (:reserved?
              (persist/reserve-command!
               *db* {:game-key "first" :command-id "same"}))))
  (is (true? (:reserved?
              (persist/reserve-command!
               *db* {:game-key "second" :command-id "same"})))))
