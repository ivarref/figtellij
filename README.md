# figtellij

> Because life is too short to type `(do (require 'figwheel.main.api) (figwheel.main.api/start "dev"))` in a REPL.

Yep, that's the full purpose of this project.
This project specifically
targets [Cursive](https://cursive-ide.com/), hence the name.

### What

You want to start figwheel building with a single command. And then you _might_
want to REPL directly into ClojureScript land.

This project does exactly that. No convoluted commands to remember.

[//]: # (: starts a figwheel build and automatically)
[//]: # (attaches incoming REPL connections to ClojureScript.)

### Defaults

Default profile for figwheel: `dev`.

Default nREPL port: `7888`.

### How

An easier way to jumpstart figwheel and optionally Cursive.


## Installation

TBD

## Use

TBD

### Vibed?

Yes, I vibecoded this.

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

- **At boot**, `figtellij.nrepl` starts the build with `:mode :serve`. Plain
  `(start "dev")` defaults to `:mode :repl`, which starts a ClojureScript REPL on
  the calling thread — fine at a terminal, but it would make the build's lifetime
  depend on one privileged session, and `stop` / `repl-env` / `cljs-repl` are only
  usable when you start non-blocking. So the boot only brings up the server,
  watcher and build registry entry.

- **Per session**, `figtellij.auto-cljs` runs `(figwheel.main.api/cljs-repl "dev")`
  once the session has gone quiet (see below). That is the same call the terminal
  REPL ends up making: `figwheel.main/repl` detects nREPL and delegates to
  `cider.piggieback/cljs-repl`, passing the build's repl-env **and its
  `:compiler-env`**. So sessions aren't merely each talking to a browser — they
  share one browser and one compiler env:

  ```clojure
  ;; session A
  cljs.user=> (swap! figtellij.core/app-state assoc :written-by :session-a)
  ;; session B
  cljs.user=> @figtellij.core/app-state
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

### Looking like a real ClojureScript REPL

nREPL carries no signal for "this session changed language", so a client that
recognises a ClojureScript REPL has to infer it from something. Two things are
sent to help, both configurable.

**The banner** (`:announce-banner?`, on). The session is given the output a
terminal figwheel REPL prints when it starts — byte-for-byte identical to what
`clojure -M:fig` produces, verified by diffing the two:

```
[Figwheel] Starting REPL
Prompt will show when REPL connects to evaluation environment (i.e. a REPL hosting webpage)
Figwheel Main Controls:
          (figwheel.main/stop-builds id ...)  ;; stops Figwheel autobuilder for ids
          …
    Exit: :cljs/quit
 Results: Stored in vars *1, *2, *3, *e holds last exception object
ClojureScript 1.12.145
cljs.user=>
```

Most of that is the controls banner piggieback already prints during the
hand-off, which otherwise goes nowhere because the hand-off runs on a sink
transport. Around it: `[Figwheel] Starting REPL` before, and the two lines
`cljs.repl/repl*` would print as it takes over — the version and the
`cljs.user=> ` prompt — after. Piggieback's parting `To quit, type: :cljs/quit`
is stripped, since the terminal REPL doesn't print it.

With the banner on, the `; auto-cljs: attached session …` note stays on the
server console, so what the client sees is exactly the REPL output and nothing
else.

**The namespace** (`:announce-ns`) — **off**. An unsolicited response carrying
`:ns`, so a client that tracks the current namespace from responses could notice
the switch without waiting for you to evaluate something. Cursive ignored it, and
an unsolicited `:ns` is irregular enough not to send on the off chance, so it is
disabled; set `:announce-ns "cljs.user"` to turn it back on. When enabled it
looks like this on the wire, right after the attach:

```clojure
{:session "418e455f-…" :out "; auto-cljs: attached session … to figwheel build \"dev\"\n"}
{:session "418e455f-…" :ns  "cljs.user"}
```

The namespace is read out of piggieback's `cljs.analyzer/*cljs-ns*` in the
session rather than assumed; `:announce-ns` is only the fallback if that can't
be read, and `nil` turns the announcement off.

nREPL has no notion of an unsolicited namespace change — `:ns` normally only
rides along on a response to a request — so a client that only looks at replies
to its own request ids will ignore this and see the change on its next
evaluation instead. For those, `:announce-ns-id? true` additionally tags the
message with the id of the last message the session completed:

```clojure
{:id "b2" :session "8bed7a91-…" :ns "cljs.user"}
```

That is a response arriving after its request already got `:status :done`, which
is irregular enough to be off by default — try it if the plain announcement
isn't picked up.

Note that `*ns*` evaluates to `nil` in **any** ClojureScript REPL, including
`clojure -M:fig` — it only exists at compile time in ClojureScript — so it isn't
a signal a client can use to tell the two apart, and nothing here is going to
change that.

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

### `println` in the app, and unreadable browser messages

Any `println` in the ClojureScript app used to crash the server's message reader
on every hot reload, with ~200 lines of Jetty stack frames ending in
`Invalid number: 360c99d9-…`. It is a figwheel.main bug, and
`figtellij.print-fix` (a `:preloads` entry in dev.cljs.edn) works around it.

ClojureScript's `println` binds `*print-readably*` to false and calls
`*print-fn*` from **inside** that binding:

```clojure
(defn println [& objs]
  (binding [*print-readably* false]
    (pr-with-opts objs nil))
  ...)
```

Figwheel's print-fn forwards browser output to the server with
`figwheel.repl/respond-to`, which serialises the message with `pr-str`. Still
inside that binding, `pr-str` emits every string unquoted, so what arrives is not
EDN:

```
{:session-id 360c99d9-e05e-4170-abfd-d93901217950, :session-name Janina,
 :response {:output true, :stream :out, :args [figtellij loaded]}}
```

`receive-message!` fails on the first bare token — the session id — and dumps the
trace. The message is dropped, so if it was a reload response the reload hangs.

The fix re-binds `*print-readably*` around `respond-to`, the one choke point
every outgoing message passes through. Patching there rather than the
`out-print`/`err-print` methods matters: `hook-repl-printing-output!` redefines
those on every connection and would clobber a patch applied to them.

Verified by hammering reloads with a browser attached: 10 unreadable messages out
of 25 before, 0 out of 25 after. Nothing to do with the nREPL layer — it
reproduces under plain `figwheel.main` in `:mode :serve` with no nREPL at all.
Drop the namespace and the preload once figwheel.main fixes it upstream.

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
speaks — by then the client's other sessions have shown up and can be counted.

| Sessions on the connection | Result |
|---|---|
| 1 | stays on the JVM — nothing attaches |
| 2 | 1st JVM, **2nd ClojureScript** |
| 3 | 1st JVM, **2nd ClojureScript**, 3rd JVM |

Note the first row: `:session-grace-ms` is **off** (`nil`), so only the nth
session is ever attached and a connection that never opens one is left entirely
on the JVM. That's deliberate — it keeps the rule exact and predictable for
Cursive — but it means single-session clients (`lein repl :connect`, scripts)
don't get a ClojureScript REPL. Set `:session-grace-ms 2000` to bring back the
fallback: after that long, the last session seen on the connection wins if the
nth hasn't shown up.

A session that is waiting for the nth to arrive gives up after
`:connect-timeout-ms` and says so, which is the log line to look for if nothing
attaches:

```
[auto-cljs] session 1dd9cc10-… left on the JVM — it is not session #2 on its
connection, and that session never arrived.
```

**Ordering is by first message, not by `clone`.** nREPL's session middleware
handles `clone` itself and never delegates it, so a session becomes visible to
this middleware only when it first sends something. A session that is cloned but
stays silent doesn't count — with the grace period off, that's enough to mean
nothing attaches at all.

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
(figtellij.auto-cljs/configure!
  {:enabled?            true
   :build-id            "dev"
   :quiet-ms            1000   ; silence required before handing off
   :attach-nth-session  2      ; which session on a connection gets it; nil = all
   :session-grace-ms    nil    ; nil = off; ms before falling back to the last
                               ;   session seen when the nth never arrives
   :connect-timeout-ms 60000   ; how long to wait for a browser before giving up
   :attach-timeout-ms  60000   ; backstop on the hand-off itself
   :busy-ttl-ms        60000   ; when an in-flight eval stops counting as in-flight
   :announce-banner?   true    ; emit the terminal figwheel REPL banner + prompt
   :announce-ns        nil     ; off; set to "cljs.user" to also send an :ns message
   :announce-ns-id?    false}) ; also tag that report with the last message id
```

Set `:enabled? false` to turn auto-attach off and drive piggieback manually.
Raise `:quiet-ms` if your client's bootstrap has gaps longer than a second
between forms.

## Layout

```
deps.edn                      ; :nrepl, :fig, :min aliases
figwheel-main.edn             ; figwheel options; :mode :serve is merged in at boot
dev.cljs.edn                  ; the "dev" build
dev/figtellij/nrepl.clj     ; entry point: figwheel + nREPL + middleware wiring
dev/figtellij/auto_cljs.clj ; the middleware
src/figtellij/core.cljs     ; demo app
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
being cut in half, two- and three-session connections attaching exactly the
second one (and a second connection still getting its own), a single-session
connection staying on the JVM with the grace period off, a late-arriving second
session still winning, `:attach-nth-session 1` flipping it, attaching a skipped
session by hand, two sessions sharing one browser and compiler env, `:cljs/quit`
and manual re-attach, the no-browser give-up and re-arm, hot reload with
`^:after-load`, and — read off the raw wire — the startup banner diffed
byte-for-byte against `clojure -M:fig`, the unsolicited `:ns "cljs.user"`
announcement, its id-tagged variant, and their off switches.
