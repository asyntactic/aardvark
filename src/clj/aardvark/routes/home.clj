(ns aardvark.routes.home
  (:use compojure.core)
  (:require [noir.session :as session]
            [aardvark.views.layout :as layout]))

(defn home-page []
  (layout/render
    "home.html" {:content ""}))

(defroutes home-routes
  (GET "/" [] (home-page)))

