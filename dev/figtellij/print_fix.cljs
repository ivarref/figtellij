(ns figtellij.print-fix
  "Works around a figwheel.main bug that makes every `println` in the app crash
  the server's message reader.

  ClojureScript's `println` binds `*print-readably*` to false and calls
  `*print-fn*` from inside that binding:

      (defn println [& objs]
        (binding [*print-readably* false]
          (pr-with-opts objs nil))
        ...)

  Figwheel's print-fn forwards the output to the server with
  `figwheel.repl/respond-to`, which serialises the whole message with `pr-str`.
  Still inside that binding, `pr-str` emits every string unquoted, so what
  reaches the server is not EDN:

      {:session-id 360c99d9-e05e-4170-abfd-d93901217950, :session-name Janina,
       :response {:output true, :stream :out, :args [figtellij loaded]}}

  `figwheel.repl/receive-message!` then fails on the first bare token — the
  session id — with `Invalid number: 360c99d9-…`, and pprints a 200-line stack
  trace. The message is dropped, so if it was a reload response the reload
  hangs.

  Re-binding `*print-readably*` around `respond-to` fixes it at the one choke
  point every outgoing message passes through, and survives
  `hook-repl-printing-output!` redefining its `out-print`/`err-print` methods on
  each connection, since those call `respond-to` too.

  Loaded as a `:preloads` entry in dev.cljs.edn. Drop this namespace, and the
  preload, once figwheel.main fixes it upstream."
  (:require
   [figwheel.repl]))

(defonce ^:private original-respond-to figwheel.repl/respond-to)

(set! figwheel.repl/respond-to
      (fn respond-to-readably [old-msg response-body]
        (binding [*print-readably* true]
          (original-respond-to old-msg response-body))))
