(ns aardvark.engine.template
  (:require [aardvark.models.db :as db]
            [aardvark.engine.pipeline :as p]
            [taoensso.timbre :as log]))

(defprotocol template 
  "A compiler template targetting a specific data processing runtime"
  (template-name [_] "Return a unique, human-readable name for this template.")
  ;; All functions below are expected to return the pipeline
  (head [_ pipeline] "Generate any boilerplate header required by the target.")
  (load-table [_ pipeline table] "Load a table from its underlying physical source and create pipeline fields for each column that correspond to both the original column and the column's role")
  (join-tables [_ pipeline primary secondary join-type] "Join two tables together using any defined FK relationship. A join does not alter the set of pipeline variables, so pipeline is unnecessary. Available join types: :inner")
  ;; Name - name of function to apply
  ;; Arguments - a map of arguments (constant names, pipeline field names, or role IDs) boolean flags, which indicate whether to quote the value of ordered function arguments. {:argument "value" :quoted true}
  ;; target The name (of a pipeline field) corresponding to the output role
  (formula [_ pipeline name arguments target] "Execute a formula (sub-function) and add the result to the pipeline. The implementation of this function should determine whether the named formula is provided by the execution platform directly or must be routed to an external user-defined function.")
  (constant [_ pipeline field value] "Add a constant value to the pipeline. If the constant exists already, then the value is overwritten by the new value.")
  (tail [_ pipeline target-roles] "Perform any required post-processing, including persisting the result")
  (library [_] "Return a footprint of library functions: [{:name \"func-name\" :arguments [\"arg1\" \"arg2\" \"arg3\"]} ...]"))

(defmacro deftemplate [name & protocol-impl]
  "Creates a named builder function that can be provided to the compiler, allowing it 
   to keep a named index of all templates without needing to instantiate any except the
   one being used."
  `(do
     (def ~'template-id ~(keyword name))
     (def ~'template-instance (fn []
                                (reify template 
                                  (~'template-name [~'this] ~(str name))
                                  ~@protocol-impl)))))

;;; Below are partial implementations of the template protocol that
;;; provide automatic management of pipeline state. Using these functions
;;; assumes that the target system can be instructed to utilize arbitrary
;;; fields with names that are generated here. If that is not the case, 
;;; then these implementations should not be used and all applicable logic
;;; will need to be created as part of the template implementation.
;;
;; Note that in most cases, the pipeline works with declaration IDs but 
;; the template implementation will be given names (e.g. column names)
;; instead, since IDs are internal indexes and the names are the values that
;; the external data system understands.

(defmacro lib [library name & arguments]
  `(conj ~library {:name ~(str name) :arguments [~@(map str arguments)]}))

(defn columns-for-table [domain-id table] 
  (as-> (db/declaration-list db/columns table domain-id false) cols
       (filter :role cols) ; We only need columns with assigned roles (TODO: what about FK columns for p-load?)        
       (reduce #(assoc %1 (:id %2) (:name %2)) {} cols)))

(defmacro p-load [pipeline table & body]
  `(let [~'columns (columns-for-table (:domain-id ~'pipeline) ~'table)
         ~'pipeline (reduce #(p/add-field-by-column %1 (key %2)) ~'pipeline ~'columns)
         ~'columns (->> ~'pipeline
                       :columns-by-field
                       (filter #(some #{(val %)} (map key ~'columns))) ; ([alias id] ...)
                       (map #(vector (first %) (get ~'columns (second %))))) ; convert columns from id to name
         ~'table (:name (db/declaration-get db/tables ~'table))] ; Convert table from ID to name
     ~@body))

(defmacro p-join-expressions [pipeline primary secondary & body]
  "Take two tables and determine on which column(s) to join them. Returns the 
   relevant columns from the primary table, which contain FK links to the 
   relevant column IDs from the secondary table. This method does not alter
   the pipeline."
  ;; Note for future development, we did have to plot out a column-wise
  ;; solution in medatior/get-required-tables. There is an opportunity 
  ;; to capture and reuse that information instead of re-calculating it
  ;; here, though the effort/benefit ratio for actually doing this seems dubious.

  `(let [~'primary-cols-with-any-fks (->> 
                               (db/declaration-list db/columns ~'primary (:domain-id ~'pipeline) false)
                               (filter #(not (empty? (:fk %)))))

        ~'secondary-cols-with-fks (->>
                                 (db/declaration-list db/columns ~'secondary (:domain-id ~'pipeline) false)
                                 (filter #(not (empty? (:fk %))))
                                 (map :id))
        ~'primary-join-cols (->> ~'primary-cols-with-any-fks
                               (filter (fn [~'pcol] (some #{(:fk ~'pcol)} ~'secondary-cols-with-fks)))
                               (map (fn [~'col] (assoc ~'col :fk-name (:name (db/declaration-get db/columns (:fk ~'col)))))))
        ~'primary (:name (db/declaration-get db/tables ~'primary))
        ~'secondary (:name (db/declaration-get db/tables ~'secondary))]
     ~@body))

(defmacro p-formula [pipeline name arguments target & body]
  ;; 1. update the pipeline
  ;; If we have a new role id in any arguments, we add this to the pipeline
  `(let [;; Update the pipeline for each constant argument
         ~'pipeline (reduce (fn [~'pipeline ~'arg] 
                              (if (:constant ~'arg)
                                (p/add-constant ~'pipeline (:value ~'arg))
                                ~'pipeline )) 
                            ~'pipeline ~'arguments)

         ;; 2. process the arguments 
         ;; if an arg is a constant, leave it alone. 
         ;; if an arg is a role, then substitute the roleid with the field name from the pipeline. Set quoting to false.
         ~'arguments (map (fn [~'arg] (if (:role ~'arg)
                                        (-> ~'arg
                                            (assoc :value (get (:fields-by-role ~'pipeline) (:value ~'arg)))
                                            (assoc :quoted false))
                                        ~'arg)) 
                          ~'arguments)

         ;; 3. Check target. If it's a role, do an extend and set target = field. 
         ~'target-type (if (db/declaration-get db/roles (:value ~'target)) :role :constant)
         ~'pipeline (if (= :role ~'target-type) 
                      (p/extend-role ~'pipeline (:value ~'target))
                      ~'pipeline)
         ~'target (cond (= :role ~'target-type) 
                        (assoc ~'target :value (get (:fields-by-role ~'pipeline) (:value ~'target)))
                        :else ~'target)]
     ~@body))

(defn p-constant [pipeline field]
  (p/add-constant pipeline field))

(defmacro p-tail [pipeline target-roles & body]
  `(let [~'target-roles (map #(let [~'field 
                                    (get (:fields-by-role ~'pipeline) %)]
                                [~'field (:name (db/declaration-get db/roles %))]) ~'target-roles)]
     ~@body))

(defn csv [args]
  "Create a comma-separated string from the provided arguments"
  (if (or (nil? args) (empty? args)) "" 
    (reduce str (interpose ", " args))))
