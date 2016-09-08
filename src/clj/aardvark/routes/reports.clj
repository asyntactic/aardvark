(ns aardvark.routes.reports
  (:use compojure.core)
  (:require [aardvark.views.layout :as layout]
            [noir.session :as session]
            [noir.util.route :refer [restricted]]
            [noir.response :refer [content-type set-headers]]
            [taoensso.timbre :as log]
            [aardvark.routes.declaration :refer [list-declaration]]
            [clojure.string :as s]
            [tikkba.utils.dom :as tikkba]
            [lacij.layouts.layout :as l]
            [lacij.edit.graph :as g]
            [lacij.view.graphview :refer [export]]
            [aardvark.util :refer [message error domain-id]]
            [aardvark.models.db :as db]))

(defn concept-report []
  (layout/render "concept-report.html" {:domain-name (:name (db/get-domain (domain-id)))}))

(defn- in [coll item]
  (not (empty? (filter #(= % item) coll))))

(defn concept-graph []
  (let [entities (list-declaration :entity nil false false)
        all-roles (list-declaration :role nil false false)
        non-orphan-roles (filter (fn [role] (let [parent-id (:parent_id role) ;; Filter orphaned roles
                                                  entity-ids (map #(get % :id) entities)]
                                              (in entity-ids (s/trim parent-id)))) all-roles)]
    (tikkba/spit-str (:xmldoc (let [$ (reduce #(g/add-node %1 (keyword (:id %2)) (str "Entity: " (:name %2))
                                                           :style {:fill "lightblue"})
                                          (g/graph :width 800) entities)
                                    $ (reduce #(g/add-node %1 (keyword (:id %2)) (str "Role: " (:name %2))
                                                           :style {:fill "lightgreen"})
                                              $ non-orphan-roles)
                                    ;; Entities own roles
                                    $ (reduce #(g/add-edge %1 (keyword (gensym)) (keyword (:parent_id %2))
                                                           (keyword (:id %2)) "has"
                                                           :style {:stroke-dasharray "9, 5"})
                                              $ non-orphan-roles)
                                    ;; Some roles refer to entity via role_type
                                    $ (reduce (fn [gr ro] (let [rt (:role_type ro)]
                                                            (if (empty? rt)
                                                              gr
                                                              (g/add-edge gr (keyword (gensym))
                                                                          (keyword (:id ro)) (keyword rt)
                                                                          "is"
                                                                          :style {:stroke-dasharray "9, 5"}))))
                                                 $ non-orphan-roles)]
                                (-> $
                                    (l/layout :hierarchical :flow :out)
                                  (g/build)))))))

(defroutes report-routes
  (ANY "/reports/concept-graph" [] (->> (concept-graph)
                                        (content-type "image/svg+xml")
                                        (restricted)))
  (ANY "/reports/concept-report" [] (restricted (concept-report))))
