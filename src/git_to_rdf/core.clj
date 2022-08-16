(ns git-to-rdf.core)
(require '[clojure.data.json :as json])
(require '[clojure.data.csv :as csv])
(require '[jsonista.core :as j])
(require '[jsonista.core :as j])
(require '[progrock.core :as pr])

(def bar (pr/progress-bar 100   ))
(pr/print (pr/tick bar 8.13))
(print (pr/render bar))

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



; get-commit-hash-pairs  repo
(defn get-commit-hash-pairs [repo-path]
  (csv-data->maps (csv/read-csv (:out (clojure.java.shell/sh "bash" "-c" (str "echo 'abbreviated_commit_hash' ; "
                                        "git -C "
                                        repo-path
                                        " --no-pager log --date=local --pretty=%h"))))))


(defn get-hash-pairs [path]
  (let [m (get-commit-hash-pairs path)]
    (partition 2 1
               (conj (reverse (map :abbreviated_commit_hash m))
          "4b825dc642cb6eb9a060e54bf8d69288fbee4904"))))


(get-hash-pairs "jj")

; TODO use hash/commitid consistently

; get-commit-summary-map  repo hash
(defn get-commit-summary-maps [repo-path pair]
  (csv-data->maps
   (csv/read-csv
    (:out
     (clojure.java.shell/sh "bash" "-c" (str "echo 'abbreviated_commit_hash,author_name,author_email,author_date,subject,body' ; "
                                             "git -C " 
                                             repo-path
                                             ; " --no-pager log --date=local --pretty=%h,%an,%ae,%aI,'%s' "
                                             " --no-pager log --date=local --pretty=%h,%an,%ae,%aI "
                                             "-1 "
                                             (second pair)))))))


(defn get-diff-content [repo-path pair]
  "pair -- pair of commit ids"
  (clojure.java.shell/sh "bash" "-c" (str "git -C "
                                          repo-path
                                          " --no-pager diff "
                                          (first pair)
                                          " "
                                          (second pair))))


(filter #(= "188c627" (second %))  (get-hash-pairs "sparql.anything"))
; 188c627
; (("6e56d40" "188c627")))


(defn get-commit-summary [repo-path pair]
  (json/write-str
   (get-commit-summary-maps repo-path
                             pair)))



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
                        ?commit gist:isIdentifiedBy ?commit_id .
                        ?commit_id gist:uniqueText ?hash .
						?author gist:name ?name .
						?author schema:email ?email .
						?commit dcterms:subject ?subject .
						# ?subject dcterms:title ?subject_text .
						?subject dcterms:title \"TODO subject goes here\" .
						?subject dcterms:description \"TODO body goes here\" .
						#?commit gist:contains ?content . # TODO gist needs this
						}
						WHERE
						{ SERVICE <x-sparql-anything:>
						{ fx:properties fx:location  \"%s\" .
						# TODO should be optionals?
						?s xyz:abbreviated_commit_hash ?hash .
						?s xyz:author_name ?name .
						?s xyz:author_email ?email .
						?s xyz:author_date ?date_string .
						optional {?s xyz:subject ?subject_text }
                        bind(bnode() as ?commit_id)
						bind(strdt(?date_string,xsd:dateTime) as ?date)
						bind(iri(concat(str(:),\"commit/\",?hash)) as ?commit)
						bind(bnode() as ?author)
						bind(bnode() as ?subject)
						}
						}
						"
						inputfile)))


(defn make-query-for-hunk [inputfile]
  (with-out-str (printf "PREFIX  sd:   <https://w3id.org/okn/o/sd#>
PREFIX  fx:   <http://sparql.xyz/facade-x/ns/>
PREFIX  rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX  xyz:  <http://sparql.xyz/facade-x/data/>
PREFIX  i:    <https://www.w3id.org/okn/i/>
PREFIX  rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX  :     <http://example.com/>
PREFIX wd:       <http://www.wikidata.org/entity/>         # Wikibase entity - item or property. 
prefix dcterms: <http://purl.org/dc/terms/> 
prefix gist: <https://ontologies.semanticarts.com/gist/>
prefix schema: <https://schema.org/>
PREFIX  xsd:  <http://www.w3.org/2001/XMLSchema#>
construct {
?commit :contains ?hunk .
?hunk a wd:Q113509427 .
?hunk gist:affects ?old_file_plaintext .
?old_file_plaintext a wd:Q113515824 . # contiguous lines
?old_file_plaintext gist:hasMagnitude ?old_source_line_count_mag .
?old_source_line_count_mag gist:numericValue ?old_source_line_count .
?old_source_line_count_mag gist:hasUnitOfMeasure gist:_each .
?old_file_plaintext gist:occursIn ?old_file .
?old_file gist:name ?old_filename . # TODO name vs path
?old_file a wd:Q86920 . # text file
?old_file_plaintext gist:identifiedBy ?old_contiguous_lines_id .
?old_contiguous_lines_id gist:numericValue ?old_source_start_line_no .
?old_contiguous_lines_id a wd:Q6553274 . # line number
?old_file_plaintext :containedTextContainer ?old_content . # TODO predicate
?old_content ?old_content_p ?old_content_line .
?hunk gist:produces ?new_file_plaintext .
?new_file_plaintext gist:occursIn ?new_file .
?new_file_plaintext a wd:Q113515824 . # contiguous lines
?new_file_plaintext gist:hasMagnitude ?new_source_line_count_mag .
?new_source_line_count_mag gist:numericValue ?new_source_line_count .
?new_source_line_count_mag gist:hasUnitOfMeasure gist:_each .
?new_file gist:name ?new_filename .
?new_file a wd:Q86920 . # text file
?new_file_plaintext gist:identifiedBy ?new_contiguous_lines_id .
?new_contiguous_lines_id gist:numericValue ?new_source_start_line_no .
?new_contiguous_lines_id a wd:Q6553274 . # line number
?new_file_plaintext :containedTextContainer ?new_content . # TODO predicate
?new_content ?new_content_p ?new_content_line .
}
WHERE
  { SERVICE <x-sparql-anything:>
      { fx:properties fx:location  \"%s\" .
# ?s ?p ?o .
        ?s xyz:commit-id ?hash .
        bind(iri(concat(str(:),\"commit/\",?hash)) as ?commit)
        optional{ ?s xyz:new_content ?new_content . 
                  bind(bnode() as ?new_contiguous_lines_id)
                  bind(bnode() as ?new_file_plaintext)
                  bind(bnode() as ?new_file)
                  bind(iri(concat(str(:),\"commit_hunk/\",struuid())) as ?hunk) # only bind per hunk not once per hunk line
                  ?s xyz:new_source_line_count ?new_source_line_count_string .
                  bind(strdt(?new_source_line_count_string,xsd:integer) as ?new_source_line_count)
                  bind(bnode() as ?new_source_line_count_mag)
            {?new_content ?new_content_p ?new_content_line .}
        }
        ?s xyz:new_filename ?new_filename .
        ?s xyz:new_source_start_line ?new_source_start_line .
        bind(strdt(?new_source_start_line,xsd:integer) as ?new_source_start_line_no)
        optional {?s xyz:old_content ?old_content .
                  bind(bnode() as ?old_file)
                  bind(bnode() as ?old_file_plaintext)
                  bind(bnode() as ?old_contiguous_lines_id)
                  ?s xyz:old_source_line_count ?old_source_line_count_string .
                  bind(strdt(?old_source_line_count_string,xsd:integer) as ?old_source_line_count)
                  bind(bnode() as ?old_source_line_count_mag)
              {?old_content ?old_content_p ?old_content_line .}
        }
        ?s xyz:old_filename ?old_filename .
        ?s xyz:old_source_start_line ?old_source_start_line .
        bind(strdt(?old_source_start_line,xsd:integer) as ?old_source_start_line_no)
      }
  }" inputfile)))

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

(doseq [pair (get-hash-pairs "fred")] 
  (do-hunks "fred" pair ))
(spit "junk11" (do-hunks "fred" (second (get-hash-pairs "fred"))))


(do-hunks "jj" (first (get-hash-pairs "jj")))
(get-hash-pairs "jj")

(defn do-hunks [repopath pair]
  "TODO probably doesn't handle space in repopath
  pair -- a commitid pair"
  (do
    ; (printf "in do-hunks. repopath: %s, pair:%s\n" repopath pair)
    ; (printf "in do-hunks. first pair:%s, secondpair:%s, lastpair:%s\n" (first pair)
            (second pair)
            (last pair))
    (clojure.java.io/delete-file "a.patch" true)
    (clojure.java.shell/sh "bash" "-c" "rm -rf hunks")
    (.mkdir (clojure.java.io/file "hunks"))
    (clojure.java.shell/sh "bash" "-c" (str "git -C "
                                            repopath
                                            " diff -U0 "
                                            (first pair)
                                            " "
                                            (second pair)
                                            " > a.patch"))
    (clojure.java.shell/sh "bash" "-c" "cd /mnt/hunks ; splitpatch -H ../a.patch")
    ; (doseq [hunk (filter #(.isFile %) (file-seq (clojure.java.io/file "hunks")))]
    ;       (clojure.pprint/pprint (assoc (raw-hunk->map (.getPath hunk))
    ;                                     :commit-id (last pair))))
    (let  [result (mapv #(assoc (raw-hunk->map (.getPath %))
                                :commit-id (last pair))
                        (filter #(.isFile %)
                                (file-seq (clojure.java.io/file "hunks"))))]
      (clojure.java.shell/sh "bash" "-c" "rm -rf hunks/*")
      (j/write-value-as-string result))))



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
                   :old_source_line_count (if (nil? (second (first deets1)))
                                            "1"
                                            (second (first deets1)))
                   :new_source_start_line (first (second deets1))
                   :new_source_line_count (if (nil? (second (second deets1)))
                                            "1"
                                            (second (second deets1)))
                   :old_content (extract-hunk-part \- content)
                   :new_content (extract-hunk-part \+ content)}]
    deets-map))


(defn extract-hunk-part [leading-char content-seq]
  (mapv #(subs % 1)
        (filter #(= leading-char
                    (first (subs % 0 1)))
                content-seq)))


; (print (slurp (nth (file-seq (clojure.java.io/file ".")) 
;      6)))

; (clojure.pprint/pprint (filter #(.isFile %) (file-seq (clojure.java.io/file "."))))

; (clojure.pprint/pprint (raw-hunk->map "/mnt/la1"))
; (print (j/write-value-as-string (raw-hunk->map "/mnt/la")))


; thanks to  https://www.clearchain.com/blog/posts/splitting-a-patch
;
; the first number is the starting line for this hunk in oldfile
; the second number is the number of original source lines in this hunk (this includes lines marked with “-“)
; the third number is the starting line for this hunk in newfile
; the last number is the number of lines after the hunk has been applied.
(clojure.pprint/pprint (raw-hunk->map "/mnt/la"))

;;;;;;;;;;;;;;;;;;;;;
(def path "sparql.anything")
(def path "curl")
(def path "jw")
; (clojure.java.io/delete-file "finalout.nq")
(clojure.java.io/delete-file "finalout_summary.nq")
(clojure.java.io/delete-file "finalout_hunks.nq")

(count (get-hash-pairs path))
(doseq [thing [1 2 3 4]
        idx (range 4)]
  (printf "%s - %s\n" thing idx))

(doseq [[pair idx] 
        (map list ["one" "two" "t" "fo"]
             (range 1 5))]
  (printf "%s - %s\n" pair idx))

(* 100 (float (/ 500 1000)))
(= 0 (mod 40 20))

(def bar (pr/progress-bar 100   ))
(pr/print (pr/tick bar 8.13))
(print (pr/render bar))

(doseq [[pair idx] (map list '((a b) (b c) (c d))
                        (range 3))]
  (printf "%s-%s\n" pair idx))

; TODO throw error if splitpatch is not installed

(time (let [hash-pairs (get-hash-pairs path)
            total-hash-pairs (count hash-pairs)
            bar (pr/progress-bar 100)]
        (doseq [[pair idx] (map list hash-pairs
                                (range 1 (+ 1 (count hash-pairs))))]
          (let [input (str "/tmp/obj_"
                           (print-str (apply str (interpose "-" pair)))
                           ".json")]
            (do
              (if (= 0 (mod idx 20)); every 20 commits do a tick update
                (pr/print (pr/tick bar (* 100 (/ idx total-hash-pairs))))
                ) 
              (printf "working on hash: %s\n" (last pair))
              (comment (spit input (j/write-value-as-string
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
                                           :abbreviated_commit_hash (last pair)))))
              (comment (run-sa-construct (make-query-for-commit-content input)
                                         "/mnt/finalout.nq"))
              (spit input (get-commit-summary path
                                              pair))
              ; (printf "on idx %s\n" idx)
              ; (printf "pair is %s\n" pair)
              ; (printf "%s -- %s\n" "sec pair" (second pair))
              ; (printf "%s\n" "doing summary on ")
              ; (printf "%s\n" (slurp input))
              ; (printf "%s\n" "done")
              (run-sa-construct (make-query-for-commit-summary input)
                                "/mnt/finalout_summary.nq")
              (spit input (do-hunks path pair))
              (printf "%s\n" "doing hunks on ")
              (printf "%s\n" (slurp input))
              (run-sa-construct (make-query-for-hunk input)
                                "/mnt/finalout_hunks.nq"))))))


; (("6e56d40" "188c627")))
(clojure.java.io/delete-file "/mnt/junk1.json")
(spit "/mnt/junk1.json" (get-commit-summary "jj"
                    "03cd687"))

(run-sa-construct (make-query-for-commit-summary "/mnt/junk1.json")
                              "/mnt/finalout_summary.nq")

(clojure.pprint/pprint *e)
