(ns contentql.system
  (:require [com.stuartsierra.component :as component]
            [contentql.core :as contentql]))

(declare system)

(defn new-system []
  (component/system-map))

(defn init []
  (set! system (new-system)))

(defn start []
  (set! system (component/start system)))

(defn stop []
  (set! system (component/stop system)))

(defn ^:export go []
  (init)
  (start))

(defn reset []
  (stop)
  (go))
