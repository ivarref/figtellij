(ns figintellij.nrepl
  "nREPL server that boots a figwheel.main build and hands every session that
  connects a ClojureScript REPL on it.

  Usage: clojure -M:nrepl [build-id] [port]"
  (:require
   [cider.piggieback :as piggieback]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [figintellij.auto-cljs :as auto-cljs]
   [figwheel.main.api :as fig]
   [nrepl.server :as nrepl])
  (:gen-class))

(def default-build "dev")
(def default-port 7888)

(defn- figwheel-options
  "The contents of figwheel-main.edn, forced to `:mode :serve`.

  `(figwheel.main.api/start \"dev\")` defaults to `:mode :repl`, which starts a
  ClojureScript REPL on the calling thread. That's the right thing at a
  terminal, but here the REPL is per-nREPL-session, so we only want the server,
  the watcher and the build registry entry. Note that passing an options map to
  `start` replaces figwheel-main.edn rather than merging with it, which is why
  we read the file ourselves."
  []
  (let [f (io/file "figwheel-main.edn")]
    (assoc (if (.isFile f) (edn/read-string (slurp f)) {})
           :mode :serve)))

(defn -main [& [build port]]
  (let [build (or build default-build)
        port  (Integer/parseInt (str (or port (System/getenv "NREPL_PORT") default-port)))]
    (fig/start (figwheel-options) build)
    (auto-cljs/configure! {:build-id build})
    (let [server (nrepl/start-server
                  :port port
                  :handler (nrepl/default-handler
                            #'piggieback/wrap-cljs-repl
                            #'auto-cljs/wrap-auto-cljs))]
      (doto (io/file ".nrepl-port")
        (spit (:port server))
        (.deleteOnExit))
      (println (format "\nnREPL server on port %d — sessions auto-attach to figwheel build %s"
                       (:port server) (pr-str build)))
      @(promise))))
