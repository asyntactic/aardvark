(defproject
  aardvark
  "0.0.0"
  :repl-options
  {:init-ns aardvark.repl
   :timeout 120000 ;; Default is 30000 (30 sec)
   }
  :dependencies
  [[ring-server "0.4.0"]
   [ring-webjars "0.1.1"]
   [ring/ring-defaults "0.2.1"]
   [ring-middleware-format "0.7.0"]
   [metosin/ring-http-response "0.8.0"]
   [bouncer "1.0.0"]
   [mount "0.1.10"]
   [cprop "0.1.8"]
   [mysql/mysql-connector-java "5.1.29"]
   [ragtime "0.3.4"]
   [domina "1.0.3"]
   [com.taoensso/timbre "3.1.1"]
   [environ "1.1.0"]
   ;[prismatic/dommy "0.1.2"]
   [korma "0.3.0-RC6"]
   [org.clojure/clojurescript "0.0-2173"]
   [selmer "1.0.7"]
   [com.taoensso/tower "2.0.2"]
   [org.clojure/clojure "1.7.0"]
   [cljs-ajax "0.2.3"]
   [lib-noir "0.9.9"]
   [compojure "1.5.1"]
   [lacij "0.10.0"] ; Graph visualization library for reports
   [com.google.code.gson/gson "2.7"]
   [medley "0.8.3"]
   ;; Connector-specific support libraries
   [org.apache.pig/pig "0.15.0"]
   [org.apache.hadoop/hadoop-core "1.2.1"]
   [org.apache.hadoop/hadoop-common "2.7.3"
    ;; hadoop-common uses an old version of guava that was conflicting with the cljs version
    :exclusions [com.google.guava/guava]]]
  :main aardvark.repl
  :source-paths ["src/clj"]
  :resource-paths ["resources" "src/resources"]
  :javac-options ["-target" "1.7" "-source" "1.7"]
  :jvm-opts ^:replace ["-XX:-OmitStackTraceInFastThrow"] ; prevent [trace missing] exceptions caused by JVM over-optimization 
  :cljsbuild
  {:builds
   [{:source-paths ["src/cljs"],
     :compiler
     {;:pretty-print false,
      :output-to "resources/public/js/site.js",
      :optimizations :advanced}}]}
  :ring
  {:handler aardvark.handler/app,
   :init aardvark.handler/init,
   :destroy aardvark.handler/destroy}
  :ragtime
  {:migrations ragtime.sql.files/migrations,
   :database
   "jdbc:mysql://localhost:3306/aardvark?user=root"}
  :profiles
  {:uberjar {:aot :all
             :resource-paths ["resources" "src/resources"]},
   :production
   {:ring
    {:open-browser? false, :stacktraces? false, :auto-reload? false}},
   :dev
   {:dependencies [[ring-mock "0.1.5"] [ring/ring-devel "1.5.0"]],
    :env {:dev true}}}
  :test-paths ["test/clj"]
  :plugins
  [[lein-ring "0.9.7"]
   [lein-environ "1.1.0"]
   [lein-cljsbuild "0.3.3"]
   [ragtime/ragtime.lein "0.3.4"]]
  :min-lein-version "2.0.0")
