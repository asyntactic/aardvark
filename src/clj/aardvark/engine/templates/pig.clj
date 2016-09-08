(ns aardvark.engine.templates.pig
  (:use [aardvark.engine.template])
  (:require [aardvark.engine.pipeline :as p]
            [clojure.string :as s]))

;;;; This template generates Pig Latin compatible with Pig v0.12-0.15. Later versions may also be 
;;;; backwards compatible, but have not beeen certified.

(defn- clean [input]
  (-> input 
      (s/replace #" " "_")
      (s/replace #"[^A-Za-z0-9_]" "")))

(defn- temp [input]
  (clean (str "temp_" input)))

(def infix-operators ["+" "-" "*" "/" "%" "?" ":"])

(defn- argval [args index]
  (let [thearg (nth args index)
        value (:value thearg)
        quoted (:quoted thearg)]
    (if quoted (str "'" value "'") value)))

(deftemplate pig
  ;; We get (template-name) for free with deftemplate

  (head [this pipeline] pipeline) ;; NOP

  (load-table [this pipeline table]
              (p-load pipeline table
                      (-> (p/append pipeline 
                                    (str (temp table)
                                         " = FOREACH " (clean table) " GENERATE " 
                                         (->> columns
                                              (map #(str (clean (second %)) " AS " (clean (first %))))
                                              (interpose ", ")
                                              (reduce str))
                                         ";"))
                          (update-in [:join-temp-tables] #(assoc % (temp table) (temp table)))))) ;; We will track table lineage through joins later

  (join-tables [this pipeline primary secondary join-type]
               (p-join-expressions pipeline primary secondary
                                   (let [output-var (clean (gensym "J"))]
                                     (-> (p/append pipeline
                                                   (cond (= join-type :inner)
                                                         (str output-var " = JOIN " (temp primary) " BY ("
                                                              (csv (map #(clean (:name %)) primary-join-cols))
                                                              "), " (temp secondary) " BY ("
                                                              (csv (map #(clean (:fk-name %)) primary-join-cols))
                                                              ");")
                                                         :else nil))
                                         (update-in [:joint-temp-tables] #(assoc % primary output-var secondary output-var))))))
  
    (formula [this pipeline name arguments target]
           (p-formula pipeline name arguments target
                      (let [output-var (clean (gensym "F"))
                            ;; Process the args for Pig-friendliness, substitute constants, etc.
                            arguments (->> arguments
                                           (map (fn [arg] (if (or (.contains (str (:value arg)) " ")) ; Quote anything with a space
                                                            (assoc arg :quoted true) 
                                                            arg))))
                            prefix (str output-var " = FOREACH @ GENERATE *, ")]
                        (p/append pipeline 
                                  ;; Handle special cases, then the general case as default
                                  (cond (= name "?:") ;; bincond
                                        (str prefix "(" (argval arguments 0) " ? " (argval arguments 1) " : " (argval arguments 2) ");")
                                        (.equalsIgnoreCase name "case") 
                                        (let [test-expr (argval arguments 0)
                                              has-else (even? (count arguments)) ; test-expr + 2*when/then + else clause is an even count (odd without else)
                                              when-clauses (reduce str (map (fn [when-expr then-expr] 
                                                                             (str "WHEN " when-expr " THEN " then-expr " ")) 
                                                                           (partition 2 (rest arguments))))]
                                          (str prefix "(CASE " test-expr " " when-clauses (when has-else 
                                                                                            (str " ELSE " (last arguments))) " );"))
                                        (.equalsIgnoreCase name "stream")
                                        (str prefix "STREAM " (argval arguments 0) " THROUGH " (argval arguments 1) ";")
                                        :else
                                        (str
                                         prefix
                                         (if (some #{name} infix-operators) 
                                           (str (argval arguments 0) " " name " " (argval arguments 1)) ;; Infix
                                           (str name "(" (csv (for [i (range (count arguments))] (argval arguments i))) ")") ;; Prefix 
                                           )
                                         " AS " (clean (:value target)) ";"))))))
  
  (constant [this pipeline field value]
            (p-constant pipeline field))  
   
  (tail [this pipeline target-roles]
        (p-tail pipeline target-roles
                (p/append pipeline
                          (str "output = FOREACH @ GENERATE "
                               (reduce str (interpose ", " 
                                                      (map #(str (first %) " AS _" (second %)) target-roles))) ";"))))

  (library [this]
           (-> []
               ;; Arithmetic
               (lib + a b)
               (lib - a b)
               (lib * a b)
               (lib / a b)
               (lib % a b)
               (lib "?:" condition value_if_true value_if_false)
               (lib CASE expr when then)
               (lib CASE expr when then else)

               ;; Comparisons
               (lib == a b)
               (lib != a b)
               (lib < a b)
               (lib > a b)
               (lib <= a b)
               (lib >= a b)
               (lib matches expr string_constant)
               (lib AND a b)
               (lib OR a b)
               (lib IN a b)
               (lib NOT a)

               ;; External
               (lib STREAM alias command)

               ;; Eval functions
               (lib AVG bag)
               (lib CONCAT expr1 expr2)
               (lib COUNT bag)
               (lib COUNT_STAR bag)
               (lib DIFF bag1 bag2)
               (lib ISEMPTY expr)
               (lib MAX bag)
               (lib MIN bag)
               (lib SIZE expr)
               (lib SUBTRACT bag1 bag2)
               (lib SUM expr)
               (lib TOKENIZE expr)
               (lib TOKENIZE expr delimiter)

               ;; Math
               (lib ABS expr)
               (lib ACOS expr)
               (lib ASIN expr)
               (lib ATAN expr)
               (lib CBRT expr)
               (lib CEIL expr)
               (lib COS expr)
               (lib EXP expr)
               (lib FLOOR expr)
               (lib LOG expr)
               (lib LOG10 expr)
               (lib RANDOM)
               (lib ROUND expr)
               (lib SIN expr)
               (lib SINH expr)
               (lib SQRT expr)
               (lib TAN expr)
               (lib TANH expr)

               ;; String
               (lib ENDSWITH string test_against)
               (lib EQUALSIGNORECASE string1 string2)
               (lib INDEXOF string character start_index)
               (lib LAST_INDEX_OF string character)
               (lib LCFIRST expr)
               (lib LOWER expr)
               (lib LTRIM expr)
               (lib REGEX_EXTRACT string regex index)
               (lib REGEX_EXTRACT_ALL string regex)
               (lib REPLACE string regex newchar)
               (lib RTRIM expr)
               (lib STARTSWITH string test_against)
               (lib STRSPLIT string regex limit)
               (lib SUBSTRING string start_index stop_index)
               (lib UCFIRST expr)
               (lib UPPER expr)

               ;; DateTime
               (lib ADDDURATION datetime duration)
               (lib CURRENTTIME)
               (lib DAYSBETWEEN datetime1 datetime2)
               (lib GETDAY datetime)
               (lib GETHOUR datetime)
               (lib GETMILLISECOND datetime)
               (lib GETMINUTE datetime)
               (lib GETMONTH datetime)
               (lib GETSECOND datetime)
               (lib GETWEEK datetime)
               (lib GETWEEKYEAR datetime)
               (lib GETYEAR datetime)
               (lib HOURSBETWEEN datetime1 datetime2)
               (lib MILLISECONDSBETWEEN datetime1 datetime2)
               (lib MINUTESBETWEEN datetime1 datetime2)
               (lib MONTHSBETWEEN datetime1 datetime2)
               (lib SECONDSBETWEEN datetime1 datetime2)
               (lib SUBTRACTDURATION datetime duration)
               (lib TODATE milliseconds)
               (lib TODATE string format)
               (lib TODATE string format timezone)
               (lib TOMILLISECONDS datetime)
               (lib TOSTRING datetime)
               (lib TOSTRING datetime format)
               (lib TOUNIXTIME datetime)
               (lib WEEKSBETWEEN datetime1 datetime2)
               (lib YEARSBETWEEN datetime1 datetime2)

               ;; Tuple, Bag, Map
               (lib TOTUPLE expr)
               (lib TOBAG expr)
               (lib TOMAP expr)
               (lib TOP top_n column relation))))
