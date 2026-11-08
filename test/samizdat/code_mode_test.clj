;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.code-mode-test
  "The code-mode helpers (karamazov-6p51.4), installed in a real project
  image with the eval bridge and called from a branch's session: reads run in
  the image, `sh` and `spit` go through the bridge and come back as data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [jolt.process :as process]
            [samizdat.bridge :as bridge]
            [samizdat.repl.image :as image]
            [samizdat.repl.route :as route]
            [samizdat.userspace :as userspace]))

(defn- helpers [] (userspace/template :prompt "code-mode-helpers"))

(deftest the-helper-source-reads-and-names-its-namespace
  (let [src (helpers)
        forms (read-string (str "[" src "]"))]
    (is (= "samizdat.code" (bridge/helper-ns src)))
    (is (= 'samizdat.code (second (first forms))))
    (is (some #(and (seq? %) (= 'defn (first %)) (= 'refer-into! (second %))) forms)))
  (testing "the install refers it into user and each session"
    (let [src (bridge/install-source #{"shell"} [] 'tool {:timeout-ms 1000 :timeout-message "slow"}
                                     (helpers) #{'b1})
          forms (read-string (str "[" src "]"))]
      (is (some #{'(samizdat.code/refer-into! (quote user))} forms))
      (is (some #{'(samizdat.code/refer-into! (quote b1))} forms)))))

(deftest a-shell-result-carries-its-data-across-the-bridge
  (is (= {:value "out" :data {:exit 0 :out "out" :err ""}}
         (bridge/answer {:category :success :result "out" :data {:exit 0 :out "out" :err ""}})))
  (is (= {:error "boom" :data {:exit 2}}
         (bridge/answer {:category :failure :result "boom" :data {:exit 2}}))))

(defn- fake-tools
  "What answers the image's bridged calls here: shell runs the command, the
  writes write — standing in for the gated tool path, which has its own tests."
  [root calls]
  (fn [{:keys [op args]}]
    (swap! calls conj op)
    (case (str op)
      "shell" (let [r (process/sh {:dir root} "bash" "-c" (:command args))
                    d {:exit (:exit r) :out (:out r) :err (:err r)}]
                (if (zero? (:exit r)) {:value (:out r) :data d} {:error (str "exit " (:exit r)) :data d}))
      "write_file" (do (spit (str root "/" (:path args)) (:content args)) {:value "wrote"})
      {:error (str "no tool " op)})))

(deftest helpers-work-from-a-branch-session-in-a-real-image
  (let [root (str (fs/create-temp-dir))
        _ (spit (str root "/deps.edn") (pr-str {:paths ["src"]}))
        _ (fs/create-dirs (str root "/src/a"))
        _ (spit (str root "/src/a/b.clj") "(ns a.b)\n;; TODO one\n(defn f [] 1)\n")
        im (image/start! {:root root :backend :none
                          :sandbox-spec {:deny-read [] :exec-roots (route/runtime-exec-roots)}})
        calls (atom [])]
    (try
      (is (some? im))
      (when im
        (is (:ok (image/install-bridge! im ["shell" "write_file"] []
                                        {:timeout-ms 20000 :timeout-message "slow"}
                                        (helpers))))
        (let [ev (fn [code]
                   (let [id (str (random-uuid))]
                     (image/eval-in im (bridge/wrap id code) 'branch-1
                                    {:on-request (fake-tools root calls) :bridge-eval id
                                     :poll-ms 20 :call-timeout-ms 20000})))]
          (testing "reads run in the image"
            (is (= "[\"src/a/b.clj\"]" (:value (ev "(glob \"src/**/*.clj\")"))))
            (is (str/includes? (:value (ev "(grep \"TODO\")")) "src/a/b.clj:2:;; TODO one"))
            (is (= "\"(ns a.b)\"" (:value (ev "(head \"src/a/b.clj\" 1)")))))
          (testing "sh returns stdout, throws with the exit and streams"
            (is (= "\"hi there\\n\"" (:value (ev "(sh \"echo\" \"hi there\")"))))
            (is (= "1" (:value (ev "(try (sh \"false\") (catch Exception e (:exit (ex-data e))))"))))
            (is (= "{:exit 3, :out \"\", :err \"\"}" (:value (ev "(sh* \"sh\" \"-c\" \"exit 3\")")))))
          (testing "spit writes through the bridge; cat is still the transducer"
            (ev "(spit \"out.txt\" \"abc\")")
            (is (= "abc" (slurp (str root "/out.txt"))))
            (is (= "[1 2 3]" (:value (ev "(into [] cat [[1 2] [3]])")))))
          (is (= #{"shell" "write_file"} (set @calls)) "programs and writes took the bridge")))
      (finally (when im (image/stop! im))))))
