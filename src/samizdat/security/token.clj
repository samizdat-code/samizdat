;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.token
  "THE HTTP API'S BEARER TOKEN (karamazov-3vu1.11).

  On Linux the sandbox cannot keep a shell command or the eval image off
  loopback — bwrap shares the host network, and seccomp cannot tell one
  connect from another — so what stands between a process the agent runs and
  the harness's API is something that process cannot read. A server issues a
  fresh token when it starts and writes it, owner-only, under its project's
  .samizdat/http/<port>.token. That directory is HIDDEN from everything the
  agent spawns (samizdat.security.confine/hidden: denied after the project's
  re-allow in seatbelt, a tmpfs over it in bwrap) and refused by the file
  tools (samizdat.agent.files/hidden?). There is no machine-wide token
  directory: each project's server keeps its own.

  samizdat.api.client reads it from the project it was started in
  (HARNESS_ROOT, else the working directory); a client started elsewhere is
  handed it in SAMIZDAT_TOKEN. A person's curl, from the project:

      curl -H \"Authorization: Bearer $(cat .samizdat/http/3985.token)\" …

  The server drops it when it stops."
  (:require [clojure.string :as str]
            [jolt.fs :as fs]))

(defn env [k] (System/getenv k))

(defonce ^:private root
  ;; The project of this process's server, once it starts.
  (atom nil))

(defn set-root!
  "The project whose .samizdat holds this process's tokens."
  [r]
  (reset! root (some-> r str)))

(defn project-root
  "This process's server's project, else HARNESS_ROOT, else the working
  directory — where a front end started in a project finds its server."
  []
  (or @root (env "HARNESS_ROOT") (System/getProperty "user.dir")))

(defn dir
  "Where the tokens are written: the project's .samizdat/http."
  []
  (str (project-root) "/.samizdat/http"))

(defn path-for [port] (str (dir) "/" port ".token"))

(defonce ^:private issued
  ;; port -> token, for this process's servers.
  (atom {}))

(defn- fresh []
  (let [b (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) b)
    (apply str (map #(format "%02x" (bit-and % 0xff)) b))))

(defonce ^:private published
  ;; The ports whose token FILE this process wrote. Only those are its to
  ;; delete: a start that failed to bind deleted the running server's
  ;; 3985.token on its way out, and every new client of that server was
  ;; refused from then on.
  (atom #{}))

(defn hold!
  "A new token for a server about to listen on `port`, held in memory only:
  the API asks for it from the first request. Its file is written by
  `publish!` once the port is really ours. Returns it."
  [port]
  (let [t (fresh)]
    (swap! issued assoc port t)
    t))

(defn publish!
  "Write the token held for `port` to its file, owner-only. Returns it."
  [port]
  (when-let [t (get @issued port)]
    (let [p (path-for port)]
      (fs/create-dirs (dir))
      ;; A project may commit its .samizdat; never the token.
      (let [gi (str (dir) "/.gitignore")]
        (when-not (fs/exists? gi) (spit gi "*\n")))
      ;; Created empty and narrowed BEFORE the token is written, so there is no
      ;; moment it sits in a file others may read. Appended, not spat over: a
      ;; plain spit replaces the file, and the replacement has the default mode.
      (fs/delete-if-exists p)
      (spit p "")
      (fs/set-posix-file-permissions p "rw-------")
      (spit p t :append true)
      (swap! published conj port)
      t)))

(defn issue!
  "A new token for the server on `port`, written owner-only. Returns it."
  [port]
  (hold! port)
  (publish! port))

(defn release!
  "Forget the token held for `port` without touching any file: the port was
  someone else's."
  [port]
  (swap! issued dissoc port)
  nil)

(defn read-for
  "The token a server on `port` issued: SAMIZDAT_TOKEN when a client started
  outside the project is handed it, else read from its file, else nil."
  [port]
  (or (some-> (env "SAMIZDAT_TOKEN") str/trim not-empty)
      (some-> (try (slurp (path-for port)) (catch Throwable _ nil)) str/trim not-empty)))

(defn current
  "The token this process's server holds, or nil when it has none."
  []
  (first (vals @issued)))

(defn revoke!
  "Drop the token for `port`, and its file when this process wrote it."
  [port]
  (swap! issued dissoc port)
  (when (contains? @published port)
    (swap! published disj port)
    (try (fs/delete-if-exists (path-for port)) (catch Throwable _ nil)))
  nil)

(defn revoke-all! []
  (doseq [p (keys @issued)] (revoke! p)))

(defn valid?
  "Whether `header` (an Authorization value) carries the token this process
  issued. Compared in constant time."
  [header]
  (let [t (current)
        given (some->> (str header) (re-matches #"(?i)Bearer\s+(\S+)") second)]
    (boolean
     (and t given (= (count t) (count given))
          (zero? (reduce bit-or 0 (map #(bit-xor (int %1) (int %2)) t given)))))))
