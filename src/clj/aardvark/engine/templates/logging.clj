(ns aardvark.engine.templates.logging
  (:use [aardvark.engine.template])
  (:require [aardvark.engine.pipeline :as p]))

;;;; Simple reference template to simply log template events (by storing them in the pipeline output)

(deftemplate logging 
  ;; We get (template-name) for free with deftemplate

  (head [this pipeline] (p/append pipeline "head"))

  (load-table [this pipeline table]
              (p-load pipeline table
                      (p/append pipeline 
                              (str "load " table " with fields/columns: " 
                                   (reduce str (interpose ", " columns))))))

  (join-tables [this pipeline primary secondary join-type]
               (p-join-expressions pipeline primary secondary
                                   (println primary-join-cols)
                                   (p/append pipeline (str "Joining " primary " and " secondary " on "
                                                           (reduce str (interpose ", " 
                                                                                  (map #(str (:name %) "->" (:fk-name %)) primary-join-cols)))))))

  (formula [this pipeline name arguments target]
           (p-formula pipeline name arguments target
                    ;  (println "ARGS: " arguments) ;; TODO remove
                      (p/append pipeline (str "Formula: " (:value target) "=" name "(" 
                                              (reduce str (interpose ", " 
                                                                     (map #(str (when (:quoted %) "'") (:value %) (when (:quoted %) "'")) arguments))) ")"))))
  
  (constant [this pipeline field value]
            (p-constant pipeline field)
            (p/append pipeline (str "Added constant: " field " = " value)))

  (tail [this pipeline target-roles]
        (p-tail pipeline target-roles
                (p/append pipeline (str "Tail roles: " 
                                        (reduce str (interpose ", " 
                                                               (map #(str (first %) " as " (second %)) target-roles)))))))

  (library [this] (-> []
                       (lib log message)))) ;; The logging connector doesn't actually do anything! This is just a meaningless example

