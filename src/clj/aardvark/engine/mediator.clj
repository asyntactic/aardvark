(ns aardvark.engine.mediator
  (:require [aardvark.models.db :as db]
            [aardvark.util :as util]
            [aardvark.engine.hypergraph :as hg]
            [clojure.string :as s]
            [taoensso.timbre :as log]))

(defn- detect-relational-conflicts [domain-id source-schema-id target-role-ids]
  (let [source-roles (db/roles-for-target domain-id source-schema-id)]
    (remove nil? 
            (map #(if (util/contains? % (map :id source-roles)) 
                    nil ; Target role is part of our schema source role set, so no conflict
                    {:conflict-type :relational, :target-role %}) 
                 target-role-ids))))

(defn- find-relational-function-roles [rf]
  (let [safe-split 

        (fn [s] (when-not (nil? s) (s/split-lines s)))
        
        get-role (fn [it] 
                    (when 
                        (and (< 2 (count it))
                             (= (subs it 0 2) "r:")) (subs it 2)))
       
        formulas (db/declaration-list db/formulas (:id rf) (:domain_id rf) false)
        arguments (->> formulas (map :arguments) (map safe-split) flatten)
        outputs (->> formulas (map :output) (map safe-split) flatten)
        source-roles (remove empty? (set (map get-role arguments)))
        target-roles (remove empty? (set (map get-role outputs)))]
    {:source-roles source-roles :target-roles target-roles :id (:id rf)}))

(defn- relational-graph [domain-id]
  ;; Get all the source and target roles for each RF
  (->> (db/declaration-list db/relational_functions nil domain-id false)
       (map find-relational-function-roles)

       ;; Set this up as a hypergraph search problem.
       ;; Vertexes = all roles
       ;; Edges = relational functions
       (map #(hg/->edge (:id %) (:source-roles %) (:target-roles %)))
       (apply hg/graph)))

(defn- resolve-relational-conflict [rf-graph domain-id source-schema-id conflict]
  ;; Search the graph to find a resolution for this relational conflict
  (let [sources (map :id (db/roles-for-target domain-id source-schema-id))]
    (->> (hg/search rf-graph sources (:target-role conflict)) ; list of list of edges. inner list = 1 solution
         (map #(map :name %))))) ; Each solution is an ordered list of RF IDs

(defn- mvs-for-context [context]
  (db/declaration-list db/modifier_values (:id context) (:domain_id context) false))

(defn- context-from-datastore [datastore-id]
  (db/declaration-get db/contexts (:context_id (db/declaration-get db/datastores datastore-id))))

(defn- modifier-ids-for-role [domain-id role-id] 
  ;; Get semantic type for this role
  (let [semantic-type (->> (db/declaration-get db/roles role-id)
                          :semantic_type
                          (db/declaration-get db/semantic_types))]
    (when semantic-type ; This would be nil for relational conflicts, and that would cause below to return all modifiers. Not pretty.
      (map :id (db/declaration-list db/modifiers (:id semantic-type) domain-id true)))))

(defn- detect-semantic-conflicts [domain-id source-datastore-id target-datastore-id target-role-ids]
  (let [modifiers (->> target-role-ids
                       (map (partial modifier-ids-for-role domain-id))
                       flatten
                       set)
        target-mvs (map (fn [mv] {:modifier (:modifier mv) :target-mv (:value mv)})
                        (-> target-datastore-id context-from-datastore mvs-for-context)) ;; ({:modifier _ :target-mv _}...)
        source-mvs (->> (map (fn [mv] {(:modifier mv) (:value mv)}) 
                             (-> source-datastore-id context-from-datastore mvs-for-context))
                        (reduce merge)) ;; {modifier source-mv} 
        all-mvs (map #(assoc % :source-mv (get source-mvs (:modifier %)) :conflict-type :semantic) target-mvs)]
    ;; Filter to just those MVs within the target roles and that have conflicts
    (->> all-mvs
         (filter #(some (fn [x] (= x (:modifier %))) modifiers))
         (filter #(and (:target-mv %)
                       (not (= (:target-mv %) (:source-mv %))))))))

(defn- semantic-graph [domain-id modifier-id]
  (->> (db/get-sfs-for-modifier domain-id modifier-id)
       
       ;; Set this up as a hypergraph search problem.
       ;; Vertexes = modifier values
       ;; Edges = semantic functions
       (map #(hg/->edge (:id %) [(:source_mv %)] [(:target_mv %)]))
       (apply hg/graph)))

(defn- resolve-semantic-conflict [domain-id modifier-id source-mv target-mv]   
  ;; Search the graph to find a resolution for this semantic conflict
  (let [graph (semantic-graph domain-id modifier-id)]
    (->> (hg/search graph [source-mv] target-mv)
         (map #(map :name %)))))

(defn choose-solution [conflict]
  (first (:solutions conflict)))  ;; TODO opportunity here to select a solution with something smarter than "first"

(defn role-contains-modifier? [modifier-id role-id]
  (let [st (->> (db/declaration-get db/roles role-id)
                :semantic_type
                (db/declaration-get db/semantic_types))]
    (loop [st st visited #{}] ;; Recursively check ancestors - STs have inheritance
      (cond (nil? st) false
            (some #{(:id st)} visited) false
            (some #{modifier-id} (map :id (db/declaration-list db/modifiers (:id st) (:domain_id st) true))) true
            :else (or (recur (db/declaration-get db/semantic_types (:id st)) (conj visited (:id st))))))))

(defn filter-roles-for-modifier [modifier-id possible-roles]
  "Filter a seq of possible roles returning only those that are associated with this modifier"
  (filter (partial role-contains-modifier? modifier-id) possible-roles))

(defn get-required-tables [domain-id roles schema-id]
;; The roles correspond to columns. We need to get the set of tables that contain these columns
;; and then ensure that the N tables are connected via a known FK relationship
  (let [all-columns (db/columns-with-table schema-id domain-id)
        relevant-columns (filter #(some #{(:role %)} roles) all-columns)
        tables (set (map :tableid relevant-columns))
        ;; Set up a graph walk to find linked tables. Tables are vertices and edges are FKs
        ;; that link tables. Tables with no FKs will not be included in the graph.
        graph (->> all-columns
                   (filter #(not (empty? (:fk %)))) ; Only columns with FKs
                   (map #(hg/->edge (:id %) [(:tableid %)] 
                                    [(:parent_id (db/declaration-get db/columns (:fk %)))]))
                   (apply hg/graph)) 
        ;; We should be able to pick the first table in the list and then for any remaining tables
        ;; find a path from that first table. If we fail to find a path to one, then we have a missing join.
        table-pair-solutions (map #(assoc {:table-pair {:head (first tables) :tail %}} :solutions (hg/search graph [(first tables)] %)) (rest tables)) 
        ;; Cull this down to a list of missing joins
        missing-joins (->> table-pair-solutions
                           (filter #(empty? (flatten (:solutions %))))
                           (map :table-pair))
        ;; Aggregate the set of required tables for this transformation
        required-tables (if (> (count tables) 1) ;; Handle the case of a single table with no joins (table-pairs)
                          (->> table-pair-solutions
                               (map choose-solution)
                               (flatten)
                               ;; Get all the heads & tails so we pickS up any intermediate tables
                               (mapcat #(list (:heads %) (:tails %)))
                               (flatten)
                               ;; Remove duplicates
                               (set))
                          (set tables))]
    
    {:required-tables required-tables :missing-joins missing-joins}))

(defn detect-conflicts [conversion]
  (let [domain-id (:domain_id conversion)
        source-datastore-id (:source_datastore conversion)
        target-datastore-id (:target_datastore conversion)
        target-role-ids (remove empty? (s/split (:roles conversion) #","))
        source-schema-id (:schema_id (db/declaration-get db/datastores source-datastore-id))
        rf-graph (relational-graph domain-id)
        resolved-relational-conflicts (->> (detect-relational-conflicts domain-id source-schema-id
                                                                        target-role-ids)
                                           (map #(assoc % :solutions (resolve-relational-conflict 
                                                                      rf-graph domain-id source-schema-id %))))
        resolved-semantic-conflicts (->> (detect-semantic-conflicts domain-id source-datastore-id 
                                                                    target-datastore-id target-role-ids)
                                         (map #(assoc % :solutions (resolve-semantic-conflict
                                                                    domain-id (:modifier %) 
                                                                    (:source-mv %) (:target-mv %)))))
        required-tables (get-required-tables domain-id target-role-ids source-schema-id)] 
    (merge {:semantic-conflicts resolved-semantic-conflicts 
            :relational-conflicts resolved-relational-conflicts
            :target-roles target-role-ids}
           required-tables)))

