(ns organism.api.commands-test
  (:require
   [clojure.test :refer :all]
   [organism.api.actions :as actions]
   [organism.api.commands :as commands]
   [organism.examples :as examples]))

(def game-state
  {:key "pond-life"
   :invocation {:players ["orb" "mass"]}
   :game examples/two-player-close
   :history [examples/two-player-close]
   :chat []})

(defn command
  [action-id]
  {:actionId action-id
   :expectedRevision 0
   :commandId "command-1"})

(deftest accepts-one-current-legal-action
  (let [action-id (:actionId (first (:actions (actions/action-context
                                                (:game game-state)
                                                "orb"))))
        result (commands/execute-command game-state "orb" (command action-id))]
    (is (= :accepted (:status result)))
    (is (= action-id (:action-id result)))
    (is (= "command-1" (:command-id result)))
    (is (= "orb" (:player result)))
    (is (map? (:game result)))
    (is (not= (:game game-state) (:game result)))))

(deftest rejects-invalid-command-shapes
  (doseq [[body code] [[{:expectedRevision 0 :commandId "command-1"}
                        "action-id-required"]
                       [{:actionId "action" :commandId "command-1"}
                        "expected-revision-required"]
                       [{:actionId "action" :expectedRevision 0}
                        "command-id-required"]]]
    (is (= {:status :rejected :http-status 400 :error code}
           (commands/execute-command game-state "orb" body)))))

(deftest rejects-callers-without-authority-before-deriving-actions
  (with-redefs [actions/resolve-current-action
                (fn [& _]
                  (throw (ex-info "must not derive actions" {})))]
    (is (= {:status :rejected :http-status 401 :error "authentication-required"}
           (commands/execute-command game-state nil (command "action"))))
    (is (= {:status :rejected :http-status 403 :error "not-a-participant"}
           (commands/execute-command game-state "mallory" (command "action"))))
    (is (= {:status :rejected :http-status 409 :error "stale-revision"
            :revision 0}
           (commands/execute-command game-state "orb"
                                     (assoc (command "action")
                                            :expectedRevision 2))))))

(deftest rejects-out-of-turn-and-unknown-actions
  (is (= {:status :rejected :http-status 403 :error "not-your-turn"}
         (commands/execute-command game-state "mass" (command "action"))))
  (is (= {:status :rejected :http-status 422 :error "action-not-legal"}
         (commands/execute-command game-state "orb" (command "unknown")))))

(deftest rejects-games-that-cannot-accept-commands
  (is (= {:status :rejected :http-status 409 :error "game-not-active"}
         (commands/execute-command (assoc game-state :game nil) "orb"
                                   (command "action"))))
  (is (= {:status :rejected :http-status 409 :error "game-not-active"}
         (commands/execute-command
          (assoc-in game-state [:game :state :winner] "orb")
          "orb"
          (command "action")))))
