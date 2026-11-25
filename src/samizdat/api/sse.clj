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

(ns samizdat.api.sse
  "Following a server-sent event stream: the client end of GET
  /v1/runs/:id/events, for a front end that wants a run pushed to it.

  Toolkit-free, like samizdat.api.client, so the suite covers it with no
  terminal. The http client reads a response whole, and a stream never ends,
  so this speaks HTTP/1.1 over its socket directly: the response head, the
  chunked body, and the event lines inside it.

  BYTES, NOT CHARACTERS, until a line is whole. A chunk's size is octets and a
  multibyte character can be cut across two chunks; a line ends at a newline
  byte, which never occurs inside a UTF-8 sequence, so a line is decoded once
  and whole."
  (:require [samizdat.security.token :as token]
            ;; the java.time.* host shim, before data.json — see samizdat.store.journal
            [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [jolt.http.net :as net]))

;; --- the parser --------------------------------------------------------------

(defn reader
  "A fresh parser: feed it the bytes of one connection with `feed`.

  `:keep-body? true` keeps a response that is not an event stream — a status
  other than 200, or a 200 with some other content type — as its raw body
  (`:raw`, `body-text`) rather than giving it up: a provider's error, or a
  provider that answered a streamed request whole."
  ([] (reader nil))
  ([{:keys [keep-body?]}]
   {:phase :head :buf [] :status nil :headers {} :chunked? false
    :need nil :skip 0 :line [] :event {} :done? false
    :keep-body? (boolean keep-body?) :raw? false :raw []}))

(defn- utf8 [bs] (String. (byte-array bs) "UTF-8"))

(defn- head-end
  "Index just past the blank line ending the head in `buf`, or nil."
  [buf]
  (let [n (count buf)]
    (loop [i 3]
      (cond (>= i n) nil
            (and (= 13 (nth buf (- i 3))) (= 10 (nth buf (- i 2)))
                 (= 13 (nth buf (- i 1))) (= 10 (nth buf i))) (inc i)
            :else (recur (inc i))))))

(defn- dispatch
  "The event a blank line completes, or nil when it carried no data."
  [{:keys [data] :as ev}]
  (when (seq data)
    (cond-> {:data (str/join "\n" data)}
      (:id ev) (assoc :id (:id ev))
      (:event ev) (assoc :event (:event ev)))))

(defn- on-line
  "One whole event-stream line into state `st`, collecting into `out`."
  [st out line]
  (let [line (if (str/ends-with? line "\r") (subs line 0 (dec (count line))) line)]
    (cond
      (= "" line)
      [(assoc st :event {}) (if-let [e (dispatch (:event st))] (conj out e) out)]

      (str/starts-with? line ":") [st out]

      :else
      (let [i (str/index-of line ":")
            field (if i (subs line 0 i) line)
            v (if i (subs line (inc i)) "")
            v (if (str/starts-with? v " ") (subs v 1) v)]
        [(case field
           "data" (update-in st [:event :data] (fnil conj []) v)
           "id" (assoc-in st [:event :id] v)
           "event" (assoc-in st [:event :event] v)
           st)
         out]))))

(defn- payload
  "Body bytes (already de-chunked) into event lines."
  [st out bs]
  (reduce (fn [[st out] b]
            (if (= 10 b)
              (on-line (assoc st :line []) out (utf8 (:line st)))
              [(update st :line conj b) out]))
          [st out] bs))

(defn- sink
  "Body bytes to where they go: the raw body, or the event lines."
  [st out bs]
  (if (:raw? st) [(update st :raw into bs) out] (payload st out bs)))

(defn- chunked
  "De-chunk `bs` into the event parser."
  [st out bs]
  (loop [st st, out out, bs (seq bs)]
    (cond
      (or (nil? bs) (:done? st)) [st out]

      (pos? (:skip st)) (recur (update st :skip dec) out (next bs))

      (nil? (:need st))
      (let [b (first bs)]
        (if (= 10 b)
          (let [size-line (str/trim (first (str/split (utf8 (:buf st)) #";")))
                n (Long/parseLong (if (str/blank? size-line) "0" size-line) 16)]
            (if (zero? n)
              [(assoc st :done? true :buf []) out]
              (recur (assoc st :need n :buf []) out (next bs))))
          (recur (update st :buf conj b) out (next bs))))

      :else
      (let [k (min (:need st) (count bs))
            [st out] (sink st out (take k bs))
            left (- (:need st) k)]
        (recur (if (zero? left) (assoc st :need nil :skip 2) (assoc st :need left))
               out (seq (drop k bs)))))))

(defn- body [st out bs]
  (if (:chunked? st) (chunked st out bs) (sink st out bs)))

(defn- head-fields
  "The response head's header fields, names lower-cased."
  [head]
  (into {} (for [line (rest (str/split-lines head))
                 :let [i (str/index-of line ":")]
                 :when i]
             [(str/lower-case (str/trim (subs line 0 i))) (str/trim (subs line (inc i)))])))

(defn body-text
  "A kept body (`:keep-body?`) as text."
  [st]
  (utf8 (:raw st)))

(defn feed
  "Feed `bytes` to parser state `st`. Returns {:state :events}: the events
  those bytes completed, each {:data} with :id and :event when the stream
  named them. `:status` on the state once the head is in; `:done?` when the
  stream ended or was refused (a status other than 200)."
  [st bytes]
  (let [bs (vec bytes)]
    (if (= :head (:phase st))
      (let [buf (into (:buf st) bs)]
        (if-let [end (head-end buf)]
          (let [head (utf8 (subvec buf 0 end))
                status (some-> (re-find #"^HTTP/\d\.\d (\d{3})" head) second parse-long)
                fields (head-fields head)
                chunked? (boolean (some-> (get fields "transfer-encoding") str/lower-case
                                          (str/includes? "chunked")))
                events? (boolean (some-> (get fields "content-type") str/lower-case
                                         (str/includes? "text/event-stream")))
                raw? (and (:keep-body? st) (or (not= 200 status) (not events?)))
                st (assoc st :phase :body :buf [] :status status :headers fields
                          :chunked? chunked? :raw? raw?)]
            (if (or raw? (= 200 status))
              (let [[st out] (body st [] (subvec buf end))]
                {:state st :events out})
              {:state (assoc st :done? true) :events []}))
          {:state (assoc st :buf buf) :events []}))
      (let [[st out] (body st [] bs)]
        {:state st :events out}))))

(defn parse-data
  "An event's data as {:data value}, JSON read with keyword keys; the raw
  text when it is not JSON."
  [text]
  {:data (try (json/read-str (str text) :key-fn keyword)
              (catch Throwable _ text))})

;; --- following a stream --------------------------------------------------------

(defn parse-url
  "`url` as {:tls? :host :port :path}, the port defaulting by scheme."
  [url]
  (let [[_ scheme host port path] (re-find #"^(https?)://([^:/]+)(?::(\d+))?(/.*)?$" (str url))
        tls? (= "https" scheme)]
    {:tls? tls? :host host :port (or (some-> port parse-long) (if tls? 443 80))
     :path (or path "/")}))

(defn- request-text
  ([target last-id] (request-text target last-id nil))
  ([{:keys [host port path]} last-id epoch]
   (str "GET " path " HTTP/1.1\r\n"
        "Host: " host ":" port "\r\n"
        ;; The harness's token, read again on each connection so a restarted
        ;; server's new one is picked up (samizdat.security.token).
        (when-let [t (and (#{"127.0.0.1" "localhost"} host) (token/read-for port))]
          (str "Authorization: Bearer " t "\r\n"))
        "Accept: text/event-stream\r\n"
        "Cache-Control: no-cache\r\n"
        (when last-id (str "Last-Event-ID: " last-id "\r\n"))
        ;; The event shape this client last read, so a server that has
        ;; changed it says resync rather than paging on (samizdat.api.stream).
        (when epoch (str "X-Samizdat-Epoch: " epoch "\r\n"))
        "\r\n")))

(defn- timeout? [e] (str/includes? (str (ex-message e)) "timed out"))

(defn- epoch-of
  "The event shape a `hello` or `resync` names, as text, or nil."
  [e]
  (when (#{"hello" "resync"} (:event e))
    (some-> (:data (parse-data (:data e))) :epoch str)))

(defn- one-connection
  "Read one connection until it ends, `stop?` says so, or nothing (not even a
  heartbeat) arrives for `dead-ms`. Returns {:last-id :epoch}: the last event
  id seen and the event shape the server named."
  [target {:keys [last-id epoch]} {:keys [on-event on-status on-resync stop? slice-ms dead-ms]}]
  (let [fd (net/connect (:host target) (:port target) 3000)]
    (try
      (net/set-read-timeout! fd slice-ms)
      (net/send-bytes fd (.getBytes (request-text target last-id epoch) "UTF-8"))
      (loop [st (reader), seen {:last-id last-id :epoch epoch}, heard (System/currentTimeMillis)]
        (if (stop?)
          seen
          (let [got (try (net/recv-bytes fd)
                         (catch Throwable e (if (timeout? e) ::quiet (throw e))))]
            (cond
              (nil? got) seen
              (= ::quiet got) (if (> (- (System/currentTimeMillis) heard) dead-ms)
                                seen
                                (recur st seen heard))
              :else
              (let [{:keys [state events]} (feed st got)
                    seen {:last-id (or (some :id (reverse events)) (:last-id seen))
                          :epoch (or (some epoch-of (reverse events))
                                     (get-in state [:headers "x-samizdat-epoch"])
                                     (:epoch seen))}]
                (when (and (:status state) (not (:status st)) on-status)
                  (on-status (:status state)))
                (doseq [e events]
                  (when (and on-resync (= "resync" (:event e)))
                    (on-resync (:data (parse-data (:data e)))))
                  (on-event e))
                (if (:done? state)
                  seen
                  (recur state seen (System/currentTimeMillis))))))))
      (finally (net/close fd)))))

(defn follow!
  "Follow the event stream at `url` until `stop?` answers true, calling
  `on-event` with each event ({:id :event :data}). Blocks: run it on a thread
  of its own.

  Reconnects when the connection ends or goes silent past `dead-ms`,
  sending the last id seen as Last-Event-ID so the server resumes after it;
  waits `retry-ms` between attempts. `on-status` hears each response's status
  (200 when streaming) and `on-error` each failed attempt.

  RESYNC. The server names its event shape (`epoch`) in a header and its
  opening `hello`; this sends back the one it last heard (`:epoch` to start
  with one). When the client's shape differs, or its cursor is below what the
  server still holds, the server sends `resync` and resumes from its head:
  `on-resync` hears the event's data ({:reason :snapshot :resume ...}) — the
  caller reloads the run snapshot rather than trusting what it has paged —
  before `on-event` hears the event itself."
  [url {:keys [last-event-id epoch on-event on-status on-resync on-error stop?
               retry-ms slice-ms dead-ms]
        :or {retry-ms 1000 slice-ms 250 dead-ms 45000}}]
  (let [target (parse-url url)
        opts {:on-event on-event :on-status on-status :on-resync on-resync :stop? stop?
              :slice-ms slice-ms :dead-ms dead-ms}]
    (loop [seen {:last-id last-event-id :epoch (some-> epoch str)}]
      (when-not (stop?)
        (let [seen (try (one-connection target seen opts)
                        (catch Throwable e
                          (when on-error (on-error e))
                          seen))]
          (when-not (stop?)
            (Thread/sleep retry-ms)
            (recur seen)))))))
