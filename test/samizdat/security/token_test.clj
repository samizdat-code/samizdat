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

(ns samizdat.security.token-test
  "The HTTP API's bearer token and the nREPL's gate (karamazov-3vu1.11): on a
  host whose sandbox cannot hide loopback, a process the agent runs can reach
  both, so the API asks for a token kept where that process cannot read, and
  the nREPL — which asks for nothing — does not start unless the operator
  says so."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.api.client :as client]
            [samizdat.api.sse :as sse]
            [samizdat.core :as core]
            [samizdat.security.confine :as confine]
            [samizdat.security.token :as token]
            [samizdat.server :as server]
            [samizdat.system :as system]))

(defmacro with-token-dir [[d] & body]
  `(let [~d (str (fs/create-temp-dir))]
     (with-redefs [token/dir (constantly ~d)]
       (try ~@body (finally (token/revoke-all!))))))

(deftest a-token-is-issued-to-a-file-only-the-owner-reads
  (with-token-dir [d]
    (let [t (token/issue! 4111)]
      (is (re-matches #"[0-9a-f]{64}" t))
      (is (= t (token/read-for 4111)))
      (is (str/starts-with? (token/path-for 4111) d))
      (is (= #{"OWNER_READ" "OWNER_WRITE"}
             (set (map str (fs/posix-file-permissions (token/path-for 4111))))))
      (is (= t (token/current))))
    (testing "and revoked with its server"
      (token/revoke! 4111)
      (is (nil? (token/read-for 4111)))
      (is (not (fs/exists? (token/path-for 4111))))
      (is (nil? (token/current))))))

(deftest the-token-lives-in-the-project-where-the-agent-cannot-read
  ;; Per project, under .samizdat/http — no machine-wide directory — and that
  ;; directory is one the sandbox hides from everything the agent spawns.
  (with-redefs [token/project-root (constantly "/work/proj")]
    (is (= "/work/proj/.samizdat/http" (token/dir)))
    (is (= "/work/proj/.samizdat/http/3985.token" (token/path-for 3985)))
    (is (some #(= (token/dir) %) (confine/hidden "/work/proj")))))

(deftest the-project-is-the-servers-root-else-where-the-client-runs
  (token/set-root! "/srv/a")
  (try
    (is (= "/srv/a" (token/project-root)))
    (finally (token/set-root! nil)))
  (is (not= "/srv/a" (token/project-root))))

(deftest a-client-started-elsewhere-is-handed-the-token
  ;; The GUI runs from its own checkout: SAMIZDAT_TOKEN carries it.
  (with-token-dir [_]
    (with-redefs [token/env (fn [k] (when (= "SAMIZDAT_TOKEN" k) "from-env"))]
      (is (= "from-env" (token/read-for 3985))))))

(defn- call [uri headers]
  (server/handler {:request-method :get :uri uri
                   :headers (merge {"host" "127.0.0.1:3985"} headers)}))

(deftest the-api-asks-for-the-token
  (with-token-dir [_]
    (with-redefs [system/conn (constantly nil) system/config (constantly {})]
      (let [t (token/issue! 3985)]
        (is (= 401 (:status (call "/v1/interventions/kinds" {}))))
        (is (= 401 (:status (call "/v1/interventions/kinds" {"authorization" "Bearer nope"}))))
        (is (= 200 (:status (call "/v1/interventions/kinds" {"authorization" (str "Bearer " t)}))))
        (testing "health says whether the server is up, and nothing it guards"
          (is (not= 401 (:status (call "/health" {})))))))))

(deftest with-no-token-issued-nothing-is-asked
  ;; A handler under test, with no server: there is nothing to reach.
  (with-redefs [system/conn (constantly nil) system/config (constantly {})]
    (token/revoke-all!)
    (is (= 200 (:status (call "/v1/interventions/kinds" {}))))))

(deftest the-client-sends-the-token-for-the-port-it-calls
  (with-token-dir [_]
    (let [t (token/issue! 4222)]
      (is (= {"Authorization" (str "Bearer " t)}
             (client/auth-headers "http://127.0.0.1:4222")))
      (is (= {} (client/auth-headers "http://127.0.0.1:4333")) "no token for that port")
      (is (= {} (client/auth-headers "https://example.com:4222")) "and none to a host that is not this one"))))

(deftest the-nrepl-starts-only-where-loopback-is-hidden
  (is (core/nrepl-allowed? {:os "Mac OS X" :backend :seatbelt :nrepl {}}))
  (is (not (core/nrepl-allowed? {:os "Linux" :backend :bwrap :nrepl {}})))
  (is (not (core/nrepl-allowed? {:os "Linux" :backend :none :nrepl {}})))
  (is (core/nrepl-allowed? {:os "Linux" :backend :bwrap :nrepl {:unconfined :allow}})
      "unless the operator says so")
  (is (not (core/nrepl-allowed? {:os "Linux" :backend :bwrap :nrepl {:unconfined "allow"}}))
      "in exactly those words"))

(deftest the-event-stream-sends-it-too
  (with-token-dir [_]
    (let [t (token/issue! 4555)]
      (is (str/includes? (#'sse/request-text {:host "127.0.0.1" :port 4555 :path "/v1/x"} nil)
                         (str "Authorization: Bearer " t "\r\n")))
      (is (not (str/includes? (#'sse/request-text {:host "127.0.0.1" :port 4556 :path "/v1/x"} nil)
                              "Authorization"))))))

(defn- busy [port]
  (ex-info (str "port " port " is already in use")
           {:errno 48 :errno-name "EADDRINUSE" :port port}))

(deftest a-second-server-takes-the-next-free-port-and-leaves-the-first-ones-token
  ;; `samizdat` in ~/src/humanizer while ~/src/jolt-lang's server held 3985:
  ;; the start died on the bind, and on its way out it deleted 3985.token —
  ;; the RUNNING server's — because the token was written before the bind
  ;; and the failed start revoked everything it had written.
  (with-token-dir [_]
    (spit (token/path-for 3985) "the-other-servers-token")
    (let [tried (atom [])
          bind (fn [port] (swap! tried conj port)
                 (if (#{3985 3986} port) (throw (busy port)) {:server port}))
          r (system/bind-server! bind 3985 5)]
      (is (= [3985 3986 3987] @tried))
      (is (= 3987 (:port r)))
      (is (= {:server 3987} (:server r)))
      (is (= "the-other-servers-token" (token/read-for 3985)) "not ours to touch")
      (is (nil? (token/read-for 3986)))
      (is (= (token/current) (token/read-for 3987)) "the token is written once the server listens")
      (testing "revoking what this process holds leaves the other server's file"
        (token/revoke-all!)
        (is (= "the-other-servers-token" (token/read-for 3985)))
        (is (nil? (token/read-for 3987)))))))

(deftest a-pinned-port-that-is-busy-fails-as-before
  (with-token-dir [_]
    (spit (token/path-for 3985) "the-other-servers-token")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already in use"
                          (system/bind-server! (fn [p] (throw (busy p))) 3985 0)))
    (is (= "the-other-servers-token" (token/read-for 3985)))
    (is (nil? (token/current)) "nothing held for a port it never got")
    (testing "and a scan that runs out says so"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already in use"
                            (system/bind-server! (fn [p] (throw (busy p))) 3985 2))))
    (testing "an error that is not a busy port is not scanned past"
      (let [tried (atom 0)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (system/bind-server! (fn [_] (swap! tried inc) (throw (ex-info "boom" {}))) 3985 5)))
        (is (= 1 @tried))))))

(deftest a-front-end-finds-the-port-its-projects-server-took
  (let [root (str (fs/create-temp-dir))
        env (fn [m] (fn [k] (get m k)))]
    (testing "with no record and nothing set, the default port"
      (is (= "http://127.0.0.1:3985" (client/default-base-url (env {}) root))))
    (system/write-port-file! root 3987)
    (testing "the port the project's server recorded"
      (is (= "http://127.0.0.1:3987" (client/default-base-url (env {}) root))))
    (testing "what the person set wins"
      (is (= "http://127.0.0.1:4000" (client/default-base-url (env {"HARNESS_PORT" "4000"}) root)))
      (is (= "http://h:1" (client/default-base-url (env {"SAMIZDAT_URL" "http://h:1"}) root))))))

(deftest the-token-is-kept-out-of-a-committed-samizdat
  (with-token-dir [d]
    (token/issue! 4112)
    (is (= "*\n" (slurp (str d "/.gitignore"))))))

(deftest a-stopped-server-takes-its-port-record-with-it
  (let [root (str (fs/create-temp-dir))]
    (system/write-port-file! root 3987)
    (system/remove-port-file! root 3986)
    (is (fs/exists? (str root "/.samizdat/http/port")) "not ours: another server's record")
    (system/remove-port-file! root 3987)
    (is (not (fs/exists? (str root "/.samizdat/http/port"))))))
