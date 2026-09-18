(ns organism.api.actions-test
  (:require
   [clojure.test :refer :all]
   [organism.api.actions :as actions]
   [organism.choice :as choice]
   [organism.examples :as examples]))

(def representative-choices
  {:introduce {{:spaces {[:orange 0] :eat
                         [:orange 1] :grow
                         [:orange 2] :move}
                :organism 0} {:state :introduced}}
   :choose-organism {7 {:state :organism-chosen}}
   :choose-action-type {:eat {:state :action-type-chosen}}
   :choose-action {:move {:state :action-chosen}
                   :pass {:state :passed}}
   :eat-to {[:red 1] {:state :eater-chosen}}
   :eat-from {[:yellow 0] {:state :food-chosen}}
   :grow-element {:grow {:state :element-chosen}}
   :grow-from {{[:red 1] 1 [:red 2] 2} {:state :food-spent}}
   :grow-to {[:blue 3] {:state :grown}}
   :move-from {[:orange 2] {:state :mover-chosen}}
   :move-to {[:blue 4] {:state :moved}}
   :circulate-from {[:red 5] {:state :circulator-chosen}}
   :circulate-to {[:orange 6] {:state :circulated}}
   :pass {:pass {:state :passed}}})

(deftest describes-every-current-choice-phase
  (doseq [[phase choices] representative-choices]
    (testing (name phase)
      (let [descriptors (actions/describe-actions phase choices "alice")]
        (is (= (count choices) (count descriptors)))
        (doseq [descriptor descriptors]
          (is (= #{:actionId :kind :label :actor :source :targets
                   :options :cost :consequences :nextActions}
                 (set (keys descriptor))))
          (is (= (name phase) (:kind descriptor)))
          (is (= "alice" (:actor descriptor)))
          (is (string? (:actionId descriptor)))
          (is (= 64 (count (:actionId descriptor))))
          (is (not (contains? descriptor :game))))))))

(deftest action-ids-are-deterministic-and-order-independent
  (let [forward (array-map :eat {:state :eat} :move {:state :move})
        reverse (array-map :move {:state :move} :eat {:state :eat})]
    (is (= (actions/describe-actions :choose-action forward "alice")
           (actions/describe-actions :choose-action reverse "alice")))))

(deftest every-descriptor-resolves-to-exactly-one-legal-choice
  (doseq [[phase choices] representative-choices
          descriptor (actions/describe-actions phase choices "alice")]
    (let [resolved (actions/resolve-action phase choices (:actionId descriptor))]
      (is (= 1 (count resolved)))
      (is (contains? choices (:choice (first resolved))))
      (is (= (get choices (:choice (first resolved)))
             (:game (first resolved)))))))

(deftest unknown-action-id-does-not-resolve
  (is (empty? (actions/resolve-action :choose-action
                                      {:eat {:state :eat}}
                                      "not-a-legal-action"))))

(deftest move-sources-preview-their-server-derived-legal-destinations
  (with-redefs [choice/find-next-choices
                (fn [game]
                  [game :move-to {[:green 3] {:state :moved-three}
                                  [:green 4] {:state :moved-four}}])]
    (let [actions (actions/describe-actions
                   :move-from
                   {[:purple 4] {:state {}}}
                   "alice")
          source (first actions)]
      (is (= #{["green" 3] ["green" 4]}
             (set (map (comp first :targets) (:nextActions source)))))
      (is (every? #(= "move-to" (:kind %)) (:nextActions source)))
      (is (every? #(not (contains? % :game)) (:nextActions source))))))

(deftest eaters-preview-their-server-derived-food-sources
  (with-redefs [choice/find-next-choices
                (fn [game]
                  [game :eat-from {[:green 2] {:state :ate-two}
                                   [:green 3] {:state :ate-three}}])]
    (let [source (first (actions/describe-actions
                         :eat-to
                         {[:purple 2] {:state {}}}
                         "alice"))]
      (is (= #{["green" 2] ["green" 3]}
             (set (map (comp first :targets) (:nextActions source)))))
      (is (every? #(= "eat-from" (:kind %)) (:nextActions source))))))

(deftest grow-choices-preview-payments-and-destinations
  (with-redefs [choice/find-next-choices
                (fn [game]
                  (case (get-in game [:state :stage])
                    :payment [game :grow-from {{[:blue 0] 1} {:state {:stage :target}}
                                               {[:orange 1] 1} {:state {:stage :target}}}]
                    :target [game :grow-to {[:blue 1] {:state {:stage :done}}}]))]
    (let [grow-choice (first (actions/describe-actions
                              :grow-element
                              {:eat {:state {:stage :payment}}}
                              "alice"))
          payments (:nextActions grow-choice)]
      (is (= 2 (count payments)))
      (is (every? #(= "grow-from" (:kind %)) payments))
      (is (every? #(= 1 (:cost %)) payments))
      (is (every? #(= ["grow-to"] (mapv :kind (:nextActions %))) payments)))))

(deftest grow-choice-previews-destinations-after-a-forced-payment
  (with-redefs [choice/find-next-choices
                (fn [game]
                  [game :grow-to {[:blue 1] {:state {:stage :done}}}])]
    (let [grow-choice (first (actions/describe-actions
                              :grow-element
                              {:eat {:state {:stage :target}}}
                              "alice"))]
      (is (= ["grow-to"] (mapv :kind (:nextActions grow-choice)))))))

(deftest canonical-engine-produces-introduction-actions
  (let [{:keys [game phase actions]}
        (actions/action-context examples/two-player-close "orb")]
    (is (= :introduce phase))
    (is (= 6 (count actions)))
    (is (= "orb" (get-in game [:state :player-turn :player])))
    (is (every? #(= "orb" (:actor %)) actions))))
