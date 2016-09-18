(ns aardvark.interop
  (:require [aardvark.models.db :as db]
            [aardvark.engine.templates.logging]
            [aardvark.engine.templates.pig]
            [aardvark.engine.templates.sql]
            [taoensso.timbre :as log]))

;;;; Gate any "interop" between the front end, the engine, and any direct interactions with data systems

(def templates 
  [(aardvark.engine.templates.pig/template-instance) 
   (aardvark.engine.templates.logging/template-instance)
   (aardvark.engine.templates.sql/template-instance)])

(def default-template (first templates))

