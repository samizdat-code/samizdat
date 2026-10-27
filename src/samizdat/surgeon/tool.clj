;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.surgeon.tool
  "Shared resource observation for native Surgeon tools."
  (:require [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.resource :as resource]))

(defn observe-files
  "Observe each distinct relative workspace file once, keyed by its request path."
  [ctx requests]
  (reduce
   (fn [observations {:keys [file]}]
     (if (contains? observations file)
       observations
       (assoc observations file
              (resource-tool/authorized-observation
               ctx (str resource/workspace-prefix file)))))
   {} requests))
