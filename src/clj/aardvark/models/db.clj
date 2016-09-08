(ns aardvark.models.db
  (:use [korma.core]
        [korma.db])
  (:import [java.sql Timestamp]
           [java.util UUID])
  (:require [aardvark.models.schema :as schema]
            [taoensso.timbre :as log]))

(defdb db schema/db-spec)

;; Note, we keep history; using technique described at:
;; http://nyeggen.com/blog/2014/02/01/immutable-sql/
;; except instead of version integer, we'll use
;; valid-from and valid-to fields
;;
;; The following utility functions help support this
;; history scheme.

(defn- current-query [base]
  "Compose a base query to return only the newest/current version of a given entity"
  (-> base
      (where {:valid_to nil})
      (order :valid_from :ASC)
      (select)))

(defn current [entity id]
  "Select the single newest instance of the provided entity by id"
  (first
   (current-query (-> (select* entity) (where {:id id}) (limit 1)))))

(defn remove-entity-by-id [entity-type id]
  "Remove an entity from the current snapshot (expire its valid_to field)"
  (update entity-type
          (set-fields {:valid_to (sqlfn now)})
          (where {:id id :valid_to nil})))

(defn upsert [entity-type entity-instance]
  "Insert/update (depending on presence of :id key) any entity and return the id"
  (let [id (:id entity-instance)]
    (if-not (empty? id)
      (transaction ;; Update
       (remove-entity-by-id entity-type id) ;; Set the old current record to invalid
       (insert entity-type
               (values
                (dissoc entity-instance :valid_from)))
       id) ;; We need a new valid_from tag (defaults to now)
      (let [id (str (UUID/randomUUID))]
        (insert entity-type ;; New entity; insert
                (values (assoc entity-instance :id id)))
        id))))

;;; Set up entities

(declare users domains)

(defentity users
  (many-to-many domains :users_domains
                {:lfk :users_id
                 :rfk :domains_id}))

(defentity domains
  (many-to-many users :users_domains
                {:lfk :domains_id
                 :rfk :users_id}))

(defentity users_domains)

;;; DB operations

(defn create-user [user]
  (insert users
          (values user)))

(defn toggle-user-active [id]
  (exec-raw ["update users set disabled = !disabled where id = ?;" [id]]))

(defn update-user [id first-name last-name email]
  (update users
          (set-fields (merge {:first_name first-name
                              :last_name last-name
                              :email email}))
          (where {:id id})))

(defn update-login-timestamp [userid]
  (update users
          (set-fields {:last_login (Timestamp. (System/currentTimeMillis))})
          (where {:id userid})))

(defn update-password [userid password]
  (update users
          (set-fields {:pass password})
          (where {:id userid})))

(defn get-users []
  (select users (order :id :asc)))

(defn get-user [id]
  (first (select users
                 (where {:id id})
                 (limit 1))))

(defn create-domain [name description]
  (let [id (str (UUID/randomUUID))]
    (insert domains (values {:id id
                             :name name
                             :description description}))
    id))

(defn domain-users [domain-id]
  (filter #(not (empty? (:domains %))) ;; A hack to get domain members only
          (select users
                  (fields :id)
                  (order :id :ASC)
                  (with domains
                    (fields :id)
                    (where {:id domain-id})))))

(defn domain-add-user [user-id domain-id]
  (insert users_domains (values {:users_id user-id
                                 :domains_id domain-id})))

(defn domain-remove-user [user-id domain-id]
  (delete users_domains (where {:users_id user-id
                                :domains_id domain-id})))

(defn update-domain [id name description]
  (update domains
          (set-fields {:name name
                       :description description})
          (where {:id id}))
  id)

(defn get-domain [id]
  (first
   (select domains
          (with users)
          (limit 1)
          (where {:domains.id id}))))

(defn delete-domain [id]
  (update domains
          (set-fields {:disabled true})
          (where {:id id})))

(defn get-domains
  ([] (select domains (where {:disabled [not= true]})))
  ([user-id] (let [domain-ids (map #(:domains_id %) (select users_domains (where {:users_id user-id})))]
              (select domains (where {:disabled [not= true]})
                      (where (in :id domain-ids))))))

;;; Handle declarations in a consistent manner across the application

;; Note that declarations do not support DB constraints.
;; This is intentional, as we want users to be able to add/remove willy-nilly.
;; The application will handle (even detect and report!) missing constraints.
;; This is a feature.
(defmacro declaration [name & fields]
  `(defentity ~name
     (fields ~@(concat [:id :valid_from :valid_to :domain_id :parent_id :name :description :ordering] fields))))

(defn footprint [type]
  "Get the field footprint (minus timestamps, but including :id and :domain_id) of a declaration"
  (-> (set (:fields type))
      (disj :valid_from)
      (disj :valid_to)))

(declaration entities)
(declaration roles :role_type :semantic_type)
(declaration semantic_types)
(declaration modifiers :value_type)
(declaration contexts)
(declaration modifier_values :value :modifier)
(declaration schemae)
(declaration tables)
(declaration columns :role :fk :join_type)
(declaration datastores :schema_id :context_id)
(declaration semantic_functions :source_mv :target_mv :modifier)
(declaration relational_functions)
(declaration arguments :variable :role_id :quoted :arg_order)
(declaration formulas :output :operation :arguments :function_type)
(declaration conversions :source_datastore :target_datastore :roles) ;; Roles are ,-separated UUIDs

(defn declaration-list [type parent-id domain-id abbreviated-fields?]
  (current-query
   (let [q (-> (select* type)
               (order :ordering :ASC) ;; Order first by weight (ordering field), second by name
               (order :name :ASC)
               (where (merge {:domain_id domain-id}
                             (when-not (nil? parent-id)
                               {:parent_id parent-id}))))
         q (if abbreviated-fields?
             (-> q (fields :id :name :description))
             q)]
     q)))

(defn get-domain-stats [id]
  {:entities (count (declaration-list entities nil id true))
   :roles (count (declaration-list roles nil id true))
   :semantic-types (count (declaration-list semantic_types nil id true))
   :schemas (count (declaration-list schemae nil id true))
   :contexts (count (declaration-list contexts nil id true))
   :datastores (count (declaration-list datastores nil id true))
   :functions (+ (count (declaration-list semantic_functions nil id true))
                 (count (declaration-list relational_functions nil id true)))
   :conversions (count (declaration-list conversions nil id true))})

(defn declaration-get [type id]
  (current type id))

(defn declaration-remove [type id]
  (remove-entity-by-id type id))

(defn get-schema-for-column [column-id]
  "Get the ID of the schema that contains a given column"
  (:id (first
        (current-query
               (-> (select* schemae)
                   (fields :id)
                   (where {:columns.id column-id})
                   (where {:columns.parent_id :tables.id})
                   (where {:tables.parent_id :schemae.id})
                   (join :inner tables (and
                                 (= nil :tables.valid_to)
                                 (= :tables.parent_id :id)))
                   (join :inner columns (and
                                  (= nil :columns.valid_to)
                                  (= :columns.parent_id :tables.id)))
                   (limit 1))))))

(defn columns-with-table [schema-id domain-id]
  "Get id and names (with table name) of all columns in the schema"
  (current-query
   (-> (select* columns)
       (fields :id :name :fk [:tables.name :tablename] [:tables.id :tableid] :role)
       (where {:tables.parent_id schema-id})
       (where {:domain_id domain-id})
       (order :ordering :ASC)
       (order :tablename :ASC)
       (order :name :ASC)
       (join :inner tables (and
                     (= nil :tables.valid_to)
                     (= :tables.id :parent_id))))))

(defn roles-with-entity [domain-id]
  "Get id and names (with entity name) of all roles"
  (current-query
   (-> (select* roles)
       (fields :id :name :role_type [:entities.name :entityname])
       (where {:domain_id domain-id})
       (order :entityname :ASC)
       (order :ordering :ASC)
       (order :name :ASC)
       (join :inner entities (and
                       (= nil :entities.valid_to)
                       (= :entities.id :parent_id))))))

(defn in?
  "true if seq contains elm"
  [seq elm]
  (some #(= elm %) seq)) ;; TODO move to util

(defn roles-for-target [domain-id schema-id]
  (let [col-roles (map #(:role %) (columns-with-table schema-id domain-id))
        roles (roles-with-entity domain-id)]
        (filter #(in? col-roles (:id %)) roles)))

(defn target-schema [conversion-id]
  "Get the target schema for a given conversion"
  (let [datastore-id (:target_datastore (declaration-get conversions conversion-id))]
    (:schema_id (declaration-get datastores datastore-id))))

(defn modifiers-with-st [domain-id]
  "Get id and names (with ST name) of all modifiers"
  (current-query
   (-> (select* modifiers)
       (fields :id :name [:semantic_types.name :st_name])
       (where {:domain_id domain-id})
       (order :ordering :ASC)
       (order :st_name :ASC)
       (order :name :ASC)
       (join :inner semantic_types (and
                       (= nil :semantic_types.valid_to)
                       (= :semantic_types.id :parent_id))))))

(defn get-modifiers-for-st [st-id]
  "Get all modifiers belonging to a given Semantic Type; include the ST's name as a field."
  (current-query
   (-> (select* modifiers)
       (fields :id :name [:semantic_types.name :st_name] :description)
       (where {:parent_id st-id})
       (order :ordering :ASC)
       (order :st_name :ASC)
       (order :name :ASC)
       (join :inner semantic_types (and
                       (= nil :semantic_types.valid_to)
                       (= :semantic_types.id :parent_id))))))

(defn- ancestor-sts
  "Get all STs that are ancestors to the one given"
  ([st-id include?] ;; include? the st in question
   (let [st (declaration-get semantic_types st-id)]
     (ancestor-sts st (if include? (hash-set st) (hash-set)) include?)))
  ([st result include?]
   (let [parent (declaration-get semantic_types (:parent_id st))]
     (if (or (empty? parent)
             (contains? result parent)) ; Avoid cycles
       result
       (recur (:id parent) (conj result parent) include?)))))

(defn inherited-modifiers [st-id]
  "Get all inherited modifiers via ancestors STs"
  (->> (ancestor-sts st-id false) ; Ancestor ST maps
      (map #(get % :id)) ; ST ids
      (map get-modifiers-for-st) ; Nested collections of modifiers
      (flatten)))

(defn get-sfs-for-modifier [domain-id modifier-id]
  "Get all SFs that link to a given modifier"
  (current-query
   (-> (select* semantic_functions)
       (where {:domain_id domain-id
               :modifier modifier-id}))))

;;; Operations to manage declaration ordering
(defn upsert-with-ordering [type instance]
  "Perform an upsert, incrementing the ordering to the next available integer"
  (transaction
   (let [old-max (:ordering (first
                      (current-query (-> (select* type)
                                         (fields :ordering)
                                         (where {:domain_id (:domain_id instance)
                                                 :parent_id (:parent_id instance)})
                                         (limit 1)
                                         (order :ordering :DESC)))))
         max-id (if-not (nil? old-max) (+ 1 old-max) 0)
         instance (if (and (contains? instance :id) (not (= (:ordering instance) :reorder)))
                    (let [old-ordering (:ordering (declaration-get type (:id instance)))
                          old-ordering (if (nil? old-ordering) 0 old-ordering)]
                      (assoc instance :ordering old-ordering)); Update; leave the ordering alone
                    (assoc instance :ordering max-id))] ; Insert, needs an order
     (upsert type instance))))

 (defn swap-ordering [type id-1 id-2]
   "Swap the ordering values of two entities. If they are the same (error condition)
   resolve by bumping one of them to the next available ID (the end) and leave the other."
   (transaction
    (let [a (declaration-get type id-1)
          b (declaration-get type id-2)
          ord-a (:ordering a)
          ord-b (:ordering b)]
      (cond (= ord-a ord-b) ;; Error condition, just bump b to the end to resolve
            (upsert-with-ordering type (assoc b :ordering :reorder))
            :default
            (transaction
             (upsert type (assoc a :ordering ord-b))
             (upsert type (assoc b :ordering ord-a)))))))

 (defn swap-up-ordering [type id]
   "Swap the ordering of the provided item with whichever item came before it.
    If there was no previous item, do nothing."
   (transaction
     (let [item (declaration-get type id)
           order-item (:ordering item)
           previous (first (current-query (-> (select* type)
                                              (fields :ordering :id)
                                              (where {:domain_id (:domain_id item)
                                                      :parent_id (:parent_id item)
                                                      :id [not= id] ; in the equal order case, makes sure we swap with a different declaration, not self
                                                      :ordering [<= order-item]}) ; helps us resolve equal ordering, which is a no-no
                                              (limit 1)
                                              (order :ordering :DESC))))
           p-id (:id previous)]
       (when p-id
         (swap-ordering type id p-id)))))
