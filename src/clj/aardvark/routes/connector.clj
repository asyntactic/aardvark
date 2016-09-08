(ns aardvark.routes.connector
  (:use compojure.core)
  (:require [noir.session :as session]
            [noir.response :refer [content-type set-headers]]
            [aardvark.views.layout :as layout]
            [noir.util.route :refer [restricted]]
            [taoensso.timbre :as log]
            [aardvark.util :refer [message error interpose-splice]]
            [aardvark.interop :as i]
            [aardvark.engine.mediator :as m]
            [aardvark.engine.compiler :as c]
            [clojure.string :as s]
            [aardvark.routes.declaration :as decl]
            [aardvark.models.db :as db]))

;;;; Routes and pages related to connector operations

(defn conflict-report [conversion-id]
  (try
    (let [conversion (db/declaration-get db/conversions conversion-id)
          conflicts (m/detect-conflicts conversion)
          semantic-conflicts (:semantic-conflicts conflicts)
          relational-conflicts (:relational-conflicts conflicts)
          resolved? (reduce #(and %1 %2) (map #(< 0 (count (:solutions %))) (concat semantic-conflicts relational-conflicts)))
          missing-joins? (< 0 (count (:missing-joins conflicts)))]
      (if missing-joins?
        (do
          (log/warn (str "Missing Join"))
          (error "Missing Join - Check Foreign Keys")
          (decl/get-declaration "conversion" conversion-id true)))
        (layout/render "conflict-report.html" {:semantic-conflicts semantic-conflicts
                                               :relational-conflicts relational-conflicts
                                               :all-resolved resolved?
                                               :conversion-id conversion-id
                                               :conversion-name (:name conversion)}))))

 (defn deploy [conversion-id]
    (let [conversion (db/declaration-get db/conversions conversion-id)
          result (c/compile-conversion conversion i/default-template)]
      (interpose-splice "\n" result)))

(defroutes connector-routes
  (GET "/connector/conflict-report" [id] (restricted (conflict-report id)))
  (GET "/connector/deploy" [id] (->> (deploy id)
                                    ;; May need to add ;base64 to the content-type for binary connector outputs
                                    (content-type "application/octet-stream")
                                    (set-headers {"Content-Disposition" "attachment; filename=\"aardvark.txt\""})
                                    (restricted))))
