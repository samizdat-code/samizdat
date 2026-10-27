;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.profiles
  "Named operator profiles with isolated state and explicit shared references."
  (:refer-clojure :exclude [list])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.fs :as fs]
            [samizdat.userspace :as userspace]))

(defonce ^:private selected (atom nil))
(defonce ^:private shared-locks (atom {}))

(defn- canonical [path]
  (.getCanonicalPath (io/file (str path))))

(defn- valid-name? [name]
  (boolean (and (string? name)
                (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" name)
                (not= ".." name))))

(defn- home-for [base name]
  (when-not (valid-name? name)
    (throw (ex-info "profile name must be one safe path segment" {:name name})))
  (let [base-path (canonical base)
        home (canonical (str base-path java.io.File/separator name))]
    (when-not (str/starts-with? home (str base-path java.io.File/separator))
      (throw (ex-info "profile home escapes the profile root" {:name name})))
    home))

(defn- profile-path [home]
  (str home "/profile.edn"))

(defn- read-profile [home]
  (let [file (io/file (profile-path home))]
    (when (.isFile file)
      (edn/read-string (slurp file)))))

(defn create!
  "Create NAME under BASE, retaining only paths for credentials and knowledge."
  [base name {:keys [project-root credentials knowledge]}]
  (let [home (home-for base name)
        file (io/file (profile-path home))]
    (when (.exists file)
      (throw (ex-info "profile already exists" {:name name :home home})))
    (.mkdirs (io/file home "config"))
    (let [profile {:name name
                   :home home
                   :db-path (str home "/samizdat.sqlite3")
                   :workflow-root (str home "/workflow")
                   :project-root (some-> project-root canonical)
                   :credentials (mapv canonical (or credentials []))
                   :knowledge (mapv canonical (or knowledge []))}]
      (spit file (pr-str profile))
      profile)))

(defn list [base]
  (let [root (io/file (canonical base))]
    (vec (sort-by :name
                  (keep #(read-profile (.getPath %))
                        (when (.isDirectory root) (.listFiles root)))))))

(defn current []
  @selected)

(defn clear! []
  (let [previous @selected]
    (reset! selected nil)
    previous))

(defn- copy-role-file [source target relative]
  (let [source-root (canonical source)
        target-root (canonical target)
        from-path (canonical (io/file source-root relative))
        to-path (canonical (io/file target-root relative))
        inside? (fn [root path]
                  (str/starts-with? path (str root java.io.File/separator)))]
    (when-not (and (inside? source-root from-path) (inside? target-root to-path))
      (throw (ex-info "role file is outside the project or profile userspace"
                      {:path relative :source from-path :target to-path})))
    (when (.isFile (io/file from-path))
      (.mkdirs (.getParentFile (io/file to-path)))
      (io/copy (io/file from-path) (io/file to-path)))))

(defn- seed-workflow! [profile project-root]
  (let [workflow-root (or (:workflow-root profile)
                          (str (:home profile) "/workflow"))
        source (some-> project-root (str "/.samizdat"))
        target (str workflow-root "/.samizdat")]
    (when (and source (.isDirectory (io/file source))
               (not (.exists (io/file target "userspace.edn"))))
      (copy-role-file source target "userspace.edn")
      (let [roles (try (edn/read-string (slurp (io/file source "userspace.edn")))
                       (catch Throwable _ {}))]
        (doseq [path (vals (:policies roles))]
          (copy-role-file source target path))
        (doseq [directory ["cells" "manifests" "prompts"]
                file (tree-seq #(.isDirectory %) #(.listFiles %)
                               (io/file source directory))
                :when (.isFile file)]
          (copy-role-file source target
                          (str (.relativize (.toPath (io/file source))
                                            (.toPath file)))))))
    (assoc profile :project-root (some-> project-root canonical)
           :workflow-root workflow-root)))

(defn use!
  "Select NAME and optionally switch its userspace connection and workflow root."
  ([base name] (use! base name {}))
  ([base name opts]
   (if (nil? name)
     (clear!)
     (let [home (home-for base name)
           profile (or (read-profile home)
                       (throw (ex-info "no such profile" {:name name :home home})))
           profile (seed-workflow! profile
                                   (or (:project-root opts) (:project-root profile)))]
       (when (or (contains? opts :conn) (contains? opts :project-root))
         (userspace/switch! (get opts :conn (userspace/conn))
                            (:workflow-root profile)))
       (reset! selected profile)
       profile))))

(defn delete! [base name]
  (when (= name (:name @selected))
    (throw (ex-info "cannot delete the active profile" {:name name})))
  (let [home (home-for base name)]
    (when-not (read-profile home)
      (throw (ex-info "no such profile" {:name name :home home})))
    (fs/delete-tree home)
    true))

(defn lock-for [path]
  (let [path (canonical path)]
    (or (get @shared-locks path)
        (locking shared-locks
          (or (get @shared-locks path)
              (let [lock (Object.)]
                (swap! shared-locks assoc path lock)
                lock))))))

(defmacro with-shared-lock [path & body]
  `(locking (lock-for ~path)
     ~@body))
