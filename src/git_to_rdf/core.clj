(ns git-to-rdf.core)
(require '[clojure.data.json :as json])
(require '[clojure.data.csv :as csv])
(require '[jsonista.core :as j])

(defmacro with-txn [dataset & body] 
  `(try
    (.begin ~dataset)
    ~@body
    (finally (.commit ~dataset))))

(defn csv-data->maps [csv-data]
  (map zipmap
       (->> (first csv-data) ;; First row is the header
            (map keyword) ;; Drop if you want string keys instead
            repeat)
       (rest csv-data)))


(defn get-commit-summary-maps [repo-path]
  (csv-data->maps
  (csv/read-csv
    (:out
      (clojure.java.shell/sh "bash" "-c" (str "echo 'abbreviated_commit_hash,author_name,author_email,author_date,subject,body' ; "
                                              "git -C " 
                                              repo-path
                                              " --no-pager log --date=local --pretty=%h,%an,%ae,%aI,'%s' "))))))

(defn get-hash-pairs [path]
  (let [m (get-commit-summary-maps path)]
    (partition 2 1
               (conj (reverse (map :abbreviated_commit_hash m))
          "4b825dc642cb6eb9a060e54bf8d69288fbee4904"))))

(defn get-diff-content [repo-path pair]
  "pair -- pair of commit ids"
  (clojure.java.shell/sh "bash" "-c" (str "git -C "
                                          repo-path
                                          " --no-pager diff "
                                          (first pair)
                                          " "
                                          (second pair))))



(defn get-commit-summary [repo-path commit-id]
  (json/write-str
   (first
     (get-commit-summary-maps repo-path))))



(defn make-query-for-commit-summary [inputfile]
  (with-out-str (printf "
						PREFIX  sd:   <https://w3id.org/okn/o/sd#>
						PREFIX  fx:   <http://sparql.xyz/facade-x/ns/>
						PREFIX  rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
						PREFIX  xyz:  <http://sparql.xyz/facade-x/data/>
						PREFIX  i:    <https://www.w3id.org/okn/i/>
						PREFIX  rdfs: <http://www.w3.org/2000/01/rdf-schema#>
						PREFIX  :     <http://example.com/>
						PREFIX wd:       <http://www.wikidata.org/entity/>
						prefix dcterms: <http://purl.org/dc/terms/> 
						prefix gist: <https://ontologies.semanticarts.com/gist/>
						prefix schema: <https://schema.org/>
						PREFIX  xsd:  <http://www.w3.org/2001/XMLSchema#>
						CONSTRUCT 
						{ 
						?commit a wd:Q20058545 .
						?commit dcterms:creator ?author .
						?commit gist:atDateTime ?date .
						?author gist:name ?name .
						?author schema:email ?email .
						?commit dcterms:subject ?subject .
						?subject dcterms:title ?subject_text .
						?subject dcterms:description \"TODO body goes here\" .
						?commit gist:contains ?content . # TODO gist needs this
						}
						WHERE
						{ SERVICE <x-sparql-anything:>
						{ fx:properties fx:location  \"%s\" .
						# TODO should be optionals?
						[ xyz:abbreviated_commit_hash ?hash ;
						xyz:author_name ?name ;
						xyz:author_name ?name ;
						xyz:author_email ?email ;
						xyz:author_date ?date_string ;
						xyz:subject ?subject_text ]
						bind(strdt(?date_string,xsd:dateTime) as ?date)
						bind(iri(concat(str(:),\"commit/\",?hash)) as ?commit)
						bind(iri(concat(str(:),\"commit_content/\",?hash)) as ?content)
						bind(bnode() as ?author)
						bind(bnode() as ?subject)
						}
						}
						"
						inputfile)))


(defn make-query-for-commit-content [inputfile]
  (with-out-str (printf "
                        PREFIX  :     <http://example.com/>
                        PREFIX  owl:  <http://www.w3.org/2002/07/owl#>
                        PREFIX  xyz:  <http://sparql.xyz/facade-x/data/>
                        PREFIX  rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
						prefix gist: <https://ontologies.semanticarts.com/gist/>
                        PREFIX  fx:   <http://sparql.xyz/facade-x/ns/>
                        prefix wd: <http://www.wikidata.org/entity/>
                        construct {
                        ?content a :ContentExpression ;
                                 gist:connectedTo wd:Q29465391 ;
                                 ?patch_line_pred ?line .
                        }
                        where 
                        {
                        service <x-sparql-anything:> {
                        fx:properties fx:location \"%s\" .
                        ?s xyz:abbreviated_commit_hash ?hash .
                        ?s xyz:out_split/(!<>) ?o .
                        ?o ?pred ?line .
                        # split pred line  ----
                        bind(iri(concat(str(:),\"commit_content/\",?hash)) as ?content)
                        bind(replace(str(?pred),str(xyz:),\"\") as ?pln) .
                        bind(iri(concat(str(rdf:),\"_\",?pln)) as ?patch_line_pred) }}"
                        inputfile))
  )


(defn run-sa-construct [query outputfile]
  (com.github.sparqlanything.cli.SPARQLAnything/main 
   (into-array ["-q" query 
				"-o" outputfile
				"--append"
				"-f" "NQ"])))


;;;;;
; rm a.patch
; rm hunks/*
; for each hash-pair
;    save patch in file   a.patch
;         git diff -U0  > a.patch
;    cd hunks
;    put each hunk in its own file
;        /path/to/splitpatch/splitpatch.rb -H ../a.patch
;    for each file in hunks... 
;        make clj map and convert to json obj

(do-hunks "fred")

(defn do-hunks [repopath]
  "TODO probably doesn't handle space in path"
  (do
    (clojure.java.io/delete-file "a.patch" true)
    (clojure.java.shell/sh "bash" "-c" "rm -rf hunks")
    (.mkdir (clojure.java.io/file "hunks"))
    (doseq [pair (get-hash-pairs repopath)]
      (clojure.java.shell/sh "bash" "-c" (str "git -C "
                                              repopath
                                              " diff -U0 "
                                              (first pair)
                                              " "
                                              (second pair)
                                              " > a.patch"))
      (clojure.java.shell/sh "bash" "-c" "cd /mnt/hunks ; splitpatch -H ../a.patch")
      (doseq [hunk (filter #(.isFile %) (file-seq (clojure.java.io/file "hunks")))]
        (clojure.pprint/pprint (assoc (raw-hunk->map (.getPath hunk))
                                      :commit-id (last pair))))
      (clojure.java.shell/sh "bash" "-c" "rm -rf hunks/*"))))



; (print (slurp (nth (file-seq (clojure.java.io/file ".")) 
;      6)))

; (clojure.java.shell/sh "pwd")
; (clojure.java.shell/sh "bash" "-c" "cd /mnt/jj ; pwd")
; (file-seq (clojure.java.io/file "/mnt"))

(defn handle-filename [s]
  (clojure.string/replace (subs s
                                4)
                          #"^[ab]/" ""))

; TODO test
; (handle-filename  "--- a/sparql.anything.xml/pom.xml")
; (handle-filename  "--- /dev/null")


(defn raw-hunk->map [path]
  "convert a raw-hunk (unified diff) file into a map"
  (let [lines (clojure.string/split-lines (slurp path))
        old-filename (handle-filename (nth lines 0))
        new-filename (handle-filename (nth lines 1))
        deets-raw (nth lines 2)
        deets-clean (clojure.string/replace deets-raw #".*@@ (.*) @@.*" "$1")
        deets-clean-no-signs (clojure.string/replace deets-clean
                                                     #"[+-]"
                                                     "") 
        deets (clojure.string/split deets-clean-no-signs #" ")
        deets1 (mapv #(clojure.string/split % #",") deets)
        ; content (drop-last 2 (drop 3 lines))
        content (drop 3 lines)
        deets-map {:old_filename old-filename
                   :new_filename new-filename
                   :old_source_start_line (first (first deets1))
                   :old_source_line_count (second (first deets1))
                   :new_source_start_line (first (second deets1))
                   :new_source_line_count (second (second deets1))
                   :old_content (extract-hunk-part \- content)
                   :new_content (extract-hunk-part \+ content) }
        ]
    deets-map))

(defn extract-hunk-part [leading-char content-seq]
  (mapv #(subs % 1)
        (filter #(= leading-char
                    (first (subs % 0 1)))
                content-seq)))


(print (slurp (nth (file-seq (clojure.java.io/file ".")) 
     6)))

(clojure.pprint/pprint (filter #(.isFile %) (file-seq (clojure.java.io/file "."))))

(clojure.pprint/pprint (raw-hunk->map "/mnt/la1"))
(print (j/write-value-as-string (raw-hunk->map "/mnt/la")))


; thanks to  https://www.clearchain.com/blog/posts/splitting-a-patch
;
; the first number is the starting line for this hunk in oldfile
; the second number is the number of original source lines in this hunk (this includes lines marked with “-“)
; the third number is the starting line for this hunk in newfile
; the last number is the number of lines after the hunk has been applied.
(print (raw-hunk->map "/mnt/la"))

;;;;;;;;;;;;;;;;;;;;;
(def path "jj")
(clojure.java.io/delete-file "finalout.nq")
(clojure.java.io/delete-file "finalout_summary.nq")

(time (doseq [pair (get-hash-pairs path)]
        (let [input (str "/tmp/obj_"
                         (print-str (apply str (interpose "-" pair)))
                         ".json")]
          (do
            (spit input (j/write-value-as-string
                         (assoc (let [fullmap (get-diff-content path
                                                                pair) 
                                      out (:out fullmap)
                                      a (clojure.string/split-lines out)
                                      b (mapv (fn [a b]
                                                {a b})
                                              (range (count a))
                                              a)]
                                  (assoc (dissoc fullmap
                                                 :out)
                                         "out_split" b))
                                :abbreviated_commit_hash (last pair))))
            (run-sa-construct (make-query-for-commit-content input)
                              "/mnt/finalout.nq")
            (spit input (get-commit-summary path
                                            (second pair)))
            (run-sa-construct (make-query-for-commit-summary input)
                              "/mnt/finalout_summary.nq")))))
