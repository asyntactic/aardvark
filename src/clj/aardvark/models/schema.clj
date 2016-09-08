(ns aardvark.models.schema)

(def db-spec
  (let [dev? (nil? (System/getProperty "RDS_HOSTNAME"))]
    {:subprotocol "mysql"
     :subname (if dev?
                "//localhost:3306/aardvark"
                (str "//" (System/getProperty "RDS_HOSTNAME") ":"
                     (System/getProperty "RDS_PORT") "/"
                     (System/getProperty "RDS_DB_NAME")))
     :user (if dev? "root" (System/getProperty "RDS_USERNAME"))
     :password (if dev? "root" (System/getProperty "RDS_PASSWORD"))
     :delimiters ""}))


