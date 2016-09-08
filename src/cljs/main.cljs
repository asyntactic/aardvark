(ns aardvark.main
  (:require [ajax.core :refer [GET POST]]
            [goog.string :as string]
            [goog.dom :as dom]
            [domina :refer [set-text! single-node]]
            [domina.xpath :refer [xpath]]))

(defn raw-text
  "A version of Domina's (text) function that does not strip linebreaks"
  [content]
  (string/trim (dom/getRawTextContent (single-node content))))

;; All of placearg needs to happen via JS instead of Domina b/c
;; textarea content was proving troublesome once it was manually edited.
(defn ^:export placearg [arg]
  ;; Place the argument in the textarea
  (let [node (.getElementById js/document "arguments-ta")
        old-val (.-value node)]
    (set! (.-value node)
               (if (empty? old-val)
                      arg
                      (str old-val "\n" arg))))
  ;; Scroll the textarea to the end
  (let [textarea (.getElementById js/document "arguments-ta")
        scrollheight (.-scrollHeight textarea)]
    (set! (.-scrollTop textarea) scrollheight)))

(defn ^:export setoutput [value]
  (.log js/console value)
  (let [node (.getElementById js/document "output")]
    (set! (.-value node) value)))

(defn ^:export getSelectValue [id]
  "Get the current (selected) value of a select element by id"
  (let [select (.getElementById js/document id)
        options (.-options select)
        selected-index (.-selectedIndex select)]
    (.-value (nth options selected-index))))

(defn ^:export getInputValue [id]
  "Get the current value of an input (possibly with a datalist)"
  (let [input (.getElementById js/document id)]
    (.-value input)))
