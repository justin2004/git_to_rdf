(defproject git_to_rdf "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "TODO"
            :url "TODO"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.apache.jena/apache-jena-libs "4.5.0" :extension "pom"]
                 [org.clojure/data.csv "1.0.1"]
                 [org.clojure/data.json "2.4.0"]
                 [progrock "0.1.2"]
                 [metosin/jsonista "0.3.5"]
                 [org.clojure/tools.cli "1.0.206"]
                 ]
  :main git-to-rdf.core
  :uberjar {:aot [git-to-rdf.core]}
  :clean-targets ^{:protect false} ["/app/target"]
  :jvm-opts ["-Dorg.slf4j.simpleLogger.logFile=git_to_rdf.log"]
  :resource-paths ["sparql-anything-0.8.0.jar"]
  :repl-options {:init-ns git-to-rdf.core})
