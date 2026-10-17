;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.papercuts
  "The `papercut` tool: report friction with the harness itself, list what
  is open, and close one against the experiment that fixed it
  (karamazov-05z0.5). Closing asks the session for that experiment's
  verdict and refuses anything but an improvement, so a closed papercut is
  a measured fix."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.prompt :as prompt]
            [samizdat.security.flow :as flow]
            [samizdat.session :as session]
            [samizdat.store.papercuts :as papercuts]))

(defn- msg [ctx] (prompt/render "papercut-tool" ctx))

(def ^:private improved
  "The verdicts that count as the fix having helped."
  #{:better :cheaper})

(defmethod base/run-tool "papercut" [{:keys [branch conn run-id] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)
        p (gates/threshold :papercut)]
    (case action
      "report"
      (let [title (some-> (base/arg ctx :title) str str/trim not-empty)]
        (if-not title
          (base/malformed branch (str (base/missing ctx :title) "\n\n" (msg {:usage true})))
          (let [{:keys [id new? reports]} (papercuts/report! conn {:title title
                                                                   :body (base/arg ctx :body)
                                                                   :run-id run-id
                                                                   :branch-id (:id branch)
                                                                   ;; What the reporter had read
                                                                   ;; (security.flow), as a message.
                                                                   :flow (flow/carried ctx)}
                                                             p)]
            (base/ok branch (msg {:reported true :id id :new new? :reports reports})
                     :progress? true))))

      "list"
      (let [rows (papercuts/open conn)]
        ;; Reading them on purpose takes their labels, as reading a
        ;; labelled message does.
        (flow/receive! ctx (map :flow rows) "papercut")
        (base/ok branch (msg {:listed true
                              :open (mapv #(select-keys % [:id :title :body :reports :last_at])
                                          rows)})))

      "close"
      (let [id (some-> (base/arg ctx :id) str str/trim not-empty)
            exp (some-> (base/arg ctx :experiment) str str/trim not-empty)
            resolution (some-> (base/arg ctx :resolution) str str/trim not-empty)
            row (when id (papercuts/by-id conn id))
            v (when exp (session/verdict exp))]
        (cond
          (not (and id resolution))
          (base/malformed branch (str (base/missing ctx :id :resolution) "\n\n" (msg {:usage true})))
          (not= "open" (:status row)) (base/fail branch (msg {:not-open true :id id}))
          (not exp) (base/refusal branch (msg {:needs-experiment true :id id}))
          (not v) (base/fail branch (msg {:no-experiment true :experiment exp}))
          (not (improved (:verdict v)))
          (base/refusal branch (msg {:not-improved true :id id :experiment exp
                                     :verdict (name (:verdict v))}))
          :else
          (do (papercuts/close! conn id {:resolution resolution :experiment exp
                                         :verdict (:verdict v)})
              (base/ok branch (msg {:closed true :id id :experiment exp
                                    :verdict (name (:verdict v))})
                       :progress? true))))

      (base/malformed branch (msg {:usage true})))))
