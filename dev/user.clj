(ns user
  (:require [contentful.core :as contentful]))


(def config {:space-id "c3tshf2weg8y"
             :access-token "e87aea51cfd9193df88f5a1d1b842d9a43cc4f2b02366b7c0ead54fb1b0ad6d4"
             :mode :live})

(defn go []
  (let [conn (contentful/create-connection config)]
    (contentful/query conn '[{:store-node [:id :name]}

                             ({:product-node [:id :name]}
                              {:id "3jQ8AiREnCAsOMqOiQ4QoA"})

                             ({:city-node [:id
                                           :name
                                           {:stores [:name
                                                     :address
                                                     ({:image [:width
                                                               :height
                                                               :url]}
                                                      {:width 100})]}]}
                              {:id "3x1YMtJ1CoOWk0ycYsOw4I"})])))
