(ns organism.api.actions-test
  (:require
   [clojure.test :refer :all]
   [organism.api.actions :as actions]
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
                   :options :cost :consequences}
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

(deftest canonical-engine-produces-introduction-actions
  (let [{:keys [game phase actions]}
        (actions/action-context examples/two-player-close "orb")]
    (is (= :introduce phase))
    (is (= 6 (count actions)))
    (is (= "orb" (get-in game [:state :player-turn :player])))
    (is (every? #(= "orb" (:actor %)) actions))))
