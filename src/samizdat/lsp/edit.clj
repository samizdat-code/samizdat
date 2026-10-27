;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.lsp.edit
  "Normalize LSP WorkspaceEdit values into data-only resource change members."
  (:require [clojure.string :as str]))

(defn- fail [kind data]
  (throw (ex-info (name kind) (assoc data :lsp-edit/error kind))))

(defn- line-start [text line-number]
  (loop [index 0 line 0]
    (cond
      (= line line-number) index
      (>= index (count text)) (fail :invalid-position {:line line-number :character 0})
      :else (let [newline (str/index-of text "\n" index)]
              (if (nil? newline)
                (fail :invalid-position {:line line-number :character 0})
                (recur (inc newline) (inc line)))))))

(defn- utf16-index [text {:keys [line character]}]
  (when (or (neg? line) (neg? character))
    (fail :invalid-position {:line line :character character}))
  (let [start (line-start text line)
        line-end (let [newline (str/index-of text "\n" start)]
                   (if (nil? newline) (count text) newline))]
    (loop [index start units 0]
      (cond
        (= units character) index
        (>= index line-end) (fail :invalid-position {:line line :character character})
        :else
        (let [codepoint (int (nth text index))
              width (if (>= codepoint Character/MIN_SUPPLEMENTARY_CODE_POINT) 2 1)
              next-units (+ units width)]
          (when (> next-units character)
            (fail :invalid-position {:line line :character character}))
          (recur (inc index) next-units))))))

(defn apply-text-edits
  "Apply non-overlapping LSP TextEdits using UTF-16 range coordinates."
  [text edits]
  (let [located (mapv (fn [{:keys [range newText]}]
                        {:start (utf16-index text (:start range))
                         :end (utf16-index text (:end range))
                         :newText (str newText)})
                      edits)
        _ (doseq [{:keys [start end]} located]
            (when (> start end)
              (fail :invalid-range {:start start :end end})))
        ascending (sort-by :start located)]
    (doseq [[left right] (partition 2 1 ascending)]
      (cond
        (and (= (:start left) (:end left))
             (= (:start right) (:end right))
             (= (:start left) (:start right)))
        (fail :ambiguous-edits {:left left :right right})

        (< (:start right) (:end left))
        (fail :overlapping-edits {:left left :right right})))
    (reduce (fn [result {:keys [start end newText]}]
              (str (subs result 0 start) newText (subs result end)))
            text
            (sort-by :start #(compare %2 %1) located))))

(defn- path [uri]
  (let [parsed (java.net.URI.
                (if (keyword? uri)
                  (if-let [scheme (namespace uri)]
                    (str scheme
                         (when-not (or (str/ends-with? scheme ":")
                                       (str/starts-with? (name uri) ":"))
                           ":")
                         (name uri))
                    (name uri))
                  (str uri)))]
    (when-not (= "file" (.getScheme parsed))
      (fail :unsupported-uri {:uri uri}))
    (.getPath parsed)))

(defn- text-edits [edit]
  (concat
   (for [[uri edits] (:changes edit)] [uri edits])
   (for [{:keys [textDocument edits]} (:documentChanges edit)
         :when textDocument]
     [(:uri textDocument) edits])))

(defn- member [read-observation uri edits]
  (let [_ (path uri)
        observation (read-observation uri)]
    (when-not observation
      (fail :missing-observation {:uri uri}))
    (assoc observation
           :operation :write
           :original (:content observation)
           :replacement (apply-text-edits (:content observation) edits)
           :base-revision (:revision observation))))

(defn- file-member [read-observation {:keys [kind uri oldUri newUri options]}]
  (case kind
    "create"
    (let [observation (read-observation uri)]
      (assoc observation :operation :create :original (:content observation)
             :replacement "" :base-revision (:revision observation)
             :overwrite? (boolean (:overwrite options))))

    "delete"
    (let [observation (read-observation uri)]
      (assoc observation :operation :delete :original (:content observation)
             :replacement nil :base-revision (:revision observation)))

    "rename"
    (let [source (or oldUri uri)
          observation (read-observation source)]
      (assoc observation :operation :rename :original (:content observation)
             :replacement (:content observation) :replacement-uri newUri
             :base-revision (:revision observation)
             :overwrite? (boolean (:overwrite options))
             :metadata (assoc (:metadata observation) :target-path (path newUri))))

    (fail :unsupported-file-operation {:kind kind :uri (or uri oldUri)})))

(defn workspace-edit->members
  "Convert a WorkspaceEdit to staged resource data without publishing anything."
  [edit read-observation]
  (vec
   (concat
    (map (fn [[uri edits]] (member read-observation uri edits)) (text-edits edit))
    (map #(file-member read-observation %)
         (remove :textDocument (:documentChanges edit))))))
