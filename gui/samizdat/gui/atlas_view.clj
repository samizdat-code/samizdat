;; samizdat - a self-hosting agentic harness
;; License: GPL-3.0-or-later

(ns samizdat.gui.atlas-view
  "The code atlas's panels as data (RFC-016): each a pure function of the
  atlas state value and a map of actions (`act`, the effects in
  samizdat.gui.atlas), returning glimmer hiccup, so the headless suite
  checks them without GTK. The canvas is here too as `source-map`: what the
  GL pane draws and how it answers a click, for a state value."
  (:require [clojure.string :as str]
            [samizdat.atlas.layout :as layout]
            [samizdat.gui.atlas-scene :as scene]
            [samizdat.gui.mathtext :as mt]))

;; --- state -------------------------------------------------------------------

(defn initial-state [settings]
  {:settings settings
   :tab :namespaces
   :lens (or (:lens settings) :namespaces)
   :expanded #{}
   :canvas :code            ; :code or :workflow
   :view nil :positions {}
   :zoom 1.0
   :selected nil :hover nil
   :card nil :stack {}
   :mark [] :marked #{} :diff-marks {}
   :search "" :results []
   :summary nil :flows nil :flow nil :units nil
   :workflows nil :workflow nil :wf-view nil
   :snapshots nil :diff nil
   :error nil})

;; --- the canvas, as data ---------------------------------------------------------

(defn positions-for
  "Layered positions for `view` under `settings`."
  [{:keys [nodes edges]} settings]
  (:positions (layout/layered {:nodes (mapv :id nodes) :edges edges}
                              (select-keys settings [:layer-gap :node-gap :sweeps]))))

(defn source-map
  "What the GL pane draws for state value `s`, or nil: the code view, or the
  workflow's state graph when that is on the canvas."
  [s]
  (let [{:keys [settings positions zoom selected marked diff-marks]} s
        view (if (= :workflow (:canvas s)) (:wf-view s) (:view s))]
    (when (and view (seq positions) settings)
      ;; The device scale multiplies the layout as well as the boxes, so a
      ;; HiDPI pane keeps the spacing the boxes were sized against.
      (let [frame (fn [{:keys [w h pan scale]}]
                    (scene/frame positions w h (* zoom (or scale 1.0)) pan (:frame-pad settings)))]
        {:draw (fn [{:keys [hover scale] :as env}]
                 (scene/verts view positions (frame env)
                              {:selected selected :hover hover :marked (set marked) :diff diff-marks}
                              settings (or scale 1.0)))
         :pick (fn [{:keys [scale] :as env} x y]
                 (scene/pick view positions (frame env) settings (or scale 1.0) [x y]))}))))

(defn wf-view
  "A workflow's state graph as a canvas view, its overlay's visits marked."
  [{:keys [nodes edges overlay]}]
  (let [visits (:visits overlay)]
    {:nodes (vec (for [n nodes :when (not= "manifest" (name (:kind n)))]
                   (-> n
                       (update :kind keyword)
                       (update :label #(if-let [v (get visits (keyword (:id n)) (get visits (:id n)))]
                                         (str % " ×" v)
                                         %)))))
     :edges (vec (for [{:keys [from to]} edges] {:from from :to to}))
     :visited (set (map name (keys visits)))
     :current (set (vals (:current overlay)))}))


;; --- views (pure) ----------------------------------------------------------------

(defn- button [label f & [tip]]
  [:button (cond-> {:label label :on-click f} tip (assoc :tooltip tip))])

(defn- short-id [id] (str/replace (str id) #"^.*/" ""))

(defn- prefix [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(def tabs [[:namespaces "code"] [:flows "flows"] [:units "units"]
           [:workflows "workflows"] [:history "history"]])

(defn- namespaces-list [act s]
  (let [{:keys [view results search]} s]
    (if (seq search)
      (into [:vbox {:spacing 2}]
            (for [{:keys [id kind]} (take 60 results)]
              (button (str id) #((:reveal act) id) (name (str kind)))))
      (into [:vbox {:spacing 2}]
            (for [{:keys [id label expandable? size]} (:nodes view)]
              (button (str (if expandable? "▸ " "  ") label (when expandable? (str "  " size)))
                      (if expandable? #((:toggle act) id) #((:select act) id))))))))

(defn- trie-rows
  "The flow's trie as indented rows, deepest last."
  [act labels node depth]
  (when node
    (cons [:hbox {:spacing 4}
           [:label {:label (str (apply str (repeat depth "  ")) (when (pos? depth) "└ "))}]
           (button (str (get labels (keyword (:id node)) (get labels (:id node) (:id node)))
                        (when (< 1 (:count node)) (str "  ×" (:count node))))
                   #((:reveal act) (:id node)))]
          (mapcat #(trie-rows act labels % (inc depth)) (:children node)))))

(defn- flows-list [act s]
  (let [{:keys [flows flow]} s]
    (if flow
      (into [:vbox {:spacing 2}
             (button "◂ all flows" (:clear act))
             [:label {:markup (str (mt/heading (:entry flow)) "\n"
                                   (mt/dim (str (:path-count flow) " path(s)"
                                                (when (:truncated? flow)
                                                  (str ", " (count (:paths flow)) " listed")))))
                      :xalign 0.0 :wrap true}]]
            (trie-rows act (:labels flow) (:trie flow) 0))
      (into [:vbox {:spacing 2}]
            (for [{:keys [entry label path-count slice]} flows]
              (button (str label "  " path-count "↯ " slice "ƒ") #((:open-flow act) entry)
                      (str entry ": " path-count " paths, " slice " functions")))))))

(defn- units-list [act s]
  (into [:vbox {:spacing 2}]
        (for [{:keys [id label members flows]} (:units s)]
          (button (str (scene/clip-label label 30) "  " (count members) "ƒ") #((:open-unit act) id)
                  (str (count flows) " flows: " (str/join ", " (take 4 (map short-id flows))))))))

(defn- workflows-list [act s run-id]
  (into [:vbox {:spacing 2}
         [:label {:markup (mt/dim (if run-id (str "steps of run " (prefix run-id 8)) "no run selected"))
                  :xalign 0.0}]]
        (concat
         (when (= :workflow (:canvas s)) [(button "◂ back to the code" (:show-code act))])
         (for [{nm :name :keys [description]} (:workflows s)]
           (button nm #((:open-workflow act) nm run-id) (scene/clip-label description 200))))))

(defn- history-list [act s]
  (let [{:keys [snapshots diff]} s]
    (into [:vbox {:spacing 2}]
          (concat
           (when diff
             (concat [(button "◂ clear" (:clear act))
                      [:label {:markup (mt/dim (str (count (:added diff)) " added, "
                                                    (count (:changed diff)) " changed, "
                                                    (count (:removed diff)) " removed"))
                               :xalign 0.0}]]
                     (for [id (concat (:added diff) (:changed diff))]
                       (button (str (if (some #{id} (:added diff)) "+ " "~ ") id) #((:reveal act) id)))
                     (for [id (:removed diff)] [:label {:label (str "- " id) :xalign 0.0}])))
           (when-not diff
             (if (seq snapshots)
               (for [{:keys [id run-id created-at]} snapshots]
                 (button (str (prefix created-at 16) (when run-id (str "  " (prefix run-id 8))))
                         #((:open-diff act) id) "what changed since this snapshot"))
               [[:label {:markup (mt/dim "no snapshots yet: one is stored at each run's end")
                         :xalign 0.0 :wrap true}]]))))))

(defn sidebar-view
  "The left column for state value `s`."
  [act s run-id]
  [:vbox {:spacing 6 :width-request 300}
   [:entry {:text (:search s) :placeholder "find a definition" :on-change (:search act)}]
   (into [:hbox {:spacing 2}]
         (for [[k label] tabs]
           [:button {:label (if (= k (:tab s)) (str "•" label) label) :on-click #((:tab act) k)}]))
   (when-let [e (:error s)] [:label {:markup (mt/dim e) :wrap true :xalign 0.0}])
   [:scrolled {:vexpand true}
    (case (:tab s)
      :flows (flows-list act s)
      :units (units-list act s)
      :workflows (workflows-list act s run-id)
      :history (history-list act s)
      (namespaces-list act s))]])

(defn toolbar-view [act s]
  (let [{:keys [summary lens zoom settings canvas]} s
        step (or (:zoom-step settings) 1.0)]
    [:hbox {:spacing 6}
     [:button {:label (if (= :units lens) "lens: units" "lens: namespaces")
               :tooltip "group by namespace, or by the flows functions serve"
               :on-click #((:set-lens act) (if (= :units lens) :namespaces :units))}]
     (button "−" #((:zoom act) (/ 1.0 step)) "zoom out")
     (button "+" #((:zoom act) step) "zoom in")
     (button "fit" (:fit act) "fit the view")
     (button "close all" (:close-all act))
     [:label {:markup (mt/dim (str (when (= :workflow canvas) (str "workflow " (:workflow s) " · "))
                                   (when summary
                                     (str (:nodes summary) " nodes · " (:edges summary) " calls · "
                                          (:entries summary) " entries"))
                                   (when (seq (:errors summary))
                                     (str " · " (count (:errors summary)) " unread"))
                                   (str " · zoom " (format "%.2f" (double zoom)))))
              :xalign 0.0 :hexpand true}]]))

(defn- hex [[r g b]]
  (apply format "#%02x%02x%02x" (map #(int (* 255 (max 0.0 (min 1.0 (double %))))) [r g b])))

(defn markup-source
  "`source` as monospace Pango markup, each whole-word use of a name in
  `names` coloured `color`: the calls a reader's eye looks for."
  [source names color]
  (let [names (set (remove str/blank? names))
        tokens (re-seq #"[^\s()\[\]{}\"';@^`~,]+|[\s\S]" (str source))]
    (str "<tt>"
         (apply str (for [t tokens]
                      (if (names t)
                        (str "<span foreground=\"" color "\"><b>" (mt/escape t) "</b></span>")
                        (mt/escape t))))
         "</tt>")))

(defn- call-names
  "How `card`'s callees are written in its source: the short name and any
  alias-qualified form ending in it."
  [card]
  (let [src (str (:source card))]
    (set (mapcat (fn [{:keys [id label]}]
                   (let [n (str/replace (str label) #"^.* " "")]
                     (cons n (re-seq (re-pattern (str "[\\w.-]+/" (str/replace n #"[.*+?^$()\[\]{}|\\]" "\\\\$0")))
                                     src))))
                 (:callees card)))))

(defn- lines [s n]
  (str/join "\n" (take n (str/split-lines (str s)))))

(defn card-view
  "The right column: callers, the focused card, the callees' stack."
  [act s]
  (let [{:keys [card stack settings selected]} s
        mark (hex (get-in settings [:colors :mark] [1.0 0.6 0.2]))
        shown (or (:neighbours-shown settings) 12)]
    [:vbox {:spacing 6 :width-request 520}
     (if-not card
       [:label {:markup (mt/dim (if selected "loading…" "click a function, or find one, to focus it"))
                :xalign 0.0 :wrap true}]
       [:scrolled {:vexpand true :scroll-top (str selected)}
        (into
         [:vbox {:spacing 6}
          (into [:vbox {:spacing 2}
                 [:label {:markup (mt/dim (str "called by " (count (:callers card))))
                          :xalign 0.0}]]
                (for [{:keys [id]} (take shown (:callers card))]
                  (button (str "← " id) #((:select act) id))))
          [:frame {:label (str (get-in card [:node :id]))}
           [:vbox {:spacing 4 :margin 6}
            [:label {:markup (str (mt/dim (str (name (str (get-in card [:node :kind]))) " · "
                                               (get-in card [:node :file]) ":" (get-in card [:node :line])
                                               " · serves " (count (:flows card)) " flow(s)"))
                                  (when-let [d (get-in card [:node :doc])] (str "\n" (mt/escape d))))
                     :xalign 0.0 :wrap true}]
            [:label {:markup (markup-source (lines (:source card) (or (:card-source-lines settings) 60))
                                            (call-names card) mark)
                     :xalign 0.0}]
            (into [:hbox {:spacing 4}]
                  (for [e (take 4 (:flows card))]
                    (button (str "flow " (short-id e)) #((:open-flow act) e))))]]
          [:label {:markup (mt/dim (str "calls " (count (:callees card)))) :xalign 0.0}]]
         (for [{:keys [id]} (take shown (:callees card))
               :let [c (get stack id)]]
           [:frame {:label (str "→ " id)}
            [:vbox {:spacing 2 :margin 4}
             [:label {:markup (if c
                                (markup-source (lines (:source c) (or (:stack-source-lines settings) 6))
                                               #{} mark)
                                (mt/dim "…"))
                      :xalign 0.0}]
             (button "focus" #((:select act) id))]]))])]))

