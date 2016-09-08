(ns aardvark.views.layout
  (:require [selmer.parser :as parser]
            [selmer.filters :as filters]
            [clojure.string :as s]
            [aardvark.engine.mediator :as m]
            [aardvark.util :refer [interpose-splice]]
            [ring.util.response :refer [content-type response]]
            [compojure.response :refer [Renderable]]
            [aardvark.models.db :as db]
            [taoensso.timbre :as log]
            [noir.session :as session]))

(def template-path "aardvark/views/templates/")

(deftype
  RenderableTemplate
  [template params]
  Renderable
  (render
    [this request]
    (content-type
      (->>
        (assoc
          params
          (keyword (s/replace template #".html" "-selected"))
          "active"
          :servlet-context
          (:context request)
          :user-id
          (session/get :user-id)
          :superadmin
          (session/get :superadmin))
        (parser/render-file (str template-path template))
        response)
      "text/html; charset=utf-8")))

(defn render [template & [params]]
  (let [global (merge (if (session/get :superadmin) ;; Global variables e.g. to go in the menu
                        {:domains (db/get-domains)}
                        {:domains (db/get-domains (session/get :user-id))})
                      {:my-domain (session/get :my-domain)
                       :message (session/flash-get :message)
                       :error (session/flash-get :error)})]
    ;; Clear out the messages - session/flash doesn't seem to be reliably removing after one request
    (doseq [var [:message :error]] (session/flash-put! var nil))
    (RenderableTemplate. template (merge global params))))

;; True if the user's role is "All" or the provided alternate. {% if role|role?:SME %}
(filters/add-filter! :role? #(or (= %1 "All") (= %1 %2) (session/get :superadmin)))

(defn- resolve-dirty [type prefix dirty-id]
    (if (and (< 1 (count dirty-id)) (= (str prefix ":") (.substring dirty-id 0 (+ 1 (count prefix)))))
      (let [id (.replace (str dirty-id) (str prefix ":") "")
            name (:name (db/declaration-get type id))]
        (if (empty? name) (if (empty? id) "Undefined" (.substring id 0 8))
          name))
      dirty-id))

(def dirty-sub (fn [type prefix dirty-id]
                 (if (and (< 1 (count dirty-id)) (.equals (str prefix ":")
                                                          (.substring dirty-id 0 (+ 1 (count prefix)))))
                   (resolve-dirty type prefix dirty-id) dirty-id)))

;; Format the argstring for display
;; Strip flags, replace linebreaks with ,
(filters/add-filter! :arguments (fn [argstring]
                                  (when argstring
                                    (->> (s/split-lines argstring)
                                         (map s/trim)
                                         (map (partial dirty-sub db/roles "r"))
                                         (map (partial dirty-sub db/modifiers "m"))
                                         (map #(let [flags (-> %
                                                               (s/split #":")
                                                               first)
                                                     arg (s/trim (s/replace % #".+:" ""))]
                                                 (s/trim (if (.contains flags "q")
                                                           (str "\"" arg "\"") arg))))
                                         (interpose-splice ", ")))))

(filters/add-filter! :splitc #(when % (s/split % #",")))

(filters/add-filter! :conflict-source-value #(or (:source-mv %) ""))

(filters/add-filter! :conflict-target-value #(or (:target-mv %) ""))

(filters/add-filter! :modifier #(let [modifier (db/declaration-get db/modifiers (:modifier %))
                                      st (db/declaration-get db/semantic_types (:parent_id modifier))]
                                  (str (:name st) "." (:name modifier))))

(filters/add-filter! :resolution (fn [conflict]
                                   (let [resolution (->> conflict m/choose-solution
                                                         (map #(if (= :relational (:conflict-type conflict))
                                                                 (:name (db/declaration-get db/relational_functions %))
                                                                 (let [sf (db/declaration-get db/semantic_functions %)]
                                                                   (str "[" (:source_mv sf) "->" (:target_mv sf) "]")))))]
                                     (if (empty? resolution)
                                       "<span style='color:red;'><b>**UNRESOLVED**</b></span>"
                                      (interpose-splice "<br/>" resolution)))))

(filters/add-filter! :target-role #(:name (db/declaration-get db/roles (:target-role %))))

(filters/add-filter! :resolve-role #(resolve-dirty db/roles "r" %))
