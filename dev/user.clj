(ns user
  (:require [clojure.tools.namespace.repl :refer [set-refresh-dirs]]
            [com.stuartsierra.component :as component]
            [figwheel-sidecar.config :as fw-config]
            [figwheel-sidecar.repl-api :as figwheel]
            [figwheel-sidecar.system :as fw-sys]
            [reloaded.repl :refer [system init]]
            [contentql.core :as contentql]
            [clojure.core.async :as async]
            [user-config :as config]))

(defn dev-system []
  (component/system-map
   :figwheel-system (fw-sys/figwheel-system (fw-config/fetch-config))))

(set-refresh-dirs "src" "dev")
(reloaded.repl/set-init! #(dev-system))

(defn cljs-repl []
  (fw-sys/cljs-repl (:figwheel-system system)))

;; Set up aliases so they don't accidentally
;; get scrubbed from the namespace declaration
(def start reloaded.repl/start)
(def stop reloaded.repl/stop)
(def go reloaded.repl/go)
(def reset reloaded.repl/reset)
(def reset-all reloaded.repl/reset-all)


(defn try-it []
  (let [conn (contentql/create-connection config/conn-config)]
    (async/go (clojure.pprint/pprint (async/<! (contentql/query conn config/q1))))))
