(ns aardvark.test.engine.hypergraph
  (:use clojure.test
        aardvark.engine.hypergraph))

(def test-graph (graph 
           (->edge "e1" #{3 4 5} #{1})
           (->edge "e2" #{3 4 5} #{2})
           (->edge "e3" #{9} #{3})
           (->edge "e4" #{6 8} #{4})
           (->edge "e5" #{7} #{5})
           (->edge "e6" #{10} #{8})
           (->edge "e7" #{10} #{7})
           (->edge "e8" #{3} #{1})
           (->edge "e9" #{11} #{5})))

(defn first-path [result]
  (map :name (first result)))

(deftest test-search-single
  (testing "Verify a single-node solution"
    (is (= '("e9") (first-path (search test-graph #{2 11} 5))))))

;// Valid paths are: 8-3, 1-[3-[4-6]-[5-7]]
;; 6/9/10 -> 1
(deftest test-search-multi
  (testing "Verify a multi-step path"
    (let [soln (search test-graph #{6 9 10} 1)]
      (is (= 2 (count soln)))
      (is (= '("e8" "e3") (first-path soln))))))
    
(deftest test-no-solution
  (testing "Verify clean handling of an unsolvable path"
    (is (empty? (search test-graph #{2} 5)))))

(deftest test-wildcards
  (testing "Verify wildcard handling"
    (let [graph (conj test-graph (->edge "e10" #{2} #{WILDCARD}))]
      (is (not (empty? (search graph #{2} 5))))) ; Now the wildcard will match the 5
    (let [graph (conj test-graph (->edge "e10" #{WILDCARD} #{5}))]
      (is (not (empty? (search graph #{2} 5))))))) ; Now the wildcard will match the 2
