(ns aardvark.engine.templates.sql
  (:use [aardvark.engine.template])
  (:require [aardvark.engine.pipeline :as p]
            [clojure.string :as s]))

;;;; This template generates standard SQL that should be runnable on any compliant RDBMS.

;; TODO clean & temp are verbatim from Pig; refactor into a common util ns, after answering the ? on the next line
;; TODO is this the correct set of allowed characters for ANSI-compliant SQL?
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

;; TODO is this the correct set of ANSI SQL infix operators?
(def infix-operators ["+" "-" "*" "/" "AND" "IN" "IS" "IS_NOT" "IS_NULL" "OR" "XOR"])

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
                                   (cond ;; TODO is case an ANSI SQL thing?
                                        ; (.equalsIgnoreCase name "case") 
                                    #_ (let [test-expr (argval arguments 0)
                                             has-else (even? (count arguments)) ; test-expr + 2*when/then + else clause is an even count (odd without else)
                                             when-clauses (reduce str (map (fn [when-expr then-expr] 
                                                                             (str "WHEN " when-expr " THEN " then-expr " ")) 
                                                                           (partition 2 (rest arguments))))]
                                         (str prefix "(CASE " test-expr " " when-clauses (when has-else 
                                                                                           (str " ELSE " (last arguments))) " );"))
                                    
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

  (library [this]
           (-> []
               (lib + a b)
               (lib - a b)
               (lib * a b)
               (lib / a b)
               (lib ABS x)
               (lib ACOS x)
               ;; TODO verify part of ansi sql
               (lib CASE expr when then)
               (lib CASE expr when then else)
               (lib ADDDATE date days)
               (lib ADDTIME time1 time2)
               (lib AES_DECRYPT crypt_str key)
               (lib AND a b)
               (lib ASCII str)
               (lib ASIN x)
               (lib ATAN x)
               (lib ATAN2 x y)
               (lib BIN x)
               (lib BINARY str)
               (lib BIT_AND expr)
               (lib BIT_COUNT expr)
               (lib BIT_LENGTH expr)
               (lib BIT_OR expr)
               (lib BIT_XOR expr)
               (lib CHAR_LENGTH expr)
               (lib CHARSET str)
               (lib COALESCE a b)
               (lib COALESCE a b c) ; convenience
               (lib COALESCE a b c d) ; convenience
               (lib COERCIBILITY str)
               (lib COLLATION str)
               (lib COMPRESS str)
               (lib CONCAT str1 str2)
               (lib CONCAT_WS separator str1 str2)
               (lib CONCAT_WS separator str1 str2 str3) ; convenience
               (lib CONCAT_WS separator str1 str2 str3 str4) ; convenience
               (lib CONV n from_base to_base)
               (lib CONVERT_TZ dt from_tz to_tz)
               (lib COS x)
               (lib COT x)
               (lib CRC32 expr)
               (lib CURDATE)
               (lib CURTIME)
               (lib DATE_ADD date interval)
               (lib DATE_FORMAT date format)
               (lib DATE_SUB date interval)
               (lib DATE expr)
               (lib DATEDIFF expr1 expr2)
               (lib DAYNAME date)
               (lib DAYOFMONTH date)
               (lib DAYOFWEEK date)
               (lib DAYOFYEAR date)
               (lib DECODE crypt_str pass_str)
               (lib DEFAULT col_name)
               (lib DEGREES x)
               (lib DES_DECRYPT crypt_str key)
               (lib DES_ENCRYPT str key)
               (lib DIV x y)
               (lib ELT n str1 str2)
               (lib ELT n str1 str2 str3) ; convenience
               (lib ELT n str1 str2 str3 str4) ; convenience
               (lib ENCODE str pass_str)
               (lib ENCRYPT str)
               (lib ENCRYPT str salt)
               (lib EXP x)
               (lib EXPORT_SET bits on off separator number_of_bits)
               (lib EXTRACTVALUE xml_frag xpath_expr)
               (lib FIELD str str1 str2)
               (lib FIELD str str1 str2 str3) ; convenience
               (lib FIELD str str1 str2 str3 str4) ; convenience
               (lib FIND_IN_SET str strlist)
               (lib FLOOR x)
               (lib FORMAT x d)
               (lib FORMAT x d locale)
               (lib FROM_DAYS n)
               (lib FROM_UNIXTIME unix_timestamp)
               (lib GET_FORMAT type locale)
               (lib GREATEST a b)
               (lib GREATEST a b c) ; convenience
               (lib GREATEST a b c d)  ; convenience
               (lib HEX expr)
               (lib HOUR time)
               (lib IF epxr1 expr2 expr3)
               (lib IFNULL expr1 expr2)
               (lib IN expr in_expr)
               (lib INSTR str substr)
               (lib INTERVAL n n1 n2)
               (lib INTERVAL n n1 n2 n3) ; convenience
               (lib INTERVAL n n1 n2 n3 n4) ; convenience
               (lib IS expr bool)
               (lib IS_NOT expr bool)
               (lib IS_NULL expr)
               (lib LAST_DAY date)
               (lib LEAST value1 value2)
               (lib LEAST value1 value2 value3) ; convenience
               (lib LEAST value1 value2 value3 value4) ; convenience
               (lib LEFT str len)
               (lib LENGTH str)
               (lib LN x)
               (lib LOCATE substr str)
               (lib LOCATE substr str pos)
               (lib LOG x)
               (lib LOG2 x)
               (lib LOG10 x)
               (lib LOWER str)
               (lib LPAD str len padstr)
               (lib LTRIM str)
               (lib MAKEDATE year dayofyear)
               (lib MAKETIME hour minute second)
               (lib MD5 expr)
               (lib MICROSECOND expr)
               (lib MID str pos len)
               (lib MINUTE time)
               (lib MOD n m)
               (lib MONTH date)
               (lib MONTHNAME date)
               (lib NOT expr)
               (lib NOW)
               (lib NULLIF expr1 expr2)
               (lib OCT x)
               (lib OR a b)
               (lib ORD str)
               (lib PASSWORD str)
               (lib PERIOD_ADD p n)
               (lib PERIOD_DIFF p1 p2)
               (lib PI)
               (lib POW x y)
               (lib QUARTER date)
               (lib RADIANS expr)
               (lib RAND)
               (lib REPEAT str count)
               (lib REPLACE str from_str to_str)
               (lib REVERSE str)
               (lib RIGHT str len)
               (lib ROUND x)
               (lib RPAD str len padstr)
               (lib RTRIM str)
               (lib SEC_TO_TIME seconds)
               (lib SECOND time)
               (lib SHA1 str)
               (lib SHA2 str hash_length)
               (lib SIGN x)
               (lib SIN x)
               (lib SOUNDEX str)
               (lib SPACE n)
               (lib SQRT x)
               (lib STR_TO_DATE str format)
               (lib STRCMP expr1 expr2)
               (lib SUBDATE expr days)
               (lib SUBSTR str pos)
               (lib SUBSTR str pos len)
               (lib SUBSTRING_INDEX str delim count)
               (lib SUBTIME expr1 expr2)
               (lib SYSDATE)
               (lib TAN x)
               (lib TIME_FORMAT time format)
               (lib TIME_TO_SEC time)
               (lib TIME expr)
               (lib TIMEDIFF expr1 expr2)
               (lib TIMESTAMP expr)
               (lib TIMESTAMP expr1 expr2)
               (lib TIMESTAMPADD unit interval datetime_expr)
               (lib TIMESTAMPDIFF unit datetime_expr1 datetime_expr2)
               (lib TO_DAYS date)
               (lib TO_SECONDS expr)
               (lib TRIM str)
               (lib TRIM remstr str)
               (lib TRUNCATE x d)
               (lib UNCOMPRESS str)
               (lib UNCOMPRESSED_LENGTH compressed_str)
               (lib UNHEX str)
               (lib UNIX_TIMESTAMP)
               (lib UNIX_TIMESTAMP date)
               (lib UPDATEXML xml_target xpath_expr new_xml)
               (lib UPPER str)
               (lib UTC_DATE)
               (lib UTC_TIME)
               (lib UTC_TIMESTAMP)
               (lib UUID_SHORT)
               (lib UUID)
               (lib WEEK date)
               (lib WEEK date mode)
               (lib WEEKDAY date)
               (lib WEEKOFYEAR date)
               (lib XOR a b)
               (lib YEAR date)
               (lib YEARWEEK date)
               (lib YEARWEEK date mode))))
