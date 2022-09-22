(ns git-to-rdf.core
  (:gen-class)
  (:require [clojure.data.json :as json])
  (:require [clojure.java.shell :as sh])
  (:require [clojure.tools.cli :as cli])
  (:require [clojure.data.csv :as csv])
  (:require [jsonista.core :as j])
  (:require [progrock.core :as pr]))

(defmacro dolog [& printfbody]
  `(spit "git_to_rdf.log" (with-out-str (printf ~@printfbody))
         :append true))

(def cli-options
  [["-r" "--repository PATH" "absolute path to git repository"
    :validate [#(.exists (clojure.java.io/as-file %)) "must be a path that exists"]
    ]
   ["-o" "--output PATH" "absolute path where the output should be saved. 
                         NOTE: the utility will output three files there:
                         summaries.nq
                         hunks.nq
                         git_to_rdf.log"
    :validate [#(.exists (clojure.java.io/as-file %)) "must be a path that exists"]]]
  )



; TODO connecting vulnerabilies (CVEs) to file names / commits / tags / releases

; TODO model repo itself and edges between adjacent commits

; TODO /dev/null is semantically opaque

;  TODO don't have a way to identify the same hunk on different runs of this tool

; TODO need to escape header and body and then get them
;     also author can have comma in it

; TODO use more URIs to reduce quad count (e.g. for IDs)

(defn installed? [cmd]
  (= 0 (:exit (sh/sh "bash" "-c" (str "command -V " cmd)))))


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
     (sh/sh
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
                 (sh/sh 
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
                   (sh/sh
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
  (sh/sh 
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
  (with-out-str (printf (slurp "queries/hunk.rq") 
                        inputfile)))


(defn change-system-out []
  (let [so (java.lang.System/out) ; TODO restore it?
        out-buffer (new java.io.ByteArrayOutputStream)]
    (java.lang.System/setOut (new java.io.PrintStream 
                                  out-buffer
                                  true
                                  "UTF-8"))
    out-buffer))

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
    (clojure.java.io/delete-file "/tmp/a.patch" true)
    (sh/sh "bash" "-c" "rm -rf /tmp/hunks")
    (.mkdir (clojure.java.io/file "/tmp/hunks"))
    (sh/sh "bash" "-c" (str "git -C "
                                            repopath
                                            " diff -U0 "
                                            (first pair)
                                            " "
                                            (second pair)
                                            " > /tmp/a.patch"))
    ; TODO splitpatch ignores chmods
    (sh/sh "bash" "-c" "cd /tmp/hunks ; splitpatch -H /tmp/a.patch")
    ; (doseq [hunk (filter #(.isFile %) (file-seq (clojure.java.io/file "hunks")))]
    ;       (clojure.pprint/pprint (assoc (raw-hunk->map (.getPath hunk))
    ;                                     :commit-id (last pair))))
    (let  [result (mapv #(assoc (raw-hunk->map (.getPath %))
                                :commit-id (last pair)
                                :origin origin)
                        (filter #(.isFile %)
                                (file-seq (clojure.java.io/file "/tmp/hunks"))))]
      (sh/sh "bash" "-c" "rm -rf /tmp/hunks/*")
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
                        (sh/sh
                         "bash" "-c"
                         (str "git -C "
                              repopath
                              " remote get-url origin"))))]
    (if (clojure.string/blank? origin-string)
      nil
      origin-string)))

; (sh/sh "bash" "-c" "echo hi")

;;;;;;;;;;;;;;;;;;;;;
; (def path "sparql.anything")
; (def path "curl")
; (def path "one_ea")
; (def path "/mnt/gist")
; (def path  "/mnta/rdf_stuff/curl")
; (clojure.pprint/pprint (file-seq (clojure.java.io/file "/mnta/rdf_stuff/curl")))
; (def path "jw")
; (clojure.java.io/delete-file "finalout.nq")

(defn doit [path output-dir]
  (let [ summaries-filename "summaries.nq"    
         hunks-filename "hunks.nq"
        buffer (change-system-out)
             hash-pairs (get-hash-pairs path)
             total-hash-pairs (count hash-pairs)
             origin-raw (get-repo-origin path)
             origin (if (empty? origin-raw)
                      (.toString (java.util.UUID/randomUUID))
                      origin-raw)
             bar (pr/progress-bar 100)
             _ (try (clojure.java.io/delete-file (str output-dir "/" summaries-filename))
                    (catch Exception e))
             _ (try (clojure.java.io/delete-file (str output-dir "/" hunks-filename))
                    (catch Exception e))
             ]
         (doseq [[pair idx] (map list hash-pairs
                                 (range 1 (+ 1 (count hash-pairs))))]
           (let [input (str "/tmp/obj_"
                            (print-str (apply str (interpose "-" pair)))
                            ".json")]
             (do
               (if (= 0 (mod idx 2)); every 2 commits do a tick update
                 (pr/print (pr/tick bar (* 100 (/ idx total-hash-pairs))))
                 ) 
               ; (dolog "working on hash: %s\n" (last pair))
               (spit input (get-commit-summary path pair origin))
               ; (dolog "on idx %s\n" idx)
               ; (dolog "pair is %s\n" pair)
               ; (dolog "%s -- %s\n" "sec pair" (second pair))
               ; (dolog "%s\n" "doing summary on ")
               ; (dolog "%s\n" (slurp input))
               ; (dolog "%s\n" "done")
               (run-sa-construct (make-query-for-commit-summary input)
                                 (str output-dir "/" summaries-filename)
                                 buffer)
               (spit input (do-hunks path pair origin))
               ; (printf "path:%s\n" path)
               ; (dolog "%s\n" "doing hunks on ")
               ; (dolog "%s\n" (slurp input))
               (run-sa-construct (make-query-for-hunk input)
                                 (str output-dir "/" hunks-filename)
                                 buffer))))
    (pr/print (pr/done bar))))


;;;;

; (def ds (org.apache.jena.tdb2.TDB2Factory/connectDataset
;                   "/tmp/la1"))

; (with-txn ds
;   (print (.getUnionGraph (.asDatasetGraph ds))))



; (defn doit [path output-dir]
; (print (cli/parse-opts args cli-options))

; (doit "/mnt/one_ea/" "/mnt/")

(defn -main [& args]
  (let [_ (if (not (installed? "splitpatch"))
            (throw (Exception. "ERROR: splitpatch is not installed")))
        opts (cli/parse-opts args cli-options)
        _ (dolog "opts are: %s" (with-out-str (clojure.pprint/pprint opts)))
        r (:repository (:options opts))
        _ (if (or (not (nil? (:errors opts)))
                  (or (nil? (:repository (:options opts)))
                            (nil? (:output (:options opts)))))
            (do
              (if (not (nil? (:errors opts)))
                (.println java.lang.System/err (:errors opts)))
              (.println java.lang.System/err (:summary opts))
              (java.lang.System/exit 1)))]
    (doit (:repository (:options opts))
          (:output (:options opts))))
  (java.lang.System/exit 0))

