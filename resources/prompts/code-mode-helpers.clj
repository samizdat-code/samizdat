;; The code-mode helpers: short functions an eval calls by name, installed in
;; the project image with the eval bridge and referred into every eval
;; session's namespace (karamazov-6p51.4).
;;
;; After xi's clj tool (https://github.com/floscr/xi, src/xi/ext/clj.cljs, MIT,
;; (c) Florian Schroedl): Unix names a model already knows, values a script
;; can compose — (sh ...) returns stdout and throws with {:exit :out :err} —
;; instead of the text written for the model.
;;
;; READS run here, in the image; anything that writes or runs a program goes
;; through the bridge (`samizdat.bridge/call`), so it takes the same gated
;; path as the tool would — policy, approval, the write checks, the journal.
;;
;; This file is userspace: the agent may add a helper or change one. The eval
;; tool's description lists them (prompts/system-lean.md); keep the two in step.

(ns samizdat.code
  ;; `cat` and `spit` are clojure.core names; these replace them where they are
  ;; referred. `cat` still works as the transducer when handed a function.
  (:refer-clojure :exclude [cat spit])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json-lib]))

(def ^:private skip-dirs #{".git" ".samizdat" "node_modules" "target" ".cpcache" "__pycache__" ".venv"})

(defn- file ^java.io.File [p] (io/file (str p)))

(defn- quote-arg [s]
  (let [s (str s)]
    (if (re-matches #"[A-Za-z0-9_./:=@%+,^-]+" s)
      s
      (str "'" (str/replace s "'" "'\\''") "'"))))

;; --- programs ---------------------------------------------------------------

(defn sh
  "Run a program, argv-style: (sh \"make\" \"test\"). Options first:
  (sh {:dir \"sub\" :env {\"PORT\" 8080}} \"npm\" \"start\"). Returns stdout;
  a non-zero exit throws, (ex-data e) holding {:exit :out :err}."
  [& args]
  (let [[opts argv] (if (map? (first args)) [(first args) (rest args)] [{} args])
        command (str/join " " (map quote-arg argv))
        command (if (seq (:env opts))
                  (str (str/join " " (for [[k v] (:env opts)] (str (name k) "=" (quote-arg v))))
                       " " command)
                  command)
        command (if (:dir opts) (str "cd " (quote-arg (:dir opts)) " && " command) command)
        d (samizdat.bridge/call-data "shell" {:command command})]
    (if (map? d) (:out d) d)))

(defn sh*
  "Like sh, but never throws for an exit code: returns {:exit :out :err}."
  [& args]
  (try (let [out (apply sh args)] {:exit 0 :out out :err ""})
       (catch Exception e
         (let [d (ex-data e)]
           (if (contains? d :exit) (select-keys d [:exit :out :err]) (throw e))))))

(defn which
  "The path of program `cmd` on PATH, or nil."
  [cmd]
  (some (fn [dir] (let [f (io/file dir (str cmd))]
                    (when (and (.isFile f) (.canExecute f)) (.getPath f))))
        (str/split (or (System/getenv "PATH") "") #":")))

(defn curl
  "An HTTP request through curl: (curl url) or (curl url {:method :post
  :headers {\"K\" \"v\"} :body s}). Returns {:status :body}."
  ([url] (curl url {}))
  ([url {:keys [method headers body]}]
   (let [marker "\n__status__:"
         args (concat ["curl" "-sS" "-L" "-X" (str/upper-case (name (or method :get)))]
                      (mapcat (fn [[k v]] ["-H" (str (name k) ": " v)]) headers)
                      (when body ["--data-binary" (str body)])
                      ["-w" (str marker "%{http_code}") (str url)])
         out (apply sh args)
         i (str/last-index-of out marker)]
     {:status (parse-long (str/trim (subs out (+ i (count marker)))))
      :body (subs out 0 i)})))

(defn json
  "Parse JSON text into Clojure data (keys as keywords)."
  [s]
  (json-lib/read-str (str s) :key-fn keyword))

(defn json-str
  "Clojure data as JSON text."
  [x]
  (json-lib/write-str x))

;; --- reading ----------------------------------------------------------------

(defn cat
  "The file's text: (cat \"src/a.clj\"). Handed a function, clojure.core/cat."
  [x]
  (if (fn? x) (clojure.core/cat x) (slurp (file x))))

(defn- lines [f] (str/split-lines (slurp (file f))))

(defn head
  "The first n lines of a file (default 10), as one string."
  ([f] (head f 10))
  ([f n] (str/join "\n" (take n (lines f)))))

(defn tail
  "The last n lines of a file (default 10), as one string."
  ([f] (tail f 10))
  ([f n] (str/join "\n" (take-last n (lines f)))))

(defn exists? [p] (.exists (file p)))

(defn stat
  "{:size :dir? :file? :mtime-ms} for path p, or nil when it does not exist."
  [p]
  (let [f (file p)]
    (when (.exists f)
      {:size (.length f) :dir? (.isDirectory f) :file? (.isFile f) :mtime-ms (.lastModified f)})))

(defn ls
  "The entries of a directory (default \".\"), sorted; directories end in /."
  ([] (ls "."))
  ([d] (->> (.listFiles (file d))
            (map (fn [^java.io.File f] (str (.getName f) (when (.isDirectory f) "/"))))
            sort vec)))

(defn- walk
  "Every file under dir, as paths relative to it, skipping VCS and build dirs."
  [dir]
  (let [root (file dir)
        base (.getPath root)
        strip (fn [^java.io.File f]
                (let [p (.getPath f)]
                  (if (= base ".")
                    (str/replace p #"^\./" "")
                    p)))]
    (letfn [(go [^java.io.File d]
              (mapcat (fn [^java.io.File f]
                        (cond
                          (.isDirectory f) (when-not (skip-dirs (.getName f)) (go f))
                          :else [(strip f)]))
                      (.listFiles d)))]
      (sort (go root)))))

(defn- glob->re [pat]
  (loop [cs (seq pat) out "" brace? false]
    (if-let [c (first cs)]
      (let [nxt (second cs)]
        (cond
          (and (= c \*) (= nxt \*))
          (if (= \/ (nth (vec cs) 2 nil))
            (recur (drop 3 cs) (str out "(?:.*/)?") brace?)
            (recur (drop 2 cs) (str out ".*") brace?))
          (= c \*) (recur (rest cs) (str out "[^/]*") brace?)
          (= c \?) (recur (rest cs) (str out "[^/]") brace?)
          (= c \{) (recur (rest cs) (str out "(?:") true)
          (and brace? (= c \})) (recur (rest cs) (str out ")") false)
          (and brace? (= c \,)) (recur (rest cs) (str out "|") brace?)
          (re-matches #"[A-Za-z0-9_/-]" (str c)) (recur (rest cs) (str out c) brace?)
          :else (recur (rest cs) (str out "\\" c) brace?)))
      (re-pattern out))))

(defn glob
  "Paths matching a glob under the working directory: (glob \"src/**/*.clj\")."
  [pat]
  (let [re (glob->re (str/replace (str pat) #"^\./" ""))]
    (vec (filter #(re-matches re %) (walk ".")))))

(defn- text-file? [p]
  (let [f (file p)]
    (and (< (.length f) 2000000)
         (not (re-find #"\.(png|jpe?g|gif|ico|pdf|zip|gz|tgz|jar|class|so|dylib|o|a|bin|pt|ckpt|npy|sqlite3?)$" (str p))))))

(defn grep
  "Lines matching a regex (or a regex string) as \"path:line:text\" strings:
  (grep #\"TODO\") under the working directory, (grep \"defn \" \"src\") under
  a directory, or in one file."
  ([re] (grep re "."))
  ([re path]
   (let [re (if (string? re) (re-pattern re) re)
         f (file path)
         files (if (.isDirectory f)
                 (map #(if (= "." (str path)) % (str path "/" %)) (walk path))
                 [(str path)])]
     (vec (for [p files
                :when (text-file? p)
                [i line] (map-indexed vector (try (lines p) (catch Exception _ nil)))
                :when (re-find re line)]
            (str p ":" (inc i) ":" line))))))

;; --- writing (through the bridge, checked like the file tools) ---------------

(defn spit
  "Write a file through write_file: checked, recorded, refused when it would
  break the file. (spit f s {:append true}) appends. Returns the tool's report."
  ([f content] (spit f content {}))
  ([f content {:keys [append]}]
   (let [content (str (when (and append (exists? f)) (slurp (file f))) content)]
     (samizdat.bridge/call "write_file" {:path (str f) :content content}))))

(defn edit
  "Replace old with new in a file through edit_file: (edit f old new)."
  [f old new]
  (samizdat.bridge/call "edit_file" {:path (str f) :old_text old :new_text new}))

(defn mkdir [d] (sh "mkdir" "-p" d) nil)
(defn mv [a b] (sh "mv" "-f" a b) nil)
(defn cp [a b] (sh "cp" "-rf" a b) nil)
(defn rm
  "Delete files; (rm {:r true} dir) deletes a directory tree."
  [& args]
  (let [[opts ps] (if (map? (first args)) [(first args) (rest args)] [{} args])]
    (apply sh "rm" (if (:r opts) "-rf" "-f") ps)
    nil))

;; --- installing --------------------------------------------------------------

(def shadowed
  "The clojure.core names the helpers replace where they are referred."
  '[cat spit])

(defn refer-into!
  "Refer every helper into namespace `ns-sym`, replacing the core names in
  `shadowed`. The harness calls this for each eval session's namespace."
  [ns-sym]
  (let [n (create-ns ns-sym)]
    (binding [*ns* n]
      (refer-clojure)
      (doseq [s shadowed] (ns-unmap n s))
      (refer 'samizdat.code))
    :ok))
