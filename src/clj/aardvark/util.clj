(ns aardvark.util
  (:require [noir.session :as session]))

;;;; General utilities used across classes

(defn message [msg]
  "Add a message to the user-displayed list (next request only)"
  (let [old (session/flash-get :message)]
    (session/flash-put! :message (conj old msg))))

(defn error [msg]
  "Add an error message to the user-displayed list (next request only)"
  (let [old (session/flash-get :error)]
    (session/flash-put! :error (conj old msg))))

(defn interpose-splice
  "Utility to interpose a separator into a collection of strings and then to
   splice that collection together into a single string."
  [sep col]
  (if (empty? col)
    ""
    (reduce (fn [a b] (str a b)) (interpose sep col))))

(defn domain-id []
  (or (session/get :my-domain) 0))

(defn contains? [item collection]
  (some #{item} collection))
