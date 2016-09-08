(ns aardvark.engine.compiler
  (:use [aardvark.engine.template])
  (:require [taoensso.timbre :as log]
            [aardvark.models.db :as db]
            [aardvark.engine.mediator :as m]
            [aardvark.engine.pipeline :as p]
            [clojure.string :as s]))

;; Loop through the tables, joining 1 to 2, then 2 to 3, etc.
(defn- process-joins [template pipeline tables]
  (loop [bootstrap (first tables) others (rest tables) p pipeline]
    (if (empty? others) p
        (recur (first others) (rest others) (join-tables template p bootstrap (first others) 
                                        ; Only inner joins supported at present
                                                         :inner)))))

;; Pull the solutions from conflicts of both types, throwing 
;; an exception if any are unresolved (i.e. have no solutions).
;; For now, the order will be arbitrary, but this funtion provides
;; a hook that could be used for strategic analysis and ordering.
(defn- merge-solutions [conflicts]
  ;; Create a copy of attached Semantic Conflicts' resolutions (SFs) for each affected target role. 
  (let [semantic-conflicts (for [sc (:semantic-conflicts conflicts) role (m/filter-roles-for-modifier (:modifier sc) (:target-roles conflicts))]
                             {:type :semantic :target-role role :solution (first (:solutions sc))})]
    ;; Now we have decorated solutions, but we need to flatten this into a list of (decorated) functions
    (for [sol (as-> (concat 
                      semantic-conflicts
                      (map #(assoc {:type :relational} :solution (first (:solutions %))) (:relational-conflicts conflicts))) solns
                      (if (some #(nil? (:solution %)) solns)
                        (throw (IllegalArgumentException. "Cannot compile a conversion with unresolved conflicts."))
                        solns)) func (:solution sol)]
      {:type (:type sol) :target-role (:target-role sol) :id func})))

(defn- parse-var [varstring]
  (let [parts (s/split varstring #":")]
    (if (= 1 (count parts))
      {:flags "" :value varstring}
      {:flags (.toLowerCase (first parts))
       :value (second parts)})))

(def output-variable-name "RETURN_VALUE")
(def argument-source-modifier "SOURCE_MODIFIER")
(def argument-target-modifier "TARGET_MODIFIER")
(def argument-source-value "SOURCE_VALUE")
(def operation-assign-constant "ASSIGN_CONSTANT")

;; Parse the formula/arg notation into a list of formulas with an arg map
(defn- deserialize-function [domain-id function]
  (let [function-id (:id function)] 
    (when-not (nil? function-id)
      (let [suffix (gensym "_")] ; Unique suffix for this function to help scope internal variables between formulae
        (->> (db/declaration-list db/formulas function-id domain-id false)
             (map (fn [formula]
                    (let [parts (parse-var (:output formula))
                          output (:value parts) 
                          ;; Subtitute values for SF Return Value or mapping to a role
                          output (cond (and (= :semantic (:type function)) 
                                            (= output output-variable-name))
                                       (:target-role function)
                                       (.contains (:flags parts) "r")
                                       output
                                       :else 
                                       (str output suffix)) ; A variable - make unique to this function
                          ;; Parse arguments from UI notation
                          arguments  (as-> (:arguments formula) args
                                           (if (empty? args) nil (s/split-lines args))
                                           (map (fn [arg] 
                                                  (let [parts (parse-var arg)
                                                        quoted? (.contains (:flags parts) "q")
                                                        constant? (.contains (:flags parts) "c")
                                                        role? (.contains (:flags parts) "r")
                                                        modifier? (.contains (:flags parts) "m")
                                                        value (:value parts)]
                                                    {:value value :quoted quoted? :constant constant? :role role?})) args)
                                           ;; Substitute source role or modifier values for special SF constants
                                           (map (fn [arg]
                                                  (let [prepped-arg (if (= :semantic (:type function))
                                                                      ;; Semantic function
                                                                      (let [sf (db/declaration-get db/semantic_functions (:id function))]
                                                                        (cond
                                                                         (= (:value arg) argument-source-value) (assoc arg :role true :value (:target-role function))
                                                                         (= (:value arg) argument-source-modifier) (assoc arg :constant true :value (:source_mv sf))
                                                                         (= (:value arg) argument-target-modifier) (assoc arg :constant true :value (:target_mv sf))
                                                                         :else arg))
                                                                      ;; Relational function
                                                                      arg)]
                                                    (if (or (:constant prepped-arg) (:role prepped-arg))
                                                      prepped-arg ; Don't add the scoping suffix to constants - these are global & we expect exact inline values
                                                      (assoc prepped-arg :value (str (:value prepped-arg) suffix))))) 
                                                args))]
                      {:output output :arguments arguments :operation (:operation formula) :suffix suffix})))))))) 

(defn compile-conversion [conversion template]
  "Compile a given conversion into an executable program given a specific template.
   detect-conflicts will be run first, and the compilation will fail if there are 
   any unresolved conflicts or missing joins."

  (let [conflicts (m/detect-conflicts conversion)
        ;; TODO validate & croak with any errors - unresolved conflicts or missing joins
        ;; Unresolved conflicts will throw an illegalargumentexception in merge-solutions below.
        required-tables (:required-tables conflicts)
        ]

    (as-> (p/make-pipeline (:domain_id conversion)) pipeline

          ;; Head
          (head template pipeline)
          
          ;; Load tables
          (reduce (fn [p table] (load-table template p table)) pipeline required-tables) 
          
          ;; Joins
          (process-joins template pipeline required-tables)
          
          ;; Apply functions
          (let [formulae (->> conflicts
                              ;; Aggregate solutions for different conflicts into a single list. Note that
                              ;; these are maps containing Function IDs, which are parents to formulae 
                              ;; (along with some other metadata consumed by deserialize-function) - see merge-solutions
                              (merge-solutions) 
                              ;; Parse the functions that make up solutions into a list 
                              ;; of formula with processed args/output that we can pass(def cnv m/conv1)
                              ;; into the template. This will also subtitute values for semantic function
                              ;; constants, and call template/constant to track these.
                              (map (partial deserialize-function (:domain_id conversion)))
                              (flatten))]
            (reduce (fn [p f]
                      (if (= (:operation f) operation-assign-constant)
                        (constant template p (:output f) (:value (first (:arguments f))))
                        (formula template p (:operation f) (:arguments f) {:value (:output f)}))) pipeline formulae))

          ;; Tail       
          (tail template pipeline (:target-roles conflicts))
          
          (:output pipeline))))


