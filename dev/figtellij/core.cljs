;; `^:figwheel-hooks` is required for figwheel to notice the ^:after-load var
;; below — it only scans namespaces carrying that metadata.
(ns ^:figwheel-hooks figtellij.core)

(defonce app-state (atom {:reloads 0}))

(defn render! []
  (when-let [el (.getElementById js/document "app")]
    (set! (.-textContent el)
          (str "figtellij — hot reloads: " (:reloads @app-state)))))

(defn ^:after-load on-reload []
  (swap! app-state update :reloads inc)
  (render!))

(defn ^:export init []
  (println "figtellij loaded")
  (render!))

(init)
