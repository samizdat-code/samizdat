;; samizdat - a claim-first verification harness
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

(ns samizdat.gui.glpane
  "The solution-space pane: the scene graph drawn in a :gl-area.

  A deliberately 2D pipeline — one program, one VBO of interleaved
  [x y r g b], pixel coordinates mapped to NDC in the vertex shader — so the
  transform that draws a node is the same pure `graph/fit` affine that
  answers a click, and picking is exact instead of an unprojection. Edges
  draw as GL_LINES, nodes as quads sized by confirmed-artifact count and
  colored by status; the selected node gets a white backing quad. Geometry
  is rebuilt and re-uploaded per render, which at beam scale (tens of
  nodes) costs nothing.

  All GL state lives here keyed by nothing — one pane per process. The
  pane re-renders when `request-render!` is called (the poll loop and the
  picker call it), coalesced by GTK to one render per frame-clock tick."
  (:require [glimmer-gl.gl :as gl]
            [glimmer-gl.gtk :as glx]
            [glimmer-gtk.ffi :as gffi]
            [glimmer.core :as ui]
            [jolt.ffi :as ffi]
            [samizdat.gui.graph :as graph]
            [samizdat.gui.input :as input]
            [samizdat.gui.style :as style]))

(def ^:private vs-src
  (str "#version 150\n"
       "in vec2 pos; in vec3 col; out vec3 vcol; uniform vec2 vp;\n"
       "void main() { vcol = col;\n"
       "  gl_Position = vec4(2.0*pos.x/vp.x - 1.0, 1.0 - 2.0*pos.y/vp.y, 0.0, 1.0); }"))

(def ^:private fs-src
  (str "#version 150\n"
       "in vec3 vcol; out vec4 frag;\n"
       "void main() { frag = vec4(vcol, 1.0); }"))

(defonce ^:private st (atom {:w 1 :h 1 :scale 1.0 :pan [0.0 0.0]}))

(def pick-radius 26.0)

(defn clear-hover!
  "Drop the hover mark. connect-to! calls this on a run switch so the status
  line stops describing a node from the run the UI just left
  (code-review-2026-08 #5)."
  []
  (swap! st dissoc :hover))

;; --- HiDPI ---------------------------------------------------------------
;; GtkGLArea's "resize" reports the FRAMEBUFFER size, in device pixels, while
;; a GestureClick reports LOGICAL widget coordinates. On a 2x display those
;; differ by exactly the scale factor, so a graph drawn correctly in device
;; pixels is unclickable: every hit test looks half a screen away from where
;; the node actually is, `nearest` returns nil, and the pane feels dead while
;; looking perfect. Everything here stays in device pixels (what GL draws in)
;; and pointer input is converted on the way in. The factor is derived rather
;; than bound: gtk_widget_get_width is logical, the resize width is device.

(defn- device-scale [area device-w]
  (let [logical (try (glx/widget-width area) (catch Throwable _ 0))]
    (if (pos? logical) (/ (double device-w) (double logical)) 1.0)))

(defn- ->device [[x y]]
  (let [s (:scale @st 1.0)]
    [(* x s) (* y s)]))

;; --- geometry (pure) ---------------------------------------------------------

(defn- line-verts [[x0 y0] [x1 y1] [cr cg cb]]
  [x0 y0 cr cg cb, x1 y1 cr cg cb])

(defn scene-verts
  "{:lines [floats] :tris [floats]} for the whole scene, in pixel space.
  Nodes draw last so an edge never crosses a node body."
  [g positions t selected]
  (let [px (fn [id] (graph/world->px t (positions id)))]
    {:lines (vec (mapcat (fn [[from to]]
                           (when (and (positions from) (positions to))
                             (line-verts (px from) (px to) style/edge-color)))
                         (graph/edges g)))
     :tris (let [live (graph/working g)]
             (vec (mapcat (fn [id]
                            (let [[cx cy] (px id)]
                              (style/node-verts cx cy (get-in g [:nodes id])
                                                (= id selected)
                                                (= id (:hover @st))
                                                (contains? live id))))
                          (keys positions))))}))

;; --- GL lifecycle ------------------------------------------------------------

(declare unmount!)

;; A foreign-callable that gets collected leaves GTK holding a dangling function
;; pointer, so every one we connect is retained for the life of the process —
;; the same reason glimmer-gtk.widget keeps its own. Also the set of widgets already
;; wired, so a second realize of the same widget does not stack handlers.
(defonce ^:private unrealize-callables (atom []))
(defonce ^:private unrealize-wired (atom #{}))

(defn- watch-unrealize!
  "Clear the cached pointer when GTK tears this widget down.

  This is the fix for the whole class. `:area` is a raw GTK pointer captured in
  on-realize, and nothing in glimmer reports that a widget went away — it has no
  unrealize/destroy hook, and its reconciler destroys a child whenever the slot's
  tag changes, positionally. So the pane could hold a pointer to freed memory
  and go on calling queue_render on it, which GTK answers with

    gtk_gl_area_queue_render: assertion 'GTK_IS_GL_AREA (area)' failed

  and then ignores — no crash, just a pane that drops frames until some later
  realize happens to repopulate the cache.

  Rather than enumerate the ways a widget can die (opening the new-run form is
  one; re-render churn produced the bursts actually observed, at startup and
  when runs first appeared), subscribe to GTK's own answer. `unrealize` fires
  before the widget is destroyed and also on reparenting, and after it the
  cached GL objects belong to a context that is gone, so clearing is right in
  both cases. realize! puts everything back on the way in."
  [area]
  (when-not (contains? @unrealize-wired area)
    (let [cb (ffi/foreign-callable (fn [_src _data]
                                     ;; The address leaves the wired set when
                                     ;; the widget dies: GLib recycles
                                     ;; allocation addresses, and a NEW
                                     ;; GtkGLArea at a recycled address that
                                     ;; looked "already wired" got no hook —
                                     ;; its death left :area dangling and
                                     ;; request-render! poking freed memory
                                     ;; (code-review-2026-08 #5).
                                     (swap! unrealize-wired disj area)
                                     (unmount!))
                                   [:pointer :pointer] :void :collect-safe)]
      (swap! unrealize-callables conj cb)
      (swap! unrealize-wired conj area)
      ;; Returns the handler id; 0 would mean the signal name is not valid for
      ;; this instance, which is the one way this can fail silently. Measured
      ;; nonzero on a live gl-area.
      (gffi/g-signal-connect-data area "unrealize" cb ffi/null ffi/null
                                  gffi/CONNECT-DEFAULT))))

(defn- realize! [area]
  ;; A realize with a DIFFERENT area than the one cached means the previous
  ;; widget was replaced without its unrealize reaching us. That should not
  ;; happen now that watch-unrealize! is connected; it is left in because the
  ;; only evidence this bug ever gave was a GTK critical with nothing naming
  ;; the transition that caused it (vf-38b).
  (when-let [old (:area @st)]
    (when (not= old area)
      (println "[glpane] the gl-area was replaced without an unrealize —"
               "frames were dropped between the two (vf-38b)")))
  (watch-unrealize! area)
  (glx/make-current area)
  (when-let [prog (gl/make-program vs-src fs-src)]
    (let [vao (gl/gen-one gl/gl-gen-vertex-arrays)
          vbo (gl/gen-one gl/gl-gen-buffers)
          _ (gl/gl-use-program prog)
          pos (gl/gl-get-attrib-location prog "pos")
          col (gl/gl-get-attrib-location prog "col")
          vp (gl/gl-get-uniform-location prog "vp")
          stride (* 5 (ffi/sizeof :float))]
      (gl/gl-bind-vertex-array vao)
      (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
      (gl/gl-enable-vertex-attrib-array pos)
      (gl/gl-vertex-attrib-pointer pos 2 gl/GL-FLOAT gl/GL-FALSE stride 0)
      (gl/gl-enable-vertex-attrib-array col)
      (gl/gl-vertex-attrib-pointer col 3 gl/GL-FLOAT gl/GL-FALSE stride
                                   (* 2 (ffi/sizeof :float)))
      (swap! st merge {:area area :prog prog :vao vao :vbo vbo :vp-loc vp}))))

(defn- draw! [mode verts first-vert]
  (when (seq verts)
    (gl/gl-draw-arrays mode first-vert (quot (count verts) 5))))

(defn- upload-and-draw!
  "Lines, then triangles over them, then `over` lines above both (labels)."
  [{:keys [lines tris over]}]
  (let [{:keys [prog vao vbo vp-loc w h]} @st
        all (-> (vec lines) (into tris) (into over))
        ptr (gl/write-floats all)]
    (gl/gl-use-program prog)
    (gl/gl-bind-vertex-array vao)
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-buffer-data gl/GL-ARRAY-BUFFER (* (count all) (ffi/sizeof :float))
                       ptr gl/GL-DYNAMIC-DRAW)
    (ffi/free ptr)
    (gl/gl-uniform-2f vp-loc (double w) (double h))
    (draw! gl/GL-LINES lines 0)
    (draw! gl/GL-TRIANGLES tris (quot (count lines) 5))
    (draw! gl/GL-LINES over (quot (+ (count lines) (count tris)) 5))))

(defn- view
  "The current graph, positions, and world->px transform for `source`'s
  output at the pane's present size, including the drag offset. Shared by
  render and pick, which is the whole point: what you see is what you hit."
  [{:keys [graph selected]}]
  (let [{:keys [w h pan]} @st
        positions (graph/layout graph)]
    (when (seq positions)
      {:g graph :positions positions :selected selected
       :t (graph/with-pan (graph/fit positions w h 70) pan)})))

(defn- render! [source]
  (gl/gl-clear-color 0.11 0.11 0.13 1.0)
  (gl/gl-clear gl/GL-COLOR-BUFFER-BIT)
  (let [src (source)]
    ;; A source may bring its own scene (the code atlas does): `:draw` gets
    ;; the pane's size, drag, hover and HiDPI factor and returns the verts.
    (if-let [draw (:draw src)]
      (when-let [v (draw (select-keys @st [:w :h :pan :hover :scale]))]
        (upload-and-draw! v))
      (when-let [{:keys [g positions t selected]} (view src)]
        (upload-and-draw! (scene-verts g positions t selected))))))

(defn pick
  "The node id under pixel [x y], or nil. A source with its own `:pick` is
  asked, with what `:draw` was given, so what is drawn is what is hit."
  [source x y]
  (let [src (source)]
    (if-let [p (:pick src)]
      (p (select-keys @st [:w :h :pan :scale]) x y)
      (when-let [{:keys [positions t]} (view src)]
        (graph/nearest positions t [x y] pick-radius)))))

(defn unmount!
  "Forget the widget, because it is being destroyed.

  `:area` is a raw GTK pointer captured in on-realize. Nothing tells us when
  that widget goes away — glimmer has no unrealize or destroy hook — so a
  caller that removes the pane from the tree has to say so here, or every
  later request-render! pokes freed memory. GTK catches it rather than
  crashing:

    Gtk-CRITICAL gtk_gl_area_queue_render: assertion 'GTK_IS_GL_AREA (area)' failed

  and then does nothing, so the symptom is a pane that never repaints again
  rather than a segfault. The GL objects go too: they belonged to the
  destroyed context, and realize! creates a fresh set on the way back in."
  []
  (swap! st dissoc :area :prog :vao :vbo :vp-loc))

(defn request-render! []
  (when-let [area (:area @st)]
    ;; queue-render is a bare FFI binding, and this is called from the
    ;; poller and branch-log futures — off the main thread, which GTK (and
    ;; AppKit under it on macOS) rejects intermittently. Hop through the
    ;; same g_idle_add marshal the reconciler uses (review2 #5); on-gui
    ;; runs the work inline when no loop is running, so headless callers
    ;; and tests still queue directly.
    (ui/on-gui #(glx/queue-render area))))

(defn size
  "The pane's size in device pixels as its last resize reported it, and the
  device scale: [w h scale]."
  []
  [(:w @st) (:h @st) (:scale @st 1.0)])

(defn reset-pan! []
  (swap! st assoc :pan [0.0 0.0])
  (request-render!))

;; --- pointer ------------------------------------------------------------------
;; The state machine itself is samizdat.gui.input, pure and tested. What
;; lives here is only the GTK plumbing around it, wrapped so that a throw
;; inside a callback is reported rather than swallowed: an exception thrown
;; through a foreign-callable takes the event with it and leaves the pane
;; looking dead, which is a bug class worth naming out loud.

(defn- guard
  "Run `f`, reporting anything it throws instead of losing it in the GTK
  callback boundary."
  [what f]
  (try (f)
       (catch Throwable e
         (println "[glpane]" what "failed:" (ex-message e))
         nil)))

(defn- hover! [source on-hover x y]
  (let [id (pick source x y)]
    ;; Only on CHANGE: motion fires constantly, and the callback lands in a
    ;; reactive cell that repaints the window.
    (when (not= id (:hover @st))
      (swap! st assoc :hover id)
      (when on-hover (on-hover id))
      (request-render!))))

(defn- on-press [x y]
  (let [[dx dy] (->device [x y])]
    (swap! st input/press dx dy)))

(defn- on-motion [source on-hover x y]
  (let [[dx dy] (->device [x y])]
    (swap! st input/motion dx dy)
    (if (:drag @st)
      (request-render!)
      (hover! source on-hover dx dy))))

(defn- on-release [source on-pick x y]
  (let [[dx dy] (->device [x y])
        [s pick?] (input/release @st dx dy)]
    (reset! st s)
    (when pick?
      (on-pick (pick source dx dy)))))

(defn pane
  "The :gl-area hiccup. `source` is a zero-arg fn returning
  {:graph <folded> :selected <id|nil>}; `on-pick` receives the picked node
  id (or nil when the click landed on empty space); `on-hover` receives the
  node under the pointer whenever it changes."
  [source on-pick on-hover]
  [:gl-area {:version [3 2] :depth-buffer false :hexpand true :vexpand true
             :can-focus true
             :on-realize (fn [area] (guard "realize" #(realize! area)))
             :on-render (fn [_area] (guard "render" #(render! source)))
             :on-resize (fn [area w h]
                          (guard "resize"
                                 #(let [scale (device-scale area w)]
                                    (swap! st assoc :w (max 1 w) :h (max 1 h)
                                           :scale scale)
                                    (gl/gl-viewport 0 0 w h))))
             :on-motion (fn [_area x y]
                          (guard "motion" #(on-motion source on-hover x y)))
             :on-button (fn [_area _btn pressed? x y]
                          (guard "button"
                                 #(if pressed?
                                    (on-press x y)
                                    (on-release source on-pick x y))))}])
