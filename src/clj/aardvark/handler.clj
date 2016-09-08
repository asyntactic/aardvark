(ns aardvark.handler
  (:require [compojure.core :refer [defroutes]]
            [aardvark.routes.home :refer [home-routes]]
            [aardvark.middleware :as middleware]
            [ring.middleware.defaults :refer :all]
            [noir.util.middleware :refer [app-handler]]
            [compojure.route :as route]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.rotor :as rotor]
            [selmer.parser :as parser]
            [environ.core :refer [env]]
            [aardvark.routes.auth :as auth]
            [aardvark.routes.domain :refer [domain-routes]]
            [aardvark.routes.declaration :refer [declaration-routes]]
            [aardvark.routes.connector :refer [connector-routes]]
            [aardvark.routes.reports :refer [report-routes]]
            [aardvark.models.db :as db]))

(defroutes
  app-routes
  (route/resources "/")
  (route/not-found "Not Found"))

(defn init
  "init will be called once when
   app is deployed as a servlet on
   an app server such as Tomcat
   put any initialization code here"
  []
  ;; Note that Java classes use Log4J, to the same file.
  ;; See src/resources/log4j.xml
  (timbre/set-config!
    [:appenders :rotor]
    {:min-level :info,
     :enabled? true,
     :async? false,
     :max-message-per-msecs nil,
     :fn rotor/appender-fn})
  (timbre/set-config!
    [:shared-appender-config :rotor]
    {:path "aardvark.log", :max-size (* 512 1024), :backlog 10})
  (if (env :dev) (parser/cache-off!))
  (when-not (db/get-user "admin") (auth/create-superadmin))
  (timbre/info "aardvark started successfully"))

(defn destroy
  "destroy will be called when your application
   shuts down, put any clean up code here"
  []
  (timbre/info "aardvark is shutting down..."))

(def app
 (app-handler
   [auth/auth-routes domain-routes declaration-routes connector-routes home-routes
    report-routes app-routes]
   :middleware
   [middleware/template-error-page middleware/log-request]
   :access-rules
   [{:uris ["/domain/update" "/domain/delete" "/domain/add"
            "/users/*"]
     :rule auth/superadmin-access}
    auth/user-access]
   :ring-defaults api-defaults
   :formats
   [:json-kw :edn]))

