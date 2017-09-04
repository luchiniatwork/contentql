(ns cljs.user
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [contentql.core :as contentql]
            [cljs.core.async :refer [<!]]
            [user-config :as config]))

(enable-console-print!)

(defn try-it []
  (let [conn (contentql/create-connection config/conn-config)]
    (go (println (<! (contentql/query conn config/q2))))))
