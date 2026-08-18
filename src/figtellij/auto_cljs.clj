(ns figtellij.auto-cljs
  "nREPL middleware that gives every session its own ClojureScript REPL on a
  running figwheel.main build, without the client having to ask for one.

  The hand-off is `figwheel.main.api/cljs-repl`, which is what a terminal
  figwheel REPL running under nREPL ends up calling anyway: `figwheel.main/repl`
  detects nREPL and delegates to `cider.piggieback/cljs-repl`, passing the
  build's repl-env and its `:compiler-env`. So an auto-attached session talks to
  the same browser, and shares the same compiler state, as the build that was
  started at boot.

  ## When it fires

  Clients bootstrap themselves when they connect — Cursive in particular
  evaluates a batch of setup forms on the fresh session — and all of that has to
  run as Clojure. So the attach doesn't happen on connect or on first eval: a
  watcher waits for the session to go quiet (`:quiet-ms`, one second) with
  nothing in flight, and attaches then. Any message on the session resets the
  timer, so a slow bootstrap just delays the hand-off rather than getting cut in
  half by it.

  ## Why it's built this way

  1. The hand-off is injected as a real `eval` message on the session. Piggieback
     records the REPL by `set!`-ing dynamic vars that are only thread-bound
     *inside* an evaluation, so it cannot be performed from a handler.

  2. Piggieback's `wrap-cljs-repl` decides how to route a message by reading the
     session state as the message passes through it, so an eval forwarded while
     the hand-off is still running would execute as Clojure. The watcher holds a
     per-session lock across the whole hand-off and the middleware takes the same
     lock before forwarding an eval, which closes that window. In the normal case
     (session already attached, or hand-off not due) the lock is uncontended.

  3. The attach only happens once the build has a live JS environment.
     `cljs.repl/-setup` evaluates a form in the browser, and figwheel parks in
     `wait-for-connection` — a sleep loop with no escape — until one is attached.
     Waiting for that happens on the watcher thread, so it never occupies one of
     nREPL's handler threads or delays the client's own messages.

  ## One session per connection

  Clients open more than one session on a connection: Cursive opens one for
  JVM-side tooling first and the REPL you type in second. Exactly one session per
  connection is auto-attached — `:attach-nth-session`, the second by default,
  counting in the order the sessions first send a message — and the rest are left
  on the JVM.

  The choice is made when a session is ready to attach rather than when it first
  speaks, so by then the client's other sessions have shown up and can be counted.
  If the nth never arrives, the last session seen wins once the connection has
  been open for `:session-grace-ms`, which is what lets single-session clients
  still get a ClojureScript REPL.

  Attach a skipped session by hand with `(figwheel.main.api/cljs-repl \"dev\")`,
  or set `:attach-nth-session nil` to attach every session."
  (:require
   [cider.piggieback :as piggieback]
   [cljs.util :as cljs-util]
   [clojure.string :as str]
   [figwheel.main.api :as fig]
   [figwheel.repl :as fig-repl]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.print :as print]
   [nrepl.transport :as transport])
  (:import
   (java.util Collections UUID WeakHashMap)
   (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)))

(def ^:private defaults
  {:build-id           "dev"
   :enabled?           true
   ;; How long a session must be silent — no messages, nothing evaluating —
   ;; before it is handed a ClojureScript REPL.
   :quiet-ms           1000
   ;; Which session on a connection gets the ClojureScript REPL: 1-based, in the
   ;; order the sessions first send a message. Cursive's REPL session is the
   ;; second, behind the one it keeps for JVM-side tooling. nil attaches all of
   ;; them.
   :attach-nth-session 2
   ;; If set, and the nth session never turns up, fall back to the last one seen
   ;; on the connection once it has been open this long — that is what would let
   ;; a single-session client still get a ClojureScript REPL. nil turns the
   ;; fallback off: only the nth session is ever attached, and a connection that
   ;; never opens one is left entirely on the JVM.
   :session-grace-ms   nil
   ;; Once a session is quiet, how long to keep waiting for a browser to connect
   ;; to the build before giving up (and saying so). Evaluating again re-arms it.
   :connect-timeout-ms 60000
   ;; Backstop on the hand-off eval itself, so a wedged JS runtime can't leave a
   ;; session stuck mid-attach.
   :attach-timeout-ms  60000
   ;; An evaluation this old is no longer counted as "in flight". Only relevant
   ;; before the attach, and only to stop an interrupted evaluation — whose
   ;; completion nREPL reports out of band, where we can't see it — from parking
   ;; the watcher forever.
   :busy-ttl-ms        60000
   ;; After attaching, send the session an unsolicited response carrying `:ns`,
   ;; so a client that tracks the current namespace from responses notices the
   ;; switch without waiting for the user to evaluate something. The value is the
   ;; namespace to claim if piggieback's own can't be read; nil disables it.
   ;; Reproduce, on the session, the output a terminal figwheel REPL prints when
   ;; it starts — `[Figwheel] Starting REPL`, the controls banner, the
   ;; ClojureScript version line and a `cljs.user=> ` prompt. nREPL carries no
   ;; signal for "this session changed language", so a client that recognises a
   ;; ClojureScript REPL by its banner needs to actually see one.
   :announce-banner?   true
   :announce-ns        "cljs.user"
   ;; Also tag that message with the id of the last message the session
   ;; completed, for clients that only look at replies to their own requests. It
   ;; is a response arriving after its request was already done, so it is off
   ;; unless the plain announcement turns out not to be enough.
   :announce-ns-id?    false})

(defonce ^:private config (atom defaults))

(defn configure!
  "Merge `m` into the middleware's options. See `defaults`."
  [m]
  (swap! config merge m))

(defonce ^:private scheduler
  (delay
    (Executors/newSingleThreadScheduledExecutor
     (reify ThreadFactory
       (newThread [_ r]
         (doto (Thread. ^Runnable r "auto-cljs-watcher")
           (.setDaemon true)))))))

(defn- now [] (System/currentTimeMillis))

;; Per-session bookkeeping lives in the session atom's metadata rather than in a
;; registry of our own: nREPL and piggieback both already do this, and it means
;; the state is collected along with the session instead of leaking.
;;
;; :phase      :idle -> :attaching -> :done  (:idle is also the re-armable state)
;; :in-flight  token -> start time, for evaluations we haven't seen finish
;; :watching?  whether a tick chain is currently scheduled for this session
(defn- watch-state [session]
  (or (::watch (meta session))
      (::watch (alter-meta!
                session
                (fn [m]
                  (if (::watch m)
                    m
                    (assoc m ::watch (atom {:phase         :idle
                                            :in-flight     {}
                                            :last-activity 0
                                            :armed-at      0
                                            :watching?     false}))))))))

(defn- cljs-session? [session]
  (some? (get @session #'piggieback/*cljs-repl-env*)))

(defn- persistent-session?
  "True for a session created by `clone`. A client that evaluates without a
  session id gets a throwaway session per message; handing each of those a
  ClojureScript REPL would mean a pointless browser round-trip every time.
  nREPL only adds `:close` to the metadata when it registers a session."
  [session]
  (some? (:close (meta session))))

(defn- js-env-connected? [repl-env]
  (boolean (seq (fig-repl/connections-available repl-env))))

(defn- busy? [st ttl-ms]
  (let [cutoff (- (now) ttl-ms)]
    (boolean (some #(> ^long % cutoff) (vals (:in-flight @st))))))

(defn- done? [resp]
  (contains? (into #{} (map name) (:status resp)) "done"))

;; ---------------------------------------------------------------------------
;; one ClojureScript session per connection

(defonce ^:private connections
  ;; connection transport -> {:seen  [session-id ...]  ; order of first message
  ;;                          :since <ms>              ; when we first saw it
  ;;                          :owner <session-id>}     ; holds the CLJS REPL
  ;; Weak keys, so an entry goes when its connection is collected. The values are
  ;; strings and numbers only — anything reaching back to the transport would pin
  ;; the entry forever.
  (Collections/synchronizedMap (WeakHashMap.)))

(defn- forget-on-close!
  "Drop a session from its connection's registry when it closes, releasing the
  ClojureScript claim if it held one, so a session opened later can take over.
  nREPL's session middleware handles `close` itself and never delegates it, so
  the only way in is the `:close` fn it keeps in the session's metadata — the
  same hook piggieback uses for its teardown."
  [transport session]
  (when-not (::close-hooked (meta session))
    (alter-meta!
     session
     (fn [m]
       (let [orig-close (:close m)
             sid        (:id m)]
         (assoc m
                ::close-hooked true
                :close (fn []
                         (locking connections
                           (when-let [c (.get connections transport)]
                             (.put connections transport
                                   (cond-> (update c :seen #(vec (remove #{sid} %)))
                                     (= sid (:owner c)) (assoc :owner nil)))))
                         (when orig-close (orig-close)))))))))

(defn- register-session!
  "Record this session against its connection, in the order sessions first speak."
  [transport session]
  (let [sid (:id (meta session))]
    (locking connections
      (let [c (or (.get connections transport) {:seen [] :since (now) :owner nil})]
        (when-not (some #{sid} (:seen c))
          (.put connections transport (update c :seen conj sid))
          (forget-on-close! transport session))))))

(defn- target-session
  "Which session on this connection should get the ClojureScript REPL: the
  configured ordinal, or — when `:session-grace-ms` is set, the connection has
  been around that long, and the ordinal never showed up — the last one seen."
  [{:keys [seen since]}]
  (let [{:keys [attach-nth-session session-grace-ms]} @config]
    (or (get seen (dec attach-nth-session))
        (when (and session-grace-ms (>= (- (now) since) session-grace-ms))
          (peek seen)))))

(defn- claim
  "Decide whether `session` gets the ClojureScript REPL for its connection.
  Returns :owner, :taken (another session has it, stay on the JVM), or :not-yet
  (the session that should have it hasn't turned up — ask again later)."
  [transport session]
  (let [sid (:id (meta session))]
    (locking connections
      (let [c (.get connections transport)]
        (cond
          (nil? c)                   :owner ; untracked, nothing to arbitrate
          (= sid (:owner c))         :owner
          (some? (:owner c))         :taken
          (= sid (target-session c)) (do (.put connections transport (assoc c :owner sid))
                                         :owner)
          :else                      :not-yet)))))

(defn- log! [msg]
  (println (str "[auto-cljs] " msg)))

(defn- notify!
  "Report to the server console, and to the client as an unsolicited `:out` on
  its session. There is no message in flight to reply to — that is the whole
  point of attaching while the session is quiet — so this is the only channel
  available; clients that don't display session output will still see the ns
  change to cljs.user on their next evaluation."
  [st msg]
  (log! msg)
  (let [{:keys [transport session]} @st]
    (try
      (transport/send transport {:session (:id (meta session))
                                 :out     (str "; auto-cljs: " msg "\n")})
      (catch Throwable _))))

(defn- out!
  "Write to the session's output, with no message in flight to attach it to."
  [st s]
  (let [{:keys [transport session]} @st]
    (try
      (transport/send transport {:session (:id (meta session)) :out s})
      (catch Throwable _))))

(defn- cljs-ns
  "The ClojureScript namespace piggieback left the session in. It tracks this in
  `cljs.analyzer/*cljs-ns*` in the session map; the var is private to piggieback's
  implementation namespace, so find it by name rather than reaching into it."
  [session]
  (some (fn [[k v]]
          (when (and (var? k) (= '*cljs-ns* (:name (meta k)))) v))
        @session))

(defn- announce-banner!
  "Finish the terminal-REPL banner on the session: the controls text that
  piggieback's hand-off printed (which otherwise goes nowhere, since we run it on
  a sink transport), then the two lines `cljs.repl/repl*` would print as it takes
  over — `ClojureScript <version>` and the `cljs.user=> ` prompt.

  `[Figwheel] Starting REPL` is sent before the hand-off, so the whole thing
  arrives in the order `clojure -M:fig` produces it."
  [st attach-out]
  (when (:announce-banner? @config)
    (out! st (str ;; piggieback signs off with a line `cljs.repl/repl*` never
                  ;; prints; drop it so the banner matches `clojure -M:fig` byte
                  ;; for byte
                  (str/replace attach-out #"(?m)^To quit, type: :cljs/quit\R" "")
                  "ClojureScript " (cljs-util/clojurescript-version) "\n"
                  (or (cljs-ns (:session @st)) (:announce-ns @config)) "=> "))))

(defn- announce-ns!
  "Tell the client that the session's namespace is now the ClojureScript one.

  The hand-off happens while the session is quiet, so it is not a reply to
  anything the client sent, and nREPL has no notion of an unsolicited namespace
  change — the `:ns` key normally only ever rides along on a response to a
  request. Clients that track the current namespace from any response for their
  session will pick this up; ones that only look at replies to their own request
  ids won't, and will see the change on their next evaluation instead.

  `:announce-ns-id?` additionally tags the message with the id of the last
  message the session completed, for clients in the second group. That is a
  response arriving after its request already got `:status :done`, which is
  irregular enough to be off by default."
  [st]
  (when-let [fallback (:announce-ns @config)]
    (let [{:keys [transport session last-msg-id]} @st]
      (try
        (transport/send
         transport
         (cond-> {:session (:id (meta session))
                  :ns      (str (or (cljs-ns session) fallback))}
           (and (:announce-ns-id? @config) last-msg-id)
           (assoc :id last-msg-id)))
        (catch Throwable _)))))

;; ---------------------------------------------------------------------------
;; the hand-off

(defn- attach!
  "Run `(figwheel.main.api/cljs-repl build-id)` as a real eval on `session`, down
  the rest of the middleware stack, and block until it reports done. Returns the
  output it produced, for reporting when the hand-off fails."
  [h session build-id timeout-ms]
  (let [done (promise)
        log  (StringBuilder.)
        sink (reify transport/Transport
               (recv [_] nil)
               (recv [_ _] nil)
               (send [this resp]
                 (doseq [k [:out :err :ex :root-ex]]
                   (when-let [v (get resp k)] (.append log (str v))))
                 (when (done? resp) (deliver done resp))
                 this))]
    (h {:op        "eval"
        :id        (str "auto-cljs-" (UUID/randomUUID))
        :session   session
        :transport sink
        :code      (format "(do (require 'figwheel.main.api) (figwheel.main.api/cljs-repl %s))"
                           (pr-str build-id))})
    (when (= ::timeout (deref done timeout-ms ::timeout))
      (.append log (format "timed out after %dms" timeout-ms)))
    (str log)))

(defn- skip!
  "Leave this session on the JVM. Reported to the server console only: the point
  of the rule is to stop a client's extra sessions producing noise."
  [st reason]
  (when (not= :skipped (:phase @st))
    (swap! st assoc :phase :skipped)
    (log! (format (str "session %s left on the JVM — %s "
                       "Evaluate (figwheel.main.api/cljs-repl %s) in it to attach it by hand.")
                  (:id (meta (:session @st)))
                  reason
                  (pr-str (:build-id @config))))))

(defn- try-attach!
  "Attach if the session is still quiet and it is the one that should have the
  ClojureScript REPL on its connection. Returns :attached, :done (nothing to do)
  or :retry. Holds the session lock for the whole hand-off so that no evaluation
  can be routed while it runs."
  [st]
  (locking st
    (let [{:keys [quiet-ms busy-ttl-ms attach-timeout-ms build-id attach-nth-session]} @config
          {:keys [phase last-activity h session transport]} @st]
      (cond
        (not= :idle phase) :done

        ;; someone attached by hand in the meantime
        (cljs-session? session)
        (do (swap! st assoc :phase :done) :done)

        ;; a message landed between the watcher deciding to attach and getting here
        (or (busy? st busy-ttl-ms)
            (< (- (now) last-activity) quiet-ms))
        :retry

        :else
        (case (if attach-nth-session (claim transport session) :owner)
          ;; the session that should have it hasn't turned up yet
          :not-yet :not-yet
          :taken   (do (skip! st "its connection already has a ClojureScript session.")
                       :done)
          :owner
          (do
            (swap! st assoc :phase :attaching)
            (when (:announce-banner? @config)
              (out! st "[Figwheel] Starting REPL\n"))
            (let [out (attach! h session build-id attach-timeout-ms)]
              (swap! st assoc :phase :done)
              (if (cljs-session? session)
                (let [msg (format "attached session %s to figwheel build %s"
                                  (:id (meta session)) (pr-str build-id))]
                  ;; With the banner on this stays on the console, so what the
                  ;; client sees is exactly what `clojure -M:fig` prints and
                  ;; nothing else.
                  (if (:announce-banner? @config) (log! msg) (notify! st msg))
                  (announce-banner! st out)
                  (announce-ns! st))
                (notify! st (format "could not attach to build %s, staying in Clojure: %s"
                                    (pr-str build-id) out)))
              :attached)))))))

;; ---------------------------------------------------------------------------
;; the quiet-period watcher

(declare tick)

(defn- schedule! [st delay-ms]
  (.schedule ^ScheduledExecutorService @scheduler
             ^Runnable (fn [] (tick st))
             (long delay-ms)
             TimeUnit/MILLISECONDS))

(defn- give-up! [st build-id]
  (swap! st assoc :watching? false)
  (when-not (:notified? @st)
    (swap! st assoc :notified? true)
    (notify! st (format (str "no JS environment connected to build %s, staying in Clojure. "
                             "Load the app in a browser and evaluate something to be attached.")
                        (pr-str build-id)))))

(defn- tick [st]
  (try
    (let [{:keys [quiet-ms connect-timeout-ms build-id busy-ttl-ms]} @config
          {:keys [phase last-activity armed-at]} @st
          quiet-for (- (now) last-activity)]
      (cond
        (not= :idle phase)      (swap! st assoc :watching? false)
        (busy? st busy-ttl-ms)  (schedule! st quiet-ms)
        (< quiet-for quiet-ms)  (schedule! st (max 50 (- quiet-ms quiet-for)))

        :else
        (let [repl-env (fig/repl-env build-id)] ; nil until the build is registered
          (cond
            (and repl-env (js-env-connected? repl-env))
            (case (try-attach! st)
              ;; the session got busy again between deciding and attaching
              :retry (do (swap! st dissoc :not-yet-since)
                         (schedule! st quiet-ms))
              ;; waiting for the session that should own the connection's REPL.
              ;; Bounded, because with :session-grace-ms off nothing else will
              ;; ever resolve this — a connection can simply never open one.
              :not-yet
              (let [since (or (:not-yet-since @st) (now))]
                (swap! st assoc :not-yet-since since)
                (if (>= (- (now) since) connect-timeout-ms)
                  (do (skip! st (format (str "it is not session #%d on its connection, "
                                             "and that session never arrived.")
                                        (:attach-nth-session @config)))
                      (swap! st assoc :watching? false))
                  (schedule! st quiet-ms)))
              (swap! st assoc :watching? false))

            (>= (- (now) armed-at) connect-timeout-ms) (give-up! st build-id)
            :else                                      (schedule! st 250)))))
    (catch Throwable t
      (swap! st assoc :watching? false)
      (println "[auto-cljs] watcher error:" (.getMessage t)))))

(defn- arm!
  "Start a tick chain for this session if one isn't already running."
  [st]
  (let [[before after] (swap-vals! st (fn [s]
                                        (if (and (= :idle (:phase s)) (not (:watching? s)))
                                          (assoc s :watching? true :armed-at (now))
                                          s)))]
    (when (and (not (:watching? before)) (:watching? after))
      (schedule! st (:quiet-ms @config)))))

;; ---------------------------------------------------------------------------
;; middleware

(defn- quit? [code]
  (and (string? code) (str/ends-with? (str/trim code) ":cljs/quit")))

(defn- tracking-transport
  "Note when an evaluation finishes, so the watcher doesn't mistake a session
  that is busy working for a session that has gone quiet."
  [transport st token]
  (reify transport/Transport
    (recv [_] (transport/recv transport))
    (recv [_ timeout] (transport/recv transport timeout))
    (send [this resp]
      (transport/send transport resp)
      (when (done? resp)
        (swap! st #(-> % (update :in-flight dissoc token) (assoc :last-activity (now)))))
      this)))

(defn- fix-quit-transport
  "Wrap `transport` so that piggieback's session vars are cleared for real once
  a `:cljs/quit` has been acked.

  Piggieback clears them by `swap!`-ing the session atom, but since nREPL 1.3
  the session executor pushes the whole session map as thread bindings around
  each evaluation and merges `get-thread-bindings` back over the session when
  the task finishes. That write-back restores the repl-env piggieback had just
  removed, so without this the session silently stays in ClojureScript. The
  write-back happens before the `:done` ack, so re-clearing here wins."
  [transport session]
  (reify transport/Transport
    (recv [_] (transport/recv transport))
    (recv [_ timeout] (transport/recv transport timeout))
    (send [this resp]
      (transport/send transport resp)
      (when (done? resp)
        (swap! session assoc
               #'piggieback/*cljs-repl-env*     nil
               #'piggieback/*cljs-compiler-env* nil
               #'piggieback/*cljs-repl-options* nil))
      this)))

(defn wrap-auto-cljs
  "Hand each session a ClojureScript REPL on the configured figwheel build once
  it has been quiet for `:quiet-ms`. `:cljs/quit` drops a session back to Clojure
  for good; re-attach by hand with `(figwheel.main.api/cljs-repl \"dev\")`."
  [h]
  (fn [{:keys [session op code transport id] :as msg}]
    (if-not (and (:enabled? @config) session (persistent-session? session))
      (h msg)
      (let [st (watch-state session)]
        (swap! st assoc
               :h h :session session :transport transport
               :last-activity (now)
               :last-msg-id (or id (:last-msg-id @st)))
        (register-session! transport session)
        (when (= :idle (:phase @st))
          (arm! st))
        (if-not (#{"eval" "load-file"} op)
          (h msg)
          (let [token (UUID/randomUUID)]
            (locking st
              (swap! st assoc-in [:in-flight token] (now))
              (h (assoc msg :transport
                        (cond-> (tracking-transport transport st token)
                          (and (= "eval" op) (quit? code) (cljs-session? session))
                          (fix-quit-transport session)))))))))))

(set-descriptor! #'wrap-auto-cljs
                 {;; after the session middleware, so :session is the session
                  ;; atom and not just an id
                  :requires #{"clone"}
                  ;; before piggieback, so that by the time it sees a client's
                  ;; message the session is already a ClojureScript one; and
                  ;; before wrap-print, which swaps in a fresh per-message
                  ;; transport — we need the connection's own transport, since
                  ;; its identity is what tells two sessions on one connection
                  ;; apart from two sessions on two connections
                  :expects  #{#'piggieback/wrap-cljs-repl #'print/wrap-print
                              "eval" "load-file"}
                  :handles  {}})
