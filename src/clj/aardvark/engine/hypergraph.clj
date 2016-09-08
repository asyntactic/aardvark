(ns aardvark.engine.hypergraph
  (:use medley.core))

(def WILDCARD "*")

;;;; A basic hypergraph implementation in which each edge may connect
;;;; multiple tail and head vertexes. 
;;;;
;;;; Within the context of Ignite, an edge corresponds to a function
;;;; and vertexes are arguments (tail) and outputs (head).

(defrecord edge [name tails heads])

;; Note that we do not enforce a connected graph
(defn graph [& edges]
  "Create a new graph. Edges are expected to have unique names; if 
   this is not the case, they will be arbitrarily pruned."
  (->> edges 
  (distinct-by :name)))

(defn get-edge [graph edge-name]
  "Get an edge from a graph by name"
  (->> graph (find-first #(= edge-name (:name %)))))

(defn- get-edges-pointing-to [graph target]
  "Fetch all edges with target vertex as a head"
  (set (filter #(some (fn [x] (or (= WILDCARD x) (= target x))) (:heads %)) graph))) 

(defn- valid? [x]
  (every? not-empty x))

;; Basically a DFS
(defn- _search [graph sources head]
  (let [candidates (get-edges-pointing-to graph head)]
    (if (empty? candidates) ()
        (filter valid? 
                  (map (fn [edge]
                         (let [thing-to-report edge] ;(:name edge) easier to read for debugging
                               (map (fn [tail]
                                      (if (some #(or (= tail WILDCARD) (= % tail)) sources) ; Is this a terminal/source tail?
                                         thing-to-report ; Then report nothing, we are done with this path
                                         (let [downstream-soln (_search (remove #(= % edge) graph) sources tail)]
                                           (if (empty? downstream-soln) () ; Prune a bad branch
                                               (conj downstream-soln thing-to-report))))) ; Useful downstream results, append this branch to the current path
                                      (:tails edge))))
                         candidates)))))

;; We don't care about the nesting any more, we just want a
;; dependency-ordered list of functions for each solution
(defn search [graph sources head]
  "Search a graph for a valid path to head given the source vertexes"
  (->> (_search graph sources head)
       (map flatten) ; We don't want nested hierarchy; just a(n ordered) list of functions to call
       (map distinct))) ; If a given function has N tails, we're going to see N copies of that function; we don't need this. 

