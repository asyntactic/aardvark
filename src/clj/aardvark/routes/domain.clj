(ns aardvark.routes.domain
  (:use compojure.core)
  (:require [aardvark.views.layout :as layout]
            [noir.session :as session]
            [noir.util.route :refer [restricted]]
            [taoensso.timbre :as log]
            [aardvark.routes.declaration :refer [list-declaration]]
            [clojure.string :as s]
            [aardvark.util :refer [message error]]
            [aardvark.models.db :as db]))

(defn domain-page [id]
  (let [domain (db/get-domain id)]
    (session/put! :my-domain id) ;; Looking at a domain selects it
    (message (str "Now working in domain " (:name domain)))
             (layout/render "domain.html" {:stats (db/get-domain-stats ((fnil s/trim "") id))
                                           :domain domain
                                           :users (db/domain-users id)})))

(defn domain-update [id name description]
  (let [id
        (if (empty? id)
          (db/create-domain name description)
          (db/update-domain id name description))]
    (domain-page id)))

(defn domain-delete [id]
  (db/delete-domain id)
  (domain-page nil)) ;; Just need a landing spot

(defn add-user [user-id domain-id]
  (cond (empty? user-id) (error "No user specified")
        (nil? (db/get-user user-id)) (error (str "No such user: " user-id))
        :default (db/domain-add-user user-id domain-id))
  (domain-page domain-id))

(defn remove-user [user-id domain-id]
  (db/domain-remove-user user-id domain-id)
  (domain-page domain-id))

(defroutes domain-routes
  (GET "/domain/get" [id] (restricted (domain-page id)))
  (GET "/domain/delete" [id] (restricted (domain-delete id)))
  (GET "/domain/add" [] (restricted (domain-page nil)))
  (GET "/domain/add-user" [id domain-id] (restricted (add-user id domain-id)))
  (GET "/domain/remove-user" [id domain-id] (restricted (remove-user id domain-id)))
  (POST "/domain/update" [id name description] (restricted (domain-update id name description))))


