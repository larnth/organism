(ns organism.api.commands-test
  (:require
   [clojure.test :refer :all]
   [organism.api.actions :as actions]
   [organism.api.commands :as commands]
   [organism.examples :as examples]
   [organism.game :as game]))

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

(defn- apply-label
  [current label]
  (let [actor (game/current-player current)
        action (some #(when (= label (:label %)) %)
                     (:actions (actions/action-context current actor)))
        resolution (actions/resolve-current-action current actor (:actionId action))]
    (:game (first (:matches resolution)))))

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

(deftest an-account-colliding-with-a-bot-seat-cannot-submit-bot-commands
  (let [bot-game (-> game-state
                     (assoc :bots #{"orb"})
                     (assoc :created-by "orb"))
        result (commands/execute-command bot-game "orb" (command "anything"))]
    (is (= :rejected (:status result)))
    (is (= 403 (:http-status result)))
    (is (= "not-a-participant" (:error result)))))

(deftest accepts-a-server-validated-action-path-atomically
  (let [calls (atom [])
        state (assoc game-state :game {:stage :source})]
    (with-redefs [actions/resolve-current-action
                  (fn [game player action-id]
                    (swap! calls conj [game player action-id])
                    {:game game
                     :phase (if (= :source (:stage game)) :move-from :move-to)
                     :current-player "orb"
                     :active? true
                     :matches (case [(:stage game) action-id]
                                [:source "source-action"] [{:game {:stage :target}}]
                                [:target "target-action"] [{:game {:stage :done}}]
                                [])})]
      (let [result (commands/execute-command
                    state
                    "orb"
                    (command ["source-action" "target-action"]))]
        (is (= :accepted (:status result)))
        (is (= {:stage :done} (:game result)))
        (is (= [[{:stage :source} "orb" "source-action"]
                [{:stage :target} "orb" "target-action"]]
               @calls))))))

(deftest accepts-an-eater-and-food-source-path-atomically
  (with-redefs [actions/resolve-current-action
                (fn [game _ action-id]
                  {:game game
                   :phase (if (= :source (:stage game)) :eat-to :eat-from)
                   :current-player "orb"
                   :active? true
                   :matches (case [(:stage game) action-id]
                              [:source "eater"] [{:game {:stage :food-source}}]
                              [:food-source "food"] [{:game {:stage :done}}]
                              [])})]
    (let [result (commands/execute-command
                  (assoc game-state :game {:stage :source})
                  "orb"
                  (command ["eater" "food"]))]
      (is (= :accepted (:status result)))
      (is (= {:stage :done} (:game result))))))

(deftest accepts-a-grow-choice-payment-and-destination-path-atomically
  (with-redefs [actions/resolve-current-action
                (fn [game _ action-id]
                  {:game game
                   :phase (case (:stage game)
                            :element :grow-element
                            :payment :grow-from
                            :target :grow-to)
                   :current-player "orb"
                   :active? true
                   :matches (case [(:stage game) action-id]
                              [:element "element"] [{:game {:stage :payment}}]
                              [:payment "payment"] [{:game {:stage :target}}]
                              [:target "target"] [{:game {:stage :done}}]
                              [])})]
    (let [result (commands/execute-command
                  (assoc game-state :game {:stage :element})
                  "orb"
                  (command ["element" "payment" "target"]))]
      (is (= :accepted (:status result)))
      (is (= {:stage :done} (:game result))))))

(deftest accepts-a-grow-choice-and-destination-when-payment-is-forced
  (with-redefs [actions/resolve-current-action
                (fn [game _ action-id]
                  {:game game
                   :phase (if (= :element (:stage game)) :grow-element :grow-to)
                   :current-player "orb"
                   :active? true
                   :matches (case [(:stage game) action-id]
                              [:element "element"] [{:game {:stage :target}}]
                              [:target "target"] [{:game {:stage :done}}]
                              [])})]
    (let [result (commands/execute-command
                  (assoc game-state :game {:stage :element})
                  "orb"
                  (command ["element" "target"]))]
      (is (= :accepted (:status result)))
      (is (= {:stage :done} (:game result))))))

(deftest accepts-a-canonical-engine-grow-path-atomically
  (let [introduction-action (first (:actions (actions/action-context
                                              examples/two-player-close
                                              "orb")))
        introduction (:game (first (:matches
                                    (actions/resolve-current-action
                                     examples/two-player-close
                                     "orb"
                                     (:actionId introduction-action)))))
        grow-template (->> (get-in introduction [:state :elements])
                           vals
                           (filter #(= :grow (:type %)))
                           first)
        prepared (assoc-in introduction [:state :elements [:blue 0]]
                           (assoc grow-template :space [:blue 0] :food 1))
        grow-stage (-> prepared
                       (apply-label "Plan grow actions")
                       (apply-label "Use grow"))
        grow-choice (some #(when (= "Grow an eat element" (:label %)) %)
                          (:actions (actions/action-context grow-stage "orb")))
        payment (first (:nextActions grow-choice))
        destination (first (:nextActions payment))
        state (assoc game-state :game grow-stage :history [grow-stage])
        result (commands/execute-command
                state
                "orb"
                (command [(:actionId grow-choice)
                          (:actionId payment)
                          (:actionId destination)]))]
    (is (some? destination))
    (is (= :accepted (:status result)))
    (is (not= grow-stage (:game result)))))

(deftest rejects-incomplete-and-overlong-action-paths
  (is (= "action-id-required"
         (:error (commands/execute-command
                  game-state "orb" (command ["one" "two" "three" "four"])))))
  (with-redefs [actions/resolve-current-action
                (fn [game _ _]
                  {:game game
                   :phase (if (= :element (:stage game)) :grow-element :grow-from)
                   :current-player "orb"
                   :active? true
                   :matches [{:game {:stage :payment}}]})]
    (is (= "invalid-action-path"
           (:error (commands/execute-command
                    (assoc game-state :game {:stage :element})
                    "orb"
                    (command ["element" "payment"])))))))

(deftest rejects-a-path-when-a-later-action-is-not-legal
  (let [state (assoc game-state :game {:stage :source})]
    (with-redefs [actions/resolve-current-action
                  (fn [game _ action-id]
                    {:game game
                     :phase (if (= :source (:stage game)) :move-from :move-to)
                     :current-player "orb"
                     :active? true
                     :matches (if (= action-id "source-action")
                                [{:game {:stage :target}}]
                                [])})]
      (is (= "action-not-legal"
             (:error (commands/execute-command
                      state
                      "orb"
                      (command ["source-action" "invented-target"]))))))))

(deftest rejects-compound-paths-outside-move-selection
  (with-redefs [actions/resolve-current-action
                (fn [game _ _]
                  {:game game
                   :phase :choose-action-type
                   :current-player "orb"
                   :active? true
                   :matches [{:game {:stage :planned}}]})]
    (is (= "invalid-action-path"
           (:error (commands/execute-command
                    game-state
                    "orb"
                    (command ["plan-move" "use-move"])))))))

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
