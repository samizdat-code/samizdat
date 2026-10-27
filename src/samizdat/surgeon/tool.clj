;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.surgeon.tool
  "Shared resource observation for native Surgeon tools."
  (:require [jolt.fs :as fs]
            [samizdat.agent.files :as files]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.resource :as resource]))

(defn canonical-file
  "The canonical project-relative identity of a confined workspace path."
  [ctx file]
  (let [root (str (fs/canonicalize (:root ctx)))]
    (when-let [resolved (files/resolve-under-root root file)]
      (str (fs/relativize root resolved)))))

(defn observe-files
  "Observe each distinct canonical workspace file once, keyed by relative path."
  [ctx requests]
  (reduce
   (fn [observations {:keys [file]}]
     (let [canonical (canonical-file ctx file)]
       (when-not canonical
         (throw (ex-info "outside-root"
                         {:resource/error :outside-root
                          :uri (str resource/workspace-prefix file)})))
       (if (contains? observations canonical)
         observations
         (assoc observations canonical
                (resource-tool/authorized-observation
                 ctx (str resource/workspace-prefix canonical))))))
   {} requests))
