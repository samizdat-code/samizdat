;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource.workspace
  "Workspace files as revisioned resources."
  (:require [clojure.string :as str]
            [jolt.fs :as fs]
            [samizdat.agent.files :as files]
            [samizdat.agent.source :as source]
            [samizdat.resource :as resource]))

(defn content-revision
  "The SHA-256 revision of UTF-8 text, or nil for absent content."
  [text]
  (when (some? text)
    (let [digest (java.security.MessageDigest/getInstance "SHA-256")
          bytes (.digest digest (.getBytes ^String (str text) "UTF-8"))]
      (apply str (map #(format "%02x" %) bytes)))))

(defn- fail [kind uri]
  (throw (ex-info (name kind) {:resource/error kind :uri uri})))

(defn- read-path [resolver resource ctx]
  (let [opts (:opts resolver)
        root (:root ctx)
        identity (:identity resource)
        refs (or (:refs opts) (files/ctx-reference-roots ctx))
        exists? (or (:exists? opts) #(fs/exists? (str %)))
        candidates (cons (files/resolve-under-root root identity)
                         (map #(files/resolve-under-root % identity) refs))]
    (or (some #(when (and % (exists? %)) %) candidates)
        (files/resolve-under-root root identity)
        (fail :outside-root (:uri resource)))))

(defn- observation [resolver resource ctx]
  (let [path (read-path resolver resource ctx)
        exists? (or (get-in resolver [:opts :exists?])
                    #(fs/exists? (str %)))
        present (boolean (exists? path))
        content (when present
                  ((or (get-in resolver [:opts :read]) slurp) path))]
    (assoc resource
           :revision (content-revision content)
           :content content
           :metadata {:path path :missing? (not present)})))

(defn- window [observation {:keys [offset limit]}]
  (let [content (:content observation)]
    (when (nil? content)
      (fail :missing-resource (:uri observation)))
    (let [start (long (or offset 0))
          amount (long (or limit (- (count content) start)))
          end (min (count content) (+ start amount))]
      {:content (subs content start end)
       :next-offset (when (< end (count content)) end)})))

(defrecord WorkspaceResolver [opts]
  resource/Resolver
  (resolve-resource [_ uri]
    {:uri uri :resolver :workspace :identity (subs uri (count resource/workspace-prefix))})
  (observe-resource [this resource ctx]
    (observation this resource ctx))
  (read-resource [_ observation _ctx opts]
    (window observation opts))
  (resource-operations [_ resource _ctx]
    [{:operation :read :uri (:uri resource)}
     {:operation :replace :uri (:uri resource)}]))

(defn workspace-resolver
  "A workspace resolver with optional :read, :exists?, and :refs effect seams."
  [opts]
  (->WorkspaceResolver opts))

(defn- clojure-file? [path]
  (contains? #{"clj" "cljc" "cljs" "cljd" "edn" "bb"}
             (last (str/split (str path) #"\."))))

(defn stage-workspace
  "Retain original and replacement snapshots for observed workspace resources.

  `replacements` maps each observation URI to complete replacement text. No
  filesystem mutation occurs here."
  [resolver ctx observations replacements]
  (mapv
   (fn [{:keys [uri identity content revision metadata] :as _observation}]
     (when-not (contains? replacements uri)
       (fail :missing-replacement uri))
     (let [path (files/resolve-under-root (:root ctx) (or (:path metadata) identity))
           _ (when-not path (fail :outside-root uri))
           {:keys [code problem]}
           (source/vet (str (get replacements uri))
                       {:whole? true :clojure? (clojure-file? identity)})]
       (when problem
         (throw (ex-info "invalid replacement"
                         {:resource/error :invalid-replacement
                          :uri uri :problem problem})))
       {:uri uri :resolver :workspace :identity identity
        :base-revision revision :original content
        :replacement code :replacement-revision (content-revision code)
        :metadata {:path path}}))
   observations))

(defn- atomic-write [path content]
  (let [target (java.nio.file.Paths/get (str path) (make-array String 0))
        parent (.getParent target)
        permissions (when (java.nio.file.Files/exists target (make-array java.nio.file.LinkOption 0))
                      (java.nio.file.Files/getPosixFilePermissions
                       target (make-array java.nio.file.LinkOption 0)))
        _ (java.nio.file.Files/createDirectories
           parent (make-array java.nio.file.attribute.FileAttribute 0))
        temporary (java.nio.file.Files/createTempFile
                   parent ".samizdat-resource-" ".tmp"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (when permissions
        (java.nio.file.Files/setPosixFilePermissions temporary permissions))
      (java.nio.file.Files/write temporary (.getBytes ^String (str content) "UTF-8")
                                 (make-array java.nio.file.OpenOption 0))
      (java.nio.file.Files/move
       temporary target
       (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                    java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (java.nio.file.Files/deleteIfExists temporary)))))

(defn publish-workspace
  "Publish a staged workspace member and return its installed revision."
  [resolver {:keys [replacement replacement-revision metadata] :as _member}]
  (let [path (:path metadata)
        publish (get-in resolver [:opts :publish])]
    (if publish
      (or (publish path replacement replacement-revision) replacement-revision)
      (do (atomic-write path replacement)
          replacement-revision))))

(defn delete-workspace
  "Delete a staged workspace resource through the injected effect seam."
  [resolver {:keys [metadata] :as _member}]
  (let [path (:path metadata)
        delete (get-in resolver [:opts :delete])]
    (if delete
      (delete path)
      (java.nio.file.Files/delete (java.nio.file.Paths/get (str path) (make-array String 0))))
    nil))

(defn rename-workspace
  "Move a staged workspace resource and install its checked replacement text."
  [resolver {:keys [metadata replacement replacement-revision] :as member}]
  (let [source (:path metadata)
        target (:target-path metadata)
        target-path (java.nio.file.Paths/get (str target) (make-array String 0))
        rename (get-in resolver [:opts :rename])]
    (java.nio.file.Files/createDirectories
     (.getParent target-path) (make-array java.nio.file.attribute.FileAttribute 0))
    (if rename
      (rename source target)
      (java.nio.file.Files/move
       (java.nio.file.Paths/get (str source) (make-array String 0)) target-path
       (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE])))
    (publish-workspace resolver (assoc member :metadata (assoc metadata :path target)))
    replacement-revision))
