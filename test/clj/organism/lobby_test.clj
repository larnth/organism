(ns organism.lobby-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [organism.lobby :as lobby]))

(deftest permissions-follow-account-membership-and-lobby-ownership
  (testing "the creator can configure and start, but cannot join a second seat"
    (is (= {:owner? true :seated? true :can-edit? true :can-join? false}
           (lobby/permissions "Alice" "alice" ["Alice" ""]))))
  (testing "another signed-in account can join exactly while unseated"
    (is (= {:owner? false :seated? false :can-edit? false :can-join? true}
           (lobby/permissions "Alice" "Bob" ["Alice" ""]))))
  (testing "a participant already at the table cannot join or edit"
    (is (= {:owner? false :seated? true :can-edit? false :can-join? false}
           (lobby/permissions "Alice" "bOB" ["Alice" "Bob" ""]))))
  (testing "an observer cannot mutate the lobby"
    (is (= {:owner? false :seated? false :can-edit? false :can-join? false}
           (lobby/permissions "Alice" nil ["Alice" ""])))))

(deftest launch-blockers-explain-the-next-required-action
  (testing "empty seats block launch before readiness"
    (is (= "Waiting for 1 player."
           (lobby/launch-blocker ["Alice" ""] {"alice" true} #{}))))
  (testing "unready humans are named while bots are automatically ready"
    (is (= "Waiting for Bob to ready up."
           (lobby/launch-blocker ["Alice" "Bob" "OBO-A"]
                                 {"alice" true "bob" false}
                                 #{"OBO-A"}))))
  (testing "a full, ready lobby has no blocker"
    (is (nil? (lobby/launch-blocker ["Alice" "Bob"]
                                    {"alice" true "bob" true}
                                    #{})))))

(deftest readiness-follows-membership-and-is-case-insensitive
  (is (= {"alice" true "bob" false}
         (lobby/normalize-readiness ["Alice" "Bob"]
                                    {"ALICE" true "removed" true})))
  (is (true? (lobby/ready? {"alice" true} "Alice")))
  (is (false? (lobby/ready? {} "Alice"))))

(deftest private-lobby-details-are-hidden-until-membership
  (let [invocation {:players ["Alice" "Bob" ""]
                    :player-count 3
                    :ring-count 5
                    :description "Friends only"}]
    (is (= invocation (lobby/visible-invocation "open" invocation "Visitor")))
    (is (= invocation (lobby/visible-invocation "private" invocation "alice")))
    (is (= {:players ["Occupied" "Occupied" ""]
            :player-count 3
            :ring-count 5}
           (lobby/visible-invocation "private" invocation "Visitor")))))

(deftest a-human-account-colliding-with-a-bot-seat-is-not-a-lobby-member
  (let [invocation {:players ["Alice" "OBO-A" ""]
                    :player-count 3
                    :ring-count 5
                    :description "Friends only"}
        bot? #(= "OBO-A" %)]
    (is (= {:players ["Occupied" "Occupied" ""]
            :player-count 3
            :ring-count 5}
           (lobby/visible-invocation "private" invocation "OBO-A" bot?)))
    (is (nil? (lobby/visible-creator "private" "Alice"
                                    (:players invocation) "OBO-A" bot?)))))

(deftest bot-seat-membership-rejection-is-case-insensitive
  (let [players ["Alice" "OBO-A"]
        bot? #(= "OBO-A" %)]
    (is (false? (lobby/member? "Alice" players "obo-a" bot?)))))

(deftest private-lobby-ownership-is-not-claimed-by-an-outsider
  (is (nil? (lobby/visible-creator "private" "Alice" ["Alice" ""] "Visitor")))
  (is (= "Alice" (lobby/visible-creator "private" "Alice" ["Alice" ""] "alice")))
  (is (= "Alice" (lobby/visible-creator "open" "Alice" ["Alice" ""] "Visitor"))))

(deftest repeated-delivery-of-the-same-chat-event-is-idempotent
  (let [message {:id "chat-1" :player "alice" :message "hello"}]
    (is (= [message]
           (-> []
               (lobby/append-chat-once message)
               (lobby/append-chat-once message))))
    (is (= 2 (count (-> []
                        (lobby/append-chat-once message)
                        (lobby/append-chat-once (assoc message :id "chat-2"))))))))

(deftest chat-delivery-acknowledges-only-the-matching-draft
  (is (true? (lobby/chat-delivery-acknowledges? "draft-123"
                                                {:client-id "draft-123"})))
  (is (false? (lobby/chat-delivery-acknowledges? "draft-123"
                                                 {:client-id "another-draft"})))
  (is (false? (lobby/chat-delivery-acknowledges? nil
                                                 {:client-id "draft-123"}))))
