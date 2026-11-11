;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.repl-restart-test
  "A project REPL in a bad state can be restarted: by itself when its process has died,
  and on request when it is alive but wedged. Either way the model is told its
  definitions are gone."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.repl.image :as image]
            [samizdat.repl.route :as route]))

(defn- with-fake-images [f]
  (let [started (atom 0) alive (atom true)]
    (with-redefs-fn {#'image/start! (fn [_] (swap! started inc) {:id @started})
                     #'image/stop! (fn [_] nil)
                     #'image/alive? (fn [_] @alive)
                     #'image/eval-in (fn [& _] {:value "nil"})
                     #'route/eval-project (fn [im & _] {:ok true :value (str "image " (:id im))})}
      #(f started alive))))

(def ^:private ctx {:root "/tmp/samizdat-restart-root" :role :implementer})

(deftest a-dead-image-is-started-again
  (with-fake-images
    (fn [started alive]
      (route/release! (:root ctx))
      (is (= "image 1" (:value (route/eval-for ctx "(+ 1 2)" nil 1000))))
      (is (not (:restarted (route/eval-for ctx "(+ 1 2)" nil 1000))) "a live image is kept")
      (reset! alive false)
      (let [r (route/eval-for ctx "(+ 1 2)" nil 1000)]
        (is (= 2 @started))
        (is (= "image 2" (:value r)))
        (is (= :stopped (:restarted r)) "and it says so"))
      (route/release! (:root ctx)))))

(deftest a-wedged-image-is-restarted-on-request
  (with-fake-images
    (fn [started _]
      (route/release! (:root ctx))
      (route/eval-for ctx "(def x 1)" nil 1000)
      (let [r (route/eval-for (assoc ctx :restart? true) "(+ 1 2)" nil 1000)]
        (is (= 2 @started))
        (is (= :asked (:restarted r))))
      (route/release! (:root ctx)))))
