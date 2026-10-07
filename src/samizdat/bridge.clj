;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.bridge
  "The eval bridge: tools callable as functions from inside an eval in the
  project image, so a branch can compose them in Clojure (karamazov-xn0h.7,
  after arrodes' function-composing REPL).

  THE IMAGE CANNOT CALL OUT. Outbound loopback was taken away from it on
  purpose (karamazov-3vu1.1): the harness's HTTP API and nREPL listen there.
  So nothing here opens a connection from the image. A call queues its
  request in the image and waits on a promise; while an eval is in flight
  the harness asks the image for queued requests on a connection of its own
  (the image runs evals concurrently), runs each, and answers by delivering
  that promise. Inbound only, as before.

  WHY POLLING AND NOT THE EVAL'S OUTPUT. The first version printed a framed
  request and read it off the eval's output as it streamed. Measured: under
  the cloned nREPL session an eval needs to be interruptible, jolt's nREPL
  holds a session's output until the eval ends, so the request arrived
  exactly when the call gave up waiting.

  A REQUEST IS NOT A CAPABILITY. Anything the model's code queues is the
  model asking for a tool call, which is what a tool call already is: the
  caller runs it through the same gated path, and the set of names that can
  be asked for at all is gates.edn :eval-bridge.

  Mechanism only: which tools, how often to ask, how long a call may wait,
  and every sentence the model reads are policy and prompts."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def image-forms
  "The bridge as the image runs it. `call` queues a request and waits for
  its answer; `take-requests!` is what the harness evaluates to collect the
  queue; `deliver!` is what it evaluates to answer. An answer is
  `{:value v}` or `{:error message}`; an error is thrown where the call was
  made, so it fails the form that made it the way any failing call would.
  A request carries the eval that made it (`*eval-id*`, bound by `wrap`).
  `config` is set at install: how long a call waits, and the sentence a call
  that waited that long fails with."
  '[(ns samizdat.bridge)
    (defonce pending (atom {}))
    (defonce requests (atom []))
    (defonce config (atom {}))
    (def ^:dynamic *eval-id* nil)
    (defn call [op args]
      (let [{:keys [timeout-ms timeout-message]} @config
            id (str (random-uuid))
            p (promise)]
        (swap! pending assoc id p)
        (swap! requests conj {:id id :op op :args args :eval *eval-id*})
        (let [answer (deref p timeout-ms ::timeout)]
          (swap! pending dissoc id)
          (cond
            (= ::timeout answer)
            (throw (ex-info timeout-message {:bridge/op op :bridge/timeout-ms timeout-ms}))
            (contains? answer :error)
            (throw (ex-info (str (:error answer)) {:bridge/op op}))
            :else (:value answer)))))
    (defn take-requests! [eval-id]
      (let [mine? #(= eval-id (:eval %))
            taken (filterv mine? @requests)]
        (swap! requests #(filterv (complement (set taken)) %))
        (pr-str taken)))
    (defn deliver! [id answer]
      (if-let [p (get @pending id)]
        (do (deliver p answer) :delivered)
        :gone))])

(defn install-source
  "Source that installs the bridge in an image with `config` (`:timeout-ms`
  and `:timeout-message`); `alias-ns` holding one function per tool in
  `tools`, each a one-map-argument call through the bridge; and, for each
  callable cell id in `cells`, a function of the same name in the id's
  namespace — `:lex/line-count` is `lex/line-count` (karamazov-xn0h.8).
  Evaluated once per image, and again when the set changes."
  [tools cells alias-ns config]
  (str/join "\n"
            ;; With metadata: `*eval-id*` is ^:dynamic, which a plain
            ;; pr-str drops, and binding it then throws in the image.
            (concat (binding [*print-meta* true] (mapv pr-str image-forms))
                    [(pr-str (list 'reset! 'samizdat.bridge/config config))
                     (pr-str (list 'ns alias-ns))]
                    (for [t (sort tools)]
                      (pr-str (list 'defn (symbol t) '[args]
                                    (list 'samizdat.bridge/call t 'args))))
                    (for [id (sort-by str cells)
                          :when (and (keyword? id) (namespace id))
                          f [(list 'in-ns (list 'quote (symbol (namespace id))))
                             '(clojure.core/refer-clojure)
                             (list 'defn (symbol (name id)) '[args]
                                   (list 'samizdat.bridge/call "cell"
                                         {:id id :args 'args}))]]
                      (pr-str f))
                    [(pr-str '(in-ns 'user))])))

(defn take-source
  "The form the harness evaluates to collect the requests eval `eval-id`
  queued — only its own: two branches can evaluate in one image at once, and
  a call must be run with the ctx of the branch that made it."
  [eval-id]
  (pr-str (list 'samizdat.bridge/take-requests! eval-id)))

(defn wrap
  "Form text `text` evaluated with its bridge calls tagged as eval
  `eval-id`'s. A call made on another thread the form starts loses the tag
  and is never answered; it fails at its own timeout."
  [eval-id text]
  (str "(binding [samizdat.bridge/*eval-id* " (pr-str eval-id) "]\n" text "\n)"))

(defn requests
  "The requests a `take-source` eval's printed value holds, or [] when it
  does not read as a vector of them. The image pr-strs the queue and nREPL
  prints that string again, so there may be two layers to read through."
  [printed]
  (let [v (try (edn/read-string (str printed)) (catch Throwable _ nil))
        v (if (string? v) (try (edn/read-string v) (catch Throwable _ nil)) v)]
    (if (vector? v) (filterv #(and (map? %) (:id %) (:op %)) v) [])))

(defn deliver-source
  "The form the harness evaluates in the image to answer request `id`."
  [id answer]
  (pr-str (list 'samizdat.bridge/deliver! id (list 'quote answer))))

(defn answer
  "What `deliver!` hands back for a tool result `r` (`{:category :result}`):
  the result text when the call succeeded or was neutral, the text as an
  error otherwise — a refusal or a failure fails the form that asked."
  [r]
  (if (#{:success :neutral} (:category r))
    {:value (str (:result r))}
    {:error (str (:result r))}))
