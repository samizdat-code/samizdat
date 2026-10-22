;; samizdat - a self-hosting agentic harness
;; License: GPL-3.0-or-later

(ns samizdat.gui.atlas
  "The code atlas in the GUI (RFC-016): the project's call graph to look at
  and walk, after Code Bubbles and the Light Table demo.

  Three columns. On the left, what to look at: search, the namespaces, the
  flows (each entry point's paths to an end), the units (functions grouped
  by the flows they serve), the workflows (manifests as state graphs, with
  the selected run's steps over them) and the history (snapshots of the
  graph, diffed against now). In the middle, the canvas: the graph at a
  zoom level, laid out so calls run left to right; clicking a group opens
  it, clicking a function focuses it. On the right, the focused function:
  what calls it above, its source, and what it calls stacked below, each
  stacked card a click away from being the focus.

  Strictly an HTTP client, like the rest of the GUI: everything arrives
  through samizdat.api.client's atlas calls. The panels are pure functions
  of the state value (`*-view`), so the suite checks them as data; effects
  run in futures and land in `st`. Sizes, colours and the starting zoom are
  gui.edn :atlas, a layered settings file the agent can rewrite."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [glimmer.ratom :as r]
            [samizdat.api.client :as client]
            [samizdat.gui.atlas-scene :as scene]
            [samizdat.gui.atlas-view :as v]
            [samizdat.gui.glpane :as glpane]
            [samizdat.layers :as layers]))

;; --- settings ----------------------------------------------------------------

(def ^:private shipped (delay (some-> (io/resource "gui.edn") slurp)))

(defn load-settings
  "gui.edn :atlas through its layers, the project's served one among them."
  [base]
  (let [served (:layout (:body (client/layout base "gui")))]
    (:atlas (:value (layers/resolve "gui" {:root (System/getProperty "user.dir")
                                           :global-dir (layers/global-dir)
                                           :served served :shipped @shipped})))))

;; --- state -------------------------------------------------------------------

(defonce st (r/atom (v/initial-state nil)))

(defonce ^:private base-url (atom nil))

(defn- set-error! [r] (swap! st assoc :error (when-not (:ok r) (:error r))) r)

;; --- the canvas, as data -----------------------------------------------------------

(defn- canvas-source [] (v/source-map @st))

(defn- render! [] (glpane/request-render!))

;; --- effects -----------------------------------------------------------------

(declare select!)

(defn- in-bg [f] (future (try (f) (catch Throwable e (swap! st assoc :error (ex-message e))))))

(defn refresh-view!
  "Fetch the code view for the current lens, open groups and marks, and lay
  it out."
  []
  (in-bg
   #(let [{:keys [lens expanded mark settings]} @st
          r (set-error! (client/atlas-view @base-url lens expanded mark))]
      (when (:ok r)
        (let [v (update (:body r) :nodes
                        (fn [ns] (mapv (fn [n] (update n :kind keyword)) ns)))]
          (swap! st assoc :view v :marked (set (:marked v))
                 :positions (if (= :code (:canvas @st)) (v/positions-for v settings) (:positions @st)))
          (render!))))))

(defn- open-depth
  "The groups to open so the view starts `depth` levels down: the root
  groups' ids, which the first view returns."
  [nodes depth]
  (if (pos? (or depth 0)) (set (keep #(when (:expandable? %) (:id %)) nodes)) #{}))

(defn start!
  "Bring the atlas up against the server at `base`: settings, the summary,
  and the first view."
  [base]
  (reset! base-url base)
  (let [settings (load-settings base)]
    (reset! st (v/initial-state settings))
    (in-bg
     #(let [first-view (client/atlas-view base (:lens @st) #{} [])]
        (swap! st assoc :summary (:body (client/atlas-summary base))
               :expanded (open-depth (get-in first-view [:body :nodes]) (:open-depth settings)))
        (refresh-view!)))))

(defn- show-code! []
  (swap! st assoc :canvas :code)
  (glpane/reset-pan!)
  (refresh-view!))

(defn toggle-group!
  "Open `id` when it is closed, close it (and what is open under it) when
  open."
  [id]
  (swap! st update :expanded #(if (% id) (disj % id) (conj % id)))
  (refresh-view!))

(defn- fetch-stack!
  "The focused function's callees' cards, for the stack beside it."
  [card]
  (let [n (or (get-in @st [:settings :neighbours-shown]) (count (:callees card)))]
    (doseq [{:keys [id]} (take n (:callees card))]
      (let [r (client/atlas-card @base-url id)]
        (when (:ok r) (swap! st assoc-in [:stack id] (:body r)))))))

(defn select!
  "Focus definition `id`: its card, and its callees' cards for the stack."
  [id]
  (swap! st assoc :selected id :stack {})
  (render!)
  (in-bg #(let [r (set-error! (client/atlas-card @base-url id))]
            (when (:ok r)
              (swap! st assoc :card (:body r))
              (fetch-stack! (:body r))))))

(defn on-pick
  "A click on the canvas: a group opens or closes, a definition is focused,
  a workflow state focuses the cell it runs."
  [id]
  (when id
    (let [s @st]
      (if (= :workflow (:canvas s))
        (when-let [cell (:cell (first (filter #(= id (:id %)) (get-in s [:wf-view :nodes]))))]
          (select! cell))
        (let [n (first (filter #(= id (:id %)) (get-in s [:view :nodes])))]
          (if (:expandable? n) (toggle-group! id) (select! id)))))))

(defn on-hover [id] (swap! st assoc :hover id))

(defn zoom! [factor]
  (swap! st update :zoom * factor)
  (render!))

(defn fit!
  "Zoom so the whole view fits the pane."
  []
  (let [[w h scale] (glpane/size) {:keys [positions settings]} @st]
    (when (seq positions)
      (swap! st assoc :zoom (/ (scene/fit-zoom positions w h (:frame-pad settings)) scale))
      (glpane/reset-pan!))))

(defn search! [q]
  (swap! st assoc :search q)
  (if (str/blank? q)
    (swap! st assoc :results [])
    (in-bg #(let [r (client/atlas-search @base-url q)]
              (when (:ok r) (swap! st assoc :results (:body r)))))))

(defn- reveal!
  "Open every group above `id` so the canvas shows it, and focus it."
  [id]
  (in-bg #(let [r (client/atlas-card @base-url id)]
            (when (:ok r)
              (swap! st update :expanded into (get-in r [:body :ancestors]))
              (show-code!)
              (select! id)))))

(defn tab! [tab]
  (swap! st assoc :tab tab)
  (let [base @base-url]
    (case tab
      :flows (when-not (:flows @st)
               (in-bg #(swap! st assoc :flows (:body (set-error! (client/atlas-flows base))))))
      :units (when-not (:units @st)
               (in-bg #(swap! st assoc :units (:body (set-error! (client/atlas-units base))))))
      :workflows (when-not (:workflows @st)
                   (in-bg #(swap! st assoc :workflows (:body (set-error! (client/atlas-workflows base))))))
      :history (in-bg #(swap! st assoc :snapshots (:body (set-error! (client/atlas-snapshots base)))))
      nil)))

(defn open-flow!
  "Show flow `entry`: its paths in the sidebar, its slice marked on the
  canvas."
  [entry]
  (in-bg #(let [r (set-error! (client/atlas-flow @base-url entry))]
            (when (:ok r)
              (swap! st assoc :flow (:body r) :mark (get-in r [:body :slice]))
              (show-code!)))))

(defn clear-marks! []
  (swap! st assoc :mark [] :flow nil :diff nil :diff-marks {})
  (refresh-view!))

(defn open-unit!
  "Look at the code through units, with unit `id` open."
  [id]
  (swap! st assoc :lens :units :expanded #{id})
  (show-code!))

(defn set-lens! [lens]
  (swap! st assoc :lens lens :expanded #{})
  (show-code!))

(defn open-workflow!
  "Put workflow `name`'s state graph on the canvas, run `run-id`'s steps
  over it when there is one."
  [name run-id]
  (in-bg #(let [r (set-error! (client/atlas-workflow @base-url name run-id))]
            (when (:ok r)
              (let [v (v/wf-view (:body r))]
                (swap! st assoc :canvas :workflow :workflow name :wf-view v
                       :marked (:visited v)
                       :positions (v/positions-for v (:settings @st)))
                (glpane/reset-pan!)
                (render!))))))

(defn open-diff!
  "What changed since snapshot `id`: listed, and marked on the canvas."
  [id]
  (in-bg #(let [r (set-error! (client/atlas-diff @base-url id nil))]
            (when (:ok r)
              (let [{:keys [added changed]} (:body r)]
                (swap! st assoc :diff (assoc (:body r) :since id)
                       :mark (vec (concat added changed))
                       :diff-marks (merge (zipmap changed (repeat :changed))
                                          (zipmap added (repeat :added))))
                (show-code!))))))

;; --- the component -----------------------------------------------------------------

(defn- canvas
  "Deref-free, so the :gl-area mounts once and repaints through
  request-render!, the way the run view's pane does."
  []
  [:frame {:label "atlas" :hexpand true :vexpand true}
   [glpane/pane canvas-source on-pick on-hover]])

(def ^:private actions
  {:reveal #(reveal! %) :toggle #(toggle-group! %) :select #(select! %)
   :open-flow #(open-flow! %) :clear #(clear-marks!) :open-unit #(open-unit! %)
   :show-code #(show-code!) :open-workflow #(open-workflow! %1 %2) :open-diff #(open-diff! %)
   :search #(search! %) :tab #(tab! %) :set-lens #(set-lens! %) :zoom #(zoom! %)
   :fit #(fit!)
   :close-all #(do (swap! st assoc :expanded #{}) (show-code!))})

(defn- sidebar [run-id] (v/sidebar-view actions @st run-id))
(defn- toolbar [] (v/toolbar-view actions @st))
(defn- cards [] (v/card-view actions @st))

(defn root
  "The atlas body; `run-id` is the run whose steps the workflows tab lays
  over a manifest."
  [run-id]
  [:hbox {:spacing 8 :vexpand true}
   [sidebar run-id]
   [:vbox {:spacing 6 :hexpand true :vexpand true}
    [toolbar]
    [canvas]]
   [cards]])
