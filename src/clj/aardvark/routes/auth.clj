(ns aardvark.routes.auth
  (:use compojure.core)
  (:require [aardvark.views.layout :as layout]
            [noir.session :as session]
            [noir.response :as resp]
            [noir.util.crypt :as crypt]
            [taoensso.timbre :as log]
            [noir.util.route :refer [restricted]]
            [aardvark.util :refer [message error]]
            [aardvark.models.db :as db]))

;;;; Authentication and user management

(defn user-access [request]
  (session/get :user-id))

(defn superadmin-access [request]
  (let [userid (user-access request)]
    (:superadmin (db/get-user userid))))

(defn superadmin? []
  "Determine whether the current user is a superadmin"
  (let [id (session/get :user-id)]
    (:superadmin (db/get-user id))))

(defn create-superadmin []
  (db/create-user {:id "admin" :pass (crypt/encrypt "admin") :superadmin true}))

(defn profile [id]
  (layout/render
   "profile.html"
   {:user (db/get-user id)}))

(defn new-user [id]
  (if (empty? id)
    (do
      (error "Username required")
      (layout/render "users.html" {:users (db/get-users)}))
    (do
      (db/create-user {:id id :pass (crypt/encrypt "changeme")})
      (profile id))))

(defn user-list []
  (layout/render "users.html" {:users (db/get-users)}))

(defn toggle-active [id]
  (db/toggle-user-active id)
  (user-list))

(defn update-profile [{:keys [id first-name last-name email password]}]
  (let [sa (superadmin?)]
    (when (or sa (= id (session/get :user-id))) ;; only a superadmin can change another user
      (db/update-user id first-name last-name email)
      (when-not (empty? password)
        (db/update-password id (crypt/encrypt password)))
      (message "User updated")
      (profile id))))

(defn handle-login [id pass]
  (let [user (db/get-user id)]
    (if (and user (crypt/compare pass (:pass user)) (not (:disabled user)))
      (do
        (db/update-login-timestamp id)
        (session/put! :superadmin (:superadmin user))
        (session/put! :user-id id)
        ;; Start the user out in one of their domains
        (session/put! :my-domain (:id (first (if (:superadmin user)
                                               (db/get-domains)
                                               (db/get-domains id)))))
        (when-not (:superadmin user)
          (let [domains (db/get-domains id)]
            (if (= 0 (count domains))
              (error "You are not a member of any domain. Ask the administrator to add you to one.")
              (session/put! :my-domain (:id (first domains)))))))
      (error "Invalid login"))
    (resp/redirect "/")))

(defn logout []
  (session/clear!)
  (resp/redirect "/"))

(defroutes auth-routes
  (GET "/users" [] (restricted (user-list)))
  (GET "/users/new" [id] (restricted (new-user id)))
  (GET "/users/edit" [id] (restricted (profile id)))
  (GET "/users/toggle-active" [id] (restricted (toggle-active id)))
  (POST "/users/update" {params :params} (restricted (update-profile params)))

  (GET "/profile" [] (restricted (profile (session/get :user-id))))
  (POST "/profile/update" {params :params} (restricted (update-profile params)))

  (POST "/login" [id pass] (handle-login id pass))
  (GET "/logout" [] (logout)))
