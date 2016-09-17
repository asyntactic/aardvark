(ns aardvark.engine.pipeline
  (:require [aardvark.models.db :as db]
            [taoensso.timbre :as log]))

;;;; The additive set of data fields in a transformation. Fields are indexed 
;;;; primarily by the underlying role that they represent. Where applicable, 
;;;; fields are also optionally indexed by sourcing column. Consider that
;;;; a given logical field in a typical transformation may be reassigned to 
;;;; different variables at each transformative step, but the logical field 
;;;; remains the same. This lineage is what we are tracking. 
 
;;;; Special constant fields are separately indexed by a permanent user-provided
;;;; field name. These fields do not "evolve" as do role/column based fields.

;;;; The compiler also utilizes the pipeline to store in-progress state when
;;;; computing an output. Specific templates may use this field :output as 
;;;; applicable (some may instead opt to call out to e.g. external APIs to 
;;;; generate this state).

(defn make-pipeline [domain-id]
  {:domain-id domain-id :columns-by-field {} :fields-by-role {} :all-fields #{} :constants #{} :last-added nil 
   :output [] :register nil})
;; The register is just a convenient bucket in which a template might keep some arbitrary state between functional calls

(defn add-constant [pipeline constant] 
  "Add a static field to the pipeline, representing a global constant"
  (update-in pipeline [:constants] conj constant))

(defn- add-field [pipeline name role-id]
  (-> pipeline
      (update-in [:fields-by-role] assoc role-id name)
      (update-in [:all-fields] conj name)
      (assoc :last-added name)))

(defn add-field-by-column [pipeline source-column-id]
  "Add a new field to the pipeline correlated with the provided column and its assigned role.
   The generated field name is stored in :last-added"
  (let [role-id (-> (db/declaration-get db/columns source-column-id)
                    :role)
        name (gensym)]
    (log/debug "Adding field " name " for column " source-column-id " with role " role-id)
    (-> pipeline
        (update-in [:columns-by-field] assoc name source-column-id)
        (add-field name role-id))))

(defn add-field-by-role [pipeline role-id]
  "Add a new field to the pipeline indexed via the provided role (and not indexed by column)
   The generated field name is stored in :last-added"
  (let [name (gensym)]
    (log/debug "Adding field " name " for role " role-id)
    (add-field pipeline name role-id)))

(defn extend-role [pipeline role-id]
  "Extend the progression of a role through the pipeline by assigning it a new field name (returned in :last-added)"
  (let [original-field (-> pipeline :fields-by-role (get role-id))
        column-id (-> pipeline :columns-by-field (get original-field))
        new-name (gensym "R")]
    (if (empty? column-id) ;; Is this really an add instead of an extend?
      (add-field-by-role pipeline role-id)
      (-> pipeline
          (update-in [:columns-by-field] assoc new-name column-id)
          (add-field new-name role-id)))))

(defn append [pipeline item]
  "Append a new line to the output state"
  (update-in pipeline [:output] #(conj % item)))


