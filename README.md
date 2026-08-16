# figtellij

A tools.deps figwheel.main project whose nREPL server hands **every** session
that connects a ClojureScript REPL on the running build. Connect from IntelliJ /
Cursive, CIDER, `lein repl :connect`, whatever — you land in `cljs.user` without
evaluating anything to get there.

## Run it

```
clojure -M:nrepl            # build "dev", nREPL on 7888
clojure -M:nrepl dev 7888   # explicit build id and port
```

That boots the figwheel build (server on <http://localhost:9500>, watcher, browser
opens), starts nREPL, and writes `.nrepl-port` so editors find it. Then point your
client at port 7888 and evaluate something:

```clojure
cljs.user=> *clojurescript-version*
"1.12.145"
cljs.user=> (js/Math.max 3 4)
4
```

Other aliases: `clojure -M:fig` for a plain terminal figwheel REPL (no nREPL),
`clojure -M:min` for an advanced-optimizations build.

## How the attach works

You asked for a session that evaluates

```clojure
(do (require 'figwheel.main.api) (figwheel.main.api/start "dev"))
```

and for later clients to join it. This does the same thing, split in two, which
is what makes the "later clients" half possible:

- **At boot**, `figintellij.nrepl` starts the build with `:mode :serve`. Plain
  `(start "dev")` defaults to `:mode :repl`, which starts a ClojureScript REPL on
  the calling thread — fine at a terminal, but it would make the build's lifetime
  depend on one privileged session, and `stop` / `repl-env` / `cljs-repl` are only
  usable when you start non-blocking. So the boot only brings up the server,
  watcher and build registry entry.

- **Per session**, `figintellij.auto-cljs` runs `(figwheel.main.api/cljs-repl "dev")`
  once the session has gone quiet (see below). That is the same call the terminal
  REPL ends up making: `figwheel.main/repl` detects nREPL and delegates to
  `cider.piggieback/cljs-repl`, passing the build's repl-env **and its
  `:compiler-env`**. So sessions aren't merely each talking to a browser — they
  share one browser and one compiler env:

  ```clojure
  ;; session A
  cljs.user=> (swap! figintellij.core/app-state assoc :written-by :session-a)
  ;; session B
  cljs.user=> @figintellij.core/app-state
  {:reloads 0, :written-by :session-a}
  ```

### When it fires: one second of silence

Clients bootstrap themselves on the JVM side when they connect — Cursive
evaluates a batch of setup forms on the fresh session — and all of that has to
run as Clojure. So the attach is triggered by **silence**, not by connecting or
by the first eval: a watcher waits until the session has seen no messages and
has nothing evaluating for `:quiet-ms` (one second), and hands off then. Any
message resets the timer, so a slow bootstrap just delays the hand-off instead of
getting cut in half by it:

```
(clojure.core/str (clojure.core/type 1))   => "class java.lang.Long"   ns=user       ; bootstrap
(do (Thread/sleep 3000) (System/getProperty "java.version"))
                                           => "21.0.4"                 ns=user       ; 3s, still Clojure
(str (type 1))                             => "class java.lang.Long"   ns=user       ; 3ms later, still Clojure
;; ... one second of quiet ...
*clojurescript-version*                    => "1.12.145"               ns=cljs.user
```

Because the hand-off happens while nothing is in flight, there is no client
message to reply to. It's reported on the server console and as an unsolicited
`:out` on the session:

```
[auto-cljs] attached session 490cf30e-… to figwheel build "dev"
```

Clients that don't display session-level output will still see the ns change to
`cljs.user` on their next evaluation.

Three details in `auto_cljs.clj` are load-bearing, all documented in the source:

1. The hand-off is injected as a **real `eval` message** on the session.
   Piggieback records the REPL by `set!`-ing dynamic vars that only exist inside
   an evaluation, so it can't be done from a handler.

2. Piggieback's `wrap-cljs-repl` decides how to route a message when the message
   passes through it, so an eval forwarded while the hand-off is still running
   would execute as Clojure. The watcher holds a per-session lock across the
   whole hand-off and the middleware takes the same lock before forwarding an
   eval. In the normal case the lock is uncontended.

3. The attach only happens once the build has a **live JS environment**.
   `cljs.repl/-setup` evaluates a form in the browser, and figwheel parks in
   `wait-for-connection` (a sleep loop with no escape) until one is attached.
   That waiting happens on the watcher thread, so it never ties up one of
   nREPL's handler threads or delays the client's own messages.

If no browser is connected, the watcher polls for `:connect-timeout-ms` (60s),
then says so and stops:

```
; auto-cljs: no JS environment connected to build "dev", staying in Clojure.
; Load the app in a browser and evaluate something to be attached.
```

Evaluating again re-arms it, so loading the page and typing anything is enough —
you don't have to reconnect. Sessions created without a `clone` (nREPL's
throwaway per-message sessions) are left alone.

### One ClojureScript session per connection

Clients open more than one session on a connection — Cursive opens one for
JVM-side tooling *first* and the REPL you type in *second* — and attaching all of
them means duplicate `attached session` lines and a ClojureScript REPL where you
wanted a JVM one. So exactly one session per connection is auto-attached:
**`:attach-nth-session`, the second by default**, counting in the order the
sessions first send a message. The rest stay on the JVM, reported on the server
console only:

```
[auto-cljs] session 21f93f0e-… left on the JVM — its connection already has a
ClojureScript session. Evaluate (figwheel.main.api/cljs-repl "dev") in it to
attach it by hand.
```

A separate connection gets its own ClojureScript session, so two editors
connected at once both work. The claim is released when the owning session
closes, via the `:close` hook in the session's metadata, so reconnecting a REPL
hands the claim to the new session.

The choice is made when a session is **ready to attach**, not when it first
speaks — by then the client's other sessions have shown up and can be counted. If
the nth never arrives, the last session seen wins once the connection has been
open for `:session-grace-ms` (2s), which is what keeps single-session clients
(`lein repl :connect`, scripts) working:

| Sessions on the connection | Result |
|---|---|
| 1 | that one gets ClojureScript, after the grace period |
| 2 | 1st JVM, **2nd ClojureScript** |
| 3 | 1st JVM, **2nd ClojureScript**, 3rd JVM |

Set `:attach-nth-session 1` if your client puts the REPL first, or `nil` to
attach every session. Either way you can always move it by hand: `:cljs/quit` in
the session that got it, `(figwheel.main.api/cljs-repl "dev")` in the one you
want.

This is also why `wrap-auto-cljs` declares `:expects #{#'print/wrap-print …}`:
`wrap-print` swaps in a fresh transport for every message, and the connection
transport's identity is what distinguishes two sessions on one connection from
two sessions on two connections. The middleware has to run ahead of it.

### Getting out

`:cljs/quit` drops a session back to Clojure and it stays there — the middleware
attaches once per session, so it won't drag you back. Re-attach by hand with
`(figwheel.main.api/cljs-repl "dev")`.

Note that `wrap-auto-cljs` has to repair `:cljs/quit`. Piggieback clears its
session vars with a `swap!` on the session atom, but since nREPL 1.3 the session
executor pushes the whole session map as thread bindings around each evaluation
and merges `get-thread-bindings` back over the session afterwards, which restores
the repl-env piggieback just removed. Without the fix, `:cljs/quit` reports
success and leaves you in ClojureScript.

### Tuning

```clojure
(figintellij.auto-cljs/configure!
  {:enabled?            true
   :build-id            "dev"
   :quiet-ms            1000   ; silence required before handing off
   :attach-nth-session  2      ; which session on a connection gets it; nil = all
   :session-grace-ms    2000   ; before falling back to the last session seen
   :connect-timeout-ms 60000   ; how long to wait for a browser before giving up
   :attach-timeout-ms  60000   ; backstop on the hand-off itself
   :busy-ttl-ms        60000}) ; when an in-flight eval stops counting as in-flight
```

Set `:enabled? false` to turn auto-attach off and drive piggieback manually.
Raise `:quiet-ms` if your client's bootstrap has gaps longer than a second
between forms.

## Layout

```
deps.edn                      ; :nrepl, :fig, :min aliases
figwheel-main.edn             ; figwheel options; :mode :serve is merged in at boot
dev.cljs.edn                  ; the "dev" build
dev/figintellij/nrepl.clj     ; entry point: figwheel + nREPL + middleware wiring
dev/figintellij/auto_cljs.clj ; the middleware
src/figintellij/core.cljs     ; demo app
resources/public/index.html
target/public/.gitkeep        ; see below
```

`target/` is checked in (empty). It's on `:paths`, and if the directory doesn't
exist when the JVM starts, tools.deps drops it from the classpath and figwheel's
compiled output 404s until the next restart.

## Caveats

- Session ordering on a connection is the order they first send a message, which
  no client actually promises. The default of 2 matches Cursive; if your client
  differs, set `:attach-nth-session`. See above for how to move the REPL by hand.
- An interrupted evaluation's completion is reported by nREPL out of band, where
  the middleware can't see it, so an interrupt during the pre-attach window
  leaves that evaluation counted as in flight until `:busy-ttl-ms` expires. The
  effect is a delayed hand-off, nothing worse.
- Sessions share figwheel's repl-env, and piggieback tears the repl-env down when
  a session closes. The HTTP server survives (`figwheel.main.api/repl-env` sets
  `:prevent-server-tear-down`), but figwheel's print listener — which forwards
  *asynchronous* browser output, i.e. `println` outside an evaluation — is
  removed and not reinstalled. Output produced during an evaluation is unaffected.

## Versions

Clojure 1.12.5 · ClojureScript 1.12.145 · figwheel-main 0.2.20 · piggieback 0.7.0
· nREPL 1.7.0. Verified end to end on JDK 21: a Cursive-style bootstrap burst
staying in Clojure and attaching after the quiet period, a 3s evaluation not
being cut in half, connections of one/two/three sessions attaching exactly the
right one each time (and a second connection still getting its own),
`:attach-nth-session 1` flipping it, attaching a skipped session by hand, two
sessions sharing one browser and compiler env, `:cljs/quit` and manual re-attach,
the no-browser give-up and re-arm, and hot reload with `^:after-load`.
