(defproject git_to_rdf "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "TODO"
            :url "TODO"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.apache.jena/apache-jena-libs "4.5.0" :extension "pom"]
                 [org.clojure/data.csv "1.0.1"]
                 [org.clojure/data.json "2.4.0"]
                 [metosin/jsonista "0.3.5"]
                 ]
  :resource-paths ["sparql-anything-0.8.0-SNAPSHOT.jar" "."]
  :repl-options {:init-ns git-to-rdf.core})
