(ns aardvark.routes.declaration
  (:use compojure.core)
  (:require [aardvark.views.layout :as layout]
            [noir.session :as session]
            [noir.util.route :refer [restricted]]
            [taoensso.timbre :as log]
            [clojure.string :as s]
            [aardvark.interop :as i]
            [aardvark.engine.template :as t]
            [aardvark.util :refer [message error interpose-splice domain-id]]
            [aardvark.models.db :as db]))

;;;; Encoding routes to perform general CRUD operations on all
;;;; declarations in the Ignite Documentation Model

(defn- entity-info [type]
  (case (keyword type)
    :entity {:db-entity db/entities :children [:role] :page "entity.html" :list-page "concept-model.html"}
    :role {:db-entity db/roles :children [] :page "role.html" :list-page :entity}
    :semantic-type {:db-entity db/semantic_types :children [:modifier] :page "semantic-type.html"
                    :list-page "semantic-type-list.html"}
    :modifier {:db-entity db/modifiers :children [] :page "modifier.html" :list-page :semantic-type}
    :context {:db-entity db/contexts :children [] :page "context.html" :list-page "context-list.html"}
    :modifier-value {:db-entity db/modifier_values :children [] :page "context.html" :list-page :context}
    :schema {:db-entity db/schemae :children [:table] :page "schema.html" :list-page "schema-list.html"}
    :table {:db-entity db/tables :children [:column] :page "table.html" :list-page :schema}
    :column {:db-entity db/columns :children [] :page "column.html" :list-page :table}
    :datastore {:db-entity db/datastores :children [] :page "datastore-list.html" :list-page "datastore-list.html"}
    :semantic-function {:db-entity db/semantic_functions :children [:formula-sf] :page "semantic-function.html"
                        :list-page "semantic-function-list.html"}
    :relational-function {:db-entity db/relational_functions :children [:formula-rf] :page "relational-function.html"
                        :list-page "relational-function-list.html"}
    :formula-sf {:db-entity db/formulas :children [] :page "formula.html" :list-page :semantic-function}
    :formula-rf {:db-entity db/formulas :children [] :page "formula.html" :list-page :relational-function}
    :conversion {:db-entity db/conversions :children [] :page "conversion.html" :list-page "conversion-list.html"}))

(defn- special-cases-list [type parent-id]
  "Get special-case variables to be included in pages for lists"
  (case (keyword type)
    :datastore {:declarations (db/declaration-list (:db-entity (entity-info :datastore)) nil (domain-id) false)
                :schemas (db/declaration-list (:db-entity (entity-info :schema))
                                              parent-id (domain-id) true)
                :contexts (db/declaration-list (:db-entity (entity-info :context))
                                               parent-id (domain-id) true)}
    :semantic-function {:modifiers (db/modifiers-with-st (domain-id))}
    {}))

(defn list-declaration [type parent-id render? abbreviated?]
  (let [info (entity-info type)
        dec-list (db/declaration-list (:db-entity info) parent-id (domain-id) abbreviated?)]
    (when (= 0 (domain-id)) (log/warn "Could not retrieve active domain from session"))
    (if render?
      (let [page (:list-page info)]
        (try
          (layout/render page (merge {:declarations dec-list}
                                     (special-cases-list type parent-id)))
          (catch NullPointerException e (log/error (str "Problem rendering page " page)))))
      dec-list)))

(defn- combine-modifiers-mvs [context-id]
  "Get a list of modifiers with the modifier values for a given context
  attached (using the :mv key)"
  (let [mvs (list-declaration :modifier-value context-id false false)
        all-mvs (list-declaration :modifier-value nil false false)
        modifiers (db/modifiers-with-st (domain-id))]
    (map (fn [modifier]
           (let [mv (first (filter #(= (:id modifier) (:modifier %)) mvs))]
             (assoc modifier :mv mv :existing-values
               (filter #(= (:modifier %) (:id modifier)) all-mvs))))
         modifiers)))

(defn- get-formula-variables [fid]
  "Get all the variables (v:*) defined in formulae belonging to a given S/R function."
  (let [form (db/declaration-list db/formulas fid (domain-id) false)
        raw-args (if (empty? (:arguments form)) () (flatten (map #(s/split (:arguments %) #"\r\n") form)))
        vars (filter #(and (< 2 (count %)) (= "v:" (.substring % 0 2))) raw-args)]
    (map #(.substring % 2) vars)))

(declare get-declaration)

(defn- special-cases-get [type id parent-id]
  "Get special-case variables to be included in pages for gets"
  (case (keyword type)
    :role {:entities (list-declaration :entity nil false true)
           :semantic-types (list-declaration :semantic-type nil false true)}
    :modifier {:semantic-types (list-declaration :semantic-type nil false true)}
        ;; We need to attach the relevant MV as a child of the modifier in order to render properly
    :context {:modifiers (combine-modifiers-mvs id)}
    :semantic-type {:sts (list-declaration :semantic-type nil false true)
                    :inherited-modifiers (db/inherited-modifiers id)}
    :modifier-value {:declaration (get-declaration :context parent-id false) ;; MV is embedded in context page, so reload context.
                     :modifiers (combine-modifiers-mvs parent-id)}
    :column {:roles (filter #(empty? (:role_type %)) (db/roles-with-entity (domain-id)))
             :columns (db/columns-with-table (db/get-schema-for-column id) (domain-id))}
    :datastore {:declarations (db/declaration-list (:db-entity (entity-info :datastore)) nil (domain-id) false)
                :schemas (db/declaration-list (:db-entity (entity-info :schema))
                                              parent-id (domain-id) true)
                :contexts (db/declaration-list (:db-entity (entity-info :context))
                                              parent-id (domain-id) true)
                } ;; Datastore list & edit on same page, so supply the list when we've arrived as a single DS view
    :semantic-function {:modifiers (db/modifiers-with-st (domain-id))}
    :formula-sf {:formula-type "formula-sf" ; A little bootstrapping for the UI since a new formula won't know its type yet
                 :parent-type "semantic-function"
                 :variables (get-formula-variables parent-id)
                 :library (t/library i/default-template)}
    :formula-rf {:formula-type "formula-rf" ; A little bootstrapping for the UI since a new formula won't know its type yet
                 :parent-type "relational-function"
                 :variables (get-formula-variables parent-id)
                 :library (t/library i/default-template)
                 :roles (db/roles-with-entity (domain-id))
                 :mods (db/modifiers-with-st (domain-id))}
    :conversion {:roles (db/roles-for-target (domain-id) (db/target-schema id))
                 :datastores (list-declaration :datastore nil false true)}
    {}))

(defn get-declaration [type id render?]
  (let [info (entity-info type)
        children (:children info)
        dec (db/declaration-get (:db-entity info) id)
        parent-id (:parent_id dec)]
    (if render?
      (layout/render (:page info)
                     (merge
                      {:declaration dec
                       :children (reduce merge {} (map
                                                  (fn [child]
                                                    {child (list-declaration child id false false)}) children))}
                      (special-cases-get type id parent-id)))
        ;; Result: {:children {:foo {:declarations [...]}
        ;;                     :bar {:declarations [...]}}}
      dec)))

(defn put-declaration [type params]
  (let [domain-id (or (session/get :my-domain) 0)
        info (entity-info type)
        children {:children info}
        id (db/upsert-with-ordering (:db-entity info) (merge {:domain_id domain-id}
                                               ;; Replace empty strings with nil for DB
                                               (reduce (fn [new-map [k v]]
                                                         (assoc new-map k
                                                           (if (and (seq? v) (empty? v)) nil v))) {}
                                                       (select-keys params (db/footprint (:db-entity info))))))]
    (get-declaration type id true)))

(defn swap-up-declaration [type id]
  (let [info (entity-info type)
        parent-id (:parent_id (db/declaration-get (:db-entity info) id))]
    (db/swap-up-ordering (:db-entity info) id)
    (if (keyword? (:list-page info)) ;; Some entities use a get of a parent entity rather than a standalone list
      (get-declaration (:list-page info) parent-id true)
      (list-declaration type parent-id true true))))

(defn remove-declaration [type id]
  (let [domain-id (or (session/get :my-domain) 0)
        info (entity-info type)
        parent-id (:parent_id (db/declaration-get (:db-entity info) id))]
    (db/declaration-remove (:db-entity info) id)
    (if (keyword? (:list-page info)) ;; Some entities use a get of a parent entity rather than a standalone list
      (get-declaration (:list-page info) parent-id true)
      (list-declaration type parent-id true true))))

;;; Special cases to handle conversions (roles)
(defn remove-conversion-role [id role]
  (let [conversion (get-declaration :conversion id false)
        r (:roles conversion)
        roles (if (nil? r) ()
                (s/split r #","))
        new-roles (s/trim (interpose-splice "," (filter #(not (= role %)) roles)))]
    (put-declaration :conversion (assoc conversion :roles new-roles))))

(defn add-conversion-role [id role]
  (let [conversion (get-declaration :conversion id false)
        r (:roles conversion)
        roles (if (nil? r) ()
                (into [] (set (s/split r #",")))) ; set prevents dupes
        new-roles (s/trim (interpose-splice "," (conj roles role)))]
    (put-declaration :conversion
                     (assoc conversion :roles new-roles))))

;;; Routes
(defroutes declaration-routes
  (ANY "/declaration/put" {params :params} (put-declaration (:type params) params)) ;; Add or update, depending on presence of id
  (ANY "/declaration/get" [type id render] (get-declaration type id render))
  (ANY "/declaration/list" [type parent-id render full] (list-declaration type parent-id render (not full)))
  (ANY "/declaration/swap-up" [type parent-id id] (swap-up-declaration type id))
  (ANY "/declaration/remove" [type id] (remove-declaration type id))
  (GET "/conversion/removerole" [id role] (remove-conversion-role id role))
  (POST "/conversion/addrole" [id role] (add-conversion-role id role)))


