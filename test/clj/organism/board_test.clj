(ns organism.board-test
  (:require
   [clojure.test :refer [deftest is]]
   [organism.board :as board]))

(deftest player-seats-are-unique-without-regard-to-case
  (is (false? (board/valid-invocation? {:players ["Alice" "alice"]})))
  (is (true? (board/valid-invocation? {:players ["Alice" "Bob"]}))))
