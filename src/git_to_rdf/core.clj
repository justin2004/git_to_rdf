(ns git-to-rdf.core
  (:require [clojure.data.json :as json])
  (:require [clojure.data.csv :as csv])
  (:require [jsonista.core :as j])
  (:require [jsonista.core :as j])
  (:require [progrock.core :as pr]))

; TODO  add cli options
;       put hunk stuff in a dir, put summary stuff in a dir

; TODO connecting vulnerabilies (CVEs) to file names / commits / tags / releases

; TODO model repo itself and edges between adjacent commits

; TODO /dev/null is semantically opaque

;  TODO don't have a way to identify the same hunk on different runs of this tool

; TODO need to escape header and body and then get them
;     also author can have comma in it

; TODO use more URIs to reduce quad count (e.g. for IDs)

(defn installed? [cmd]
  (= 0 (:exit (clojure.java.shell/sh "bash" "-c" (str "command -V " cmd)))))

(if (not (installed? "splitpatch"))
  (throw (Exception. "ERROR: splitpatch is not installed")))

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


; (identifier \|)
; (first "|")

; (junk 4 :separator 1 :quote \")
; (defn junk [input & options]
;   (let [{:keys [separator quote] :or {separator \, quote \"}} options]
;   options))


; (csv-data->maps (csv/read-csv (with-out-str (printf "one|two|three\n1|2|3"))
;               :separator \| :quote \"))

; get-commit-hash-pairs  repo
; TODO where is this used?
(defn get-commit-hash-pairs [repo-path]
  (csv-data->maps
   (csv/read-csv
    (:out
     (clojure.java.shell/sh
      "bash" "-c" (str "echo 'abbreviated_commit_hash' ; "
                       "git -C "
                       repo-path
                       " --no-pager log --date=local --pretty=%h"))))))


(defn get-hash-pairs [path]
  (let [m (get-commit-hash-pairs path)]
    (partition 2 1
               (conj (reverse (map :abbreviated_commit_hash m))
          "4b825dc642cb6eb9a060e54bf8d69288fbee4904"))))
;          ^ that commit id is the empty tree object

; (clojure.string/trim "one\ntwo\n")
; (if (= "" (clojure.string/trim (get-commit-body "one_ea"
;                  (last (get-hash-pairs "one_ea")))))
;   88)

; need this because body can contain newlines
(defn get-commit-body [repo-path pair]
  (let [result (clojure.string/trim
                (:out
                 (clojure.java.shell/sh 
                  "bash" "-c" (str "git -C " 
                                   repo-path
                                   " --no-pager log --date=local --pretty=%b "
                                   "-1 "
                                   (second pair)))))]
    (if (= result "")
      nil
      result)))

; TODO use hash/commitid consistently

; (csv/read-csv "bob|fred|joe|jim\n\"1\" was her name|2||88"
;               :separator \|
;               :quote \⍕)

; TODO if any of the summary fields contain ⍎ (or ⍕) then this will break
(defn get-commit-summary-maps [repo-path pair origin]
  (let [bigmap (first ; first because there is only 1 csv row per commit summary
                (csv-data->maps
                 (csv/read-csv
                  (:out
                   (clojure.java.shell/sh
                    "bash" "-c" (str "echo 'abbreviated_commit_hash⍎author_name⍎author_email⍎author_date⍎subject' ; "
                                     "git -C "
                                     repo-path
                                                          ; " --no-pager log --date=local --pretty=%h,%an,%ae,%aI,'%s' "
                                     " --no-pager log --date=local --pretty='%h⍎%an⍎%ae⍎%aI⍎%s'"
                                     " -1 "
                                     (second pair))))
                  :separator \⍎
                  :quote \⍕)))]
    (assoc bigmap
           :origin origin
           :body (get-commit-body repo-path pair))))




(defn get-diff-content [repo-path pair]
  "pair -- pair of commit ids"
  (clojure.java.shell/sh 
   "bash" "-c" (str "git -C "
                    repo-path
                    " --no-pager diff "
                    (first pair)
                    " "
                    (second pair))))


; (filter #(= "188c627" (second %))  (get-hash-pairs "sparql.anything"))
; 188c627
; (("6e56d40" "188c627")))


(defn get-commit-summary [repo-path pair origin]
  (json/write-str
   (get-commit-summary-maps repo-path pair origin)))


(defn make-query-for-commit-summary [inputfile]
  (with-out-str (printf (slurp "queries/commit-summary.rq")
                        inputfile)))


(defn make-query-for-hunk [inputfile]
  (with-out-str (printf (slurp "queries/hunk.rq") inputfile)))


(defn change-system-out []
  (let [so (java.lang.System/out) ; TODO restore it?
        out-buffer (new java.io.ByteArrayOutputStream)])
  (java.lang.System/setOut (new java.io.PrintStream 
                                out-buffer
                                true
                                "UTF-8"))
  out-buffer) 

(defn run-sa-construct [query outputfile buffer]
  (do
    (.reset buffer)
    (com.github.sparqlanything.cli.SPARQLAnything/main 
     (into-array ["-q" query 
                  "-f" "NQ"]))
    (spit outputfile
          buffer :append true)))


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

; (doseq [pair (get-hash-pairs "fred")] 
;   (do-hunks "fred" (first (get-hash-pairs "fred")) "OORI" ))
; (spit "junk11" (do-hunks "fred" (second (get-hash-pairs "fred"))))

(defn handle-filename [s]
  (clojure.string/replace (subs s
                                4)
                          #"^[ab]/" ""))


(defn extract-hunk-part [leading-char content-seq]
  (mapv #(subs % 1)
        (filter #(= leading-char
                    (first (subs % 0 1)))
                content-seq)))

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

(defn do-hunks [repopath pair origin]
  "TODO probably doesn't handle space in repopath
  pair -- a commitid pair
  origin -- the url of the remote origin (for minting URIs)
  "
  (do
    ; (printf "in do-hunks. repopath: %s, pair:%s\n" repopath pair)
    ; (printf "in do-hunks. first pair:%s, secondpair:%s, lastpair:%s\n" (first pair)
    ;         (second pair)
    ;         (last pair))
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
    ; TODO splitpatch ignores chmods
    (clojure.java.shell/sh "bash" "-c" "cd /mnt/hunks ; splitpatch -H ../a.patch")
    ; (doseq [hunk (filter #(.isFile %) (file-seq (clojure.java.io/file "hunks")))]
    ;       (clojure.pprint/pprint (assoc (raw-hunk->map (.getPath hunk))
    ;                                     :commit-id (last pair))))
    (let  [result (mapv #(assoc (raw-hunk->map (.getPath %))
                                :commit-id (last pair)
                                :origin origin)
                        (filter #(.isFile %)
                                (file-seq (clojure.java.io/file "hunks"))))]
      (clojure.java.shell/sh "bash" "-c" "rm -rf hunks/*")
      (j/write-value-as-string result))))



; (print (slurp (nth (file-seq (clojure.java.io/file ".")) 
;      6)))

; (clojure.java.shell/sh "pwd")
; (clojure.java.shell/sh "bash" "-c" "cd /mnt/jj ; pwd")
; (file-seq (clojure.java.io/file "/mnt"))


; TODO test
; (handle-filename  "--- a/sparql.anything.xml/pom.xml")
; (handle-filename  "--- /dev/null")






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
; (clojure.pprint/pprint (raw-hunk->map "/mnt/la"))




(defn get-repo-origin [repopath]
  (let [origin-string (clojure.string/trim (:out
                        (clojure.java.shell/sh
                         "bash" "-c"
                         (str "git -C "
                              repopath
                              " remote get-url origin"))))]
    (if (clojure.string/blank? origin-string)
      nil
      origin-string)))


;;;;;;;;;;;;;;;;;;;;;
; (def path "sparql.anything")
; (def path "curl")
(def path "one_ea")
(def path "/mnt/gist")
(def path  "/mnta/rdf_stuff/curl")
; (clojure.pprint/pprint (file-seq (clojure.java.io/file "/mnta/rdf_stuff/curl")))
; (def path "jw")
; (clojure.java.io/delete-file "finalout.nq")
(comment
  (def f0
    (future
      (time
       (let [buffer (change-system-out)
             output-dir "/mnt/" ; "/mnta/rdf_stuff/curl_rdf/"
             hash-pairs (get-hash-pairs path)
             total-hash-pairs (count hash-pairs)
             origin-raw (get-repo-origin path)
             origin (if (empty? origin-raw)
                      (.toString (java.util.UUID/randomUUID))
                      origin-raw)
             bar (pr/progress-bar 100)
             _ (try (clojure.java.io/delete-file (str output-dir "finalout_summary.nq")) ; TODO put in 1 place
                    (catch Exception e))
             _ (try (clojure.java.io/delete-file (str output-dir "finalout_hunks.nq"))
                    (catch Exception e))
             ]
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
               (spit input (get-commit-summary path
                                               pair
                                               origin))
               ; (printf "on idx %s\n" idx)
               ; (printf "pair is %s\n" pair)
               ; (printf "%s -- %s\n" "sec pair" (second pair))
               ; (printf "%s\n" "doing summary on ")
               ; (printf "%s\n" (slurp input))
               ; (printf "%s\n" "done")
               (run-sa-construct (make-query-for-commit-summary input)
                                 (str output-dir "finalout_summary.nq")
                                 buffer)
               (spit input (do-hunks path pair origin))
               ; (printf "path:%s\n" path)
               ; (printf "%s\n" "doing hunks on ")
               ; (printf "%s\n" (slurp input))
               (run-sa-construct (make-query-for-hunk input)
                                 (str output-dir "finalout_hunks.nq")
                                 buffer)))))))))



(comment (clojure.pprint/pprint *e))

(comment (clojure.pprint/pprint f0))
(comment (future-cancel f0))
;;;;

; (def ds (org.apache.jena.tdb2.TDB2Factory/connectDataset
;                   "/tmp/la1"))

; (with-txn ds
;   (print (.getUnionGraph (.asDatasetGraph ds))))
