(ns organism.choice-test
  (:require
   [clojure.test :refer :all]
   [organism.choice :as choice]
   [organism.game :as game]))

(deftest grow-destinations-use-the-selected-contributors
  (let [spaces-seen (atom nil)
        elements [{:space [:blue 0] :type :grow :food 1}
                  {:space [:orange 1] :type :grow :food 1}]]
    (with-redefs [game/get-action-field (fn [_ field]
                                         (when (= field :from)
                                           {[:orange 1] 1}))
                  game/growable-spaces (fn [_ spaces]
                                         (reset! spaces-seen spaces)
                                         [])]
      (choice/grow-to-choices {} elements nil))
    (is (= #{[:orange 1]} (set @spaces-seen)))))
