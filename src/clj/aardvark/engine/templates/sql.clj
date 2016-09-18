(ns aardvark.engine.templates.sql
  (:use [aardvark.engine.template])
  (:require [aardvark.engine.pipeline :as p]
            [clojure.string :as s]))

;;;; This template generates standard SQL that should be runnable on any compliant RDBMS.

(defn- clean [input]
  (-> input 
      (s/replace #" " "_")
      (s/replace #"[^A-Za-z0-9_]" "")))

(defn- temp [input]
  (clean (str "temp_" input)))

(defn- argval [args index]
  (let [thearg (nth args index)
        value (:value thearg)
        quoted (:quoted thearg)]
    (if quoted (str "'" value "'") value)))

(def infix-operators ["+" "-" "*" "/" "%" "AND" "IN" "IS" "IS_NOT" "IS_NULL" "OR" "XOR"])

(deftemplate sql
  ;; We get (template-name) for free with deftemplate

  (head [this pipeline] pipeline) ;; NOP

  (load-table [this pipeline table]
              (p-load pipeline table
                      (-> (p/append pipeline 
                                    (str "CREATE TEMPORARY TABLE "
                                         (temp table)
                                         " AS (SELECT "
                                         (->> columns
                                              (map #(str (clean (second %)) " " (clean (first %))))
                                              (interpose ", ")
                                              (reduce str))
                                         " FROM " (clean table) ");"))
                          (update-in [:join-temp-tables] #(assoc % (temp table) (temp table))) ;; We will track table lineage through joins later
                          (assoc :register (temp table))))) ; The register will just hold the current working temp table

  (join-tables [this pipeline primary secondary join-type]`x
               (p-join-expressions pipeline primary secondary
                                   (let [output-var (clean (gensym "J"))]
                                     (-> (p/append pipeline
                                                   (cond (= join-type :inner)
                                                         (str "CREATE TEMPORARY TABLE " output-var 
                                                              " AS (SELECT * FROM " (temp primary) " p, "
                                                              (temp secondary) " s WHERE "
                                                              (->> primary-join-cols
                                                                   (map #(str "p." (clean (:name %))
                                                                              " = s." (clean (:fk-name %))))
                                                                   (interpose " AND ")
                                                                   (reduce str)) 
                                                              ");")
                                                         :else nil))
                                         (update-in [:joint-temp-tables] #(assoc % primary output-var secondary output-var))
                                         (assoc :register output-var)))))
  
    (formula [this pipeline name arguments target]
           (p-formula pipeline name arguments target
                      (let [output-var (clean (gensym "F"))
                            ;; Process the args for Pig-friendliness, substitute constants, etc.
                            arguments (->> arguments
                                           (map (fn [arg] (if (or (.contains (str (:value arg)) " ")) ; Quote anything with a space
                                                            (assoc arg :quoted true) 
                                                            arg))))
                            prefix (str "CREATE TEMPORARY TABLE " output-var " AS (SELECT *, ")]
                        (->
                         (p/append pipeline 
                                   ;; Handle special cases, then the general case as default
                                   (cond
                                    (.equalsIgnoreCase name "case") 
                                    (let [test-expr (argval arguments 0)
                                             has-else (even? (count arguments)) ; test-expr + 2*when/then + else clause is an even count (odd without else)
                                          when-clauses (reduce str (map (fn [when-expr then-expr] 
                                                                             (str "WHEN " when-expr " THEN " then-expr " ")) 
                                                                        (partition 2 (rest arguments))))]
                                      (str prefix "CASE " test-expr " " when-clauses (when has-else 
                                                                                       (str " ELSE " (last arguments))) " END );"))
                                    :else
                                    (str
                                     prefix
                                     (if (some #{name} infix-operators) 
                                       (str (argval arguments 0) " " name " " (argval arguments 1)) ;; Infix
                                       (str name "(" (csv (for [i (range (count arguments))] (argval arguments i))) ")"))
                                     " AS " (clean (:value target)) " FROM " (:register pipeline)  ");")))
                         (assoc :register output-var)))))

  (constant [this pipeline field value]
            (p-constant pipeline field))  
   
  (tail [this pipeline target-roles]
        (p-tail pipeline target-roles
                (->
                 (p/append pipeline
                           (str "CREATE TEMPORARY TABLE output AS (SELECT "
                                (reduce str (interpose ", " 
                                                       (map #(str (first %) " AS " (second %)) target-roles)))
                                " FROM " (:register pipeline) ");"))
                 (assoc :register "output")))) ; Ok, this last register update is unnecessary, but consistent

  ;; Many more are implemented by every actual DB, but this is not an exhaustive list; 
  ;; here we are just capturing those that are "guaranteed" to be common by virtue of 
  ;; being reserved keywords in the SQL-92 standard
  (library [this]
           (-> []
               (lib + a b)
               (lib - a b)
               (lib * a b)
               (lib / a b)
               (lib % a b)
               (lib CASE expr when then)
               (lib CASE expr when then else)
               (lib AND a b)
               (lib BIN x)
               (lib BIT_LENGTH expr)
               (lib CHAR_LENGTH expr)
               (lib COALESCE a b)
               (lib COALESCE a b c) ; convenience
               (lib COALESCE a b c d) ; convenience
               (lib COLLATION str)
               (lib CURRENT_DATE)
               (lib CURRENT_TIME)
               (lib CURRENT_TIMESTAMP)
               (lib DATE expr)
               (lib DAY expr)
               (lib DECIMAL a)
               (lib DEFAULT col_name)
               (lib DOUBLE a)
               (lib FLOAT a)
               (lib HOUR time)
               (lib IN expr in_expr)
               (lib INTERVAL n n1 n2)
               (lib INTERVAL n n1 n2 n3) ; convenience
               (lib INTERVAL n n1 n2 n3 n4) ; convenience
               (lib IS expr bool)
               (lib LEFT str len)
               (lib MINUTE time)
               (lib MONTH date)
               (lib NOT expr)
               (lib NULLIF expr1 expr2)
               (lib OR a b)
               (lib RIGHT str len)
               (lib SECOND time)
               (lib TIME expr)
               (lib TIMESTAMP expr)
               (lib TRIM str)
               (lib TRIM remstr str)
               (lib YEAR date))))
