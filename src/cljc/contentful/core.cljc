(ns contentful.core
  (:require [om.next :as om]
            [cheshire.core :as json]
            [clj-http.client :as http]))

;; ------------------------------
;; URL field transformations
;; ------------------------------

(defn ^:private field-name->url
  "Receives a field name as provided by the query and converts it into what
  Contentful accepts as a field parameters on URL requests."
  [query-name]
  (let [s (name query-name)]
    (if (= "id" s)
      (str "sys." s)
      (str "fields." s))))

(defn ^:private ast-params->params
  "Receives the AST parameters and cleans them up for internal consumption."
  [params]
  params)

(defn ^:private ast-select->select
  "Receives the AST selection fields and cleans them up for internal consumption."
  [children]
  (if children
    (reduce (fn [a {:keys [type dispatch-key]}]
              (if (= :prop type)
                (conj a dispatch-key)))
            []
            children)))

(defn ^:private params->url-params
  "Transforms internal params into URL-ready params for Contentful requests."
  [params]
  (if params
    (let [coll (->> params
                    (reduce-kv (fn [a k v]
                                 (conj a (str (field-name->url k) "=" v)))
                               [])
                    (interpose "&"))]
      (str "&" (apply str coll)))))

(defn ^:private select->url-params
  "Transforms internal field selects into URL-ready params for Contentful requests"
  [select]
  (if select
    (let [coll (->> select
                    (map field-name->url)
                    (interpose ","))]
      (str "&select=" (apply str coll)))))

;; ------------------------------
;; Fetching functions
;; ------------------------------

(defn ^:private get-json
  "Retrieves response body from Contentful's API response."
  [url]
  (-> url
      (http/get {:accept :json})
      :body
      (json/parse-string true)))

(defn ^:private match-linked-entries
  [base-coll linked-entries]
  (remove nil? (mapv #(get linked-entries (:id (:sys %)))
                     base-coll)))


(defn ^:private build-entities-url
  "Builds an entries request URL for Contentful."
  [{:keys [entries-url]} content-type {:keys [params select]}]
  (str entries-url
       "&content_type=" (name content-type)
       "&include=10"
       (select->url-params select)
       (params->url-params params)))

(defn ^:private get-entities
  [conn content-type & opts]
  (let [url (build-entities-url conn content-type (first opts))
        payload (get-json url)
        ;;entries (:items payload)
        ;;linked-entries (dictionary-linked-entries (:Entry (:includes payload)))
        ;;linked-assets (dictionary-linked-entries (:Asset (:includes payload)))
        ]
    #_(transform entries {:entity-type entity-type
                          :linked-entries linked-entries
                          :linked-assets linked-assets})
    (clojure.pprint/pprint url)
    (clojure.pprint/pprint payload)))

;; ------------------------------
;; Public functions
;; ------------------------------

(defn create-connection
  "Config is `{:space-id \"xxx\" :access-token \"xxx\" :mode :live}`
  `:mode` can be `:live` or `:preview`"
  [{:keys [space-id access-token mode]}]
  (let [base-url (if (= mode :live)
                   "https://cdn.contentful.com"
                   "https://preview.contentful.com")
        space-url (str base-url
                       "/spaces/"
                       space-id)
        entries-url (str space-url
                         "/entries?access_token="
                         access-token)
        content-types-url (str space-url
                               "/content_types?access_token="
                               access-token)]
    {:base-url base-url
     :space-url space-url
     :entries-url entries-url
     :content-types-url content-types-url}))

(defn query
  [conn query]
  (let [ast (om/query->ast query)]
    (clojure.pprint/pprint ast)
    (reduce (fn [a {:keys [dispatch-key children params]}]
              (let [opts {:select (ast-select->select children)
                          :params (ast-params->params params)}]
                (println opts)
                (assoc a dispatch-key (get-entities conn dispatch-key opts)))
              )
            {}
            (:children ast))))







;; (def ^:private get-json
;;   "Retrieve response body from Contentful's API response"
;;   [url]
;;   (-> url
;;       (http/get {:accept :json})
;;       :body
;;       (json/parse-string true)))

;; (def ^:private get-contentful-map
;;   ([url-func] (get-json (url-func)))
;;   ([url-func & args] (get-json (apply url-func args))))

;; (def ^:private match-linked-entries
;;   [base-coll linked-entries]
;;   (remove nil? (mapv #(get linked-entries (:id (:sys %)))
;;                      base-coll)))

;; (def ^:private qualify-keyword
;;   [x]
;;   (keyword x x))

;; (def ^:private extract-entity-type
;;   [entry]
;;   (when-not (nil? entry)
;;     (-> entry
;;         :sys
;;         :contentType
;;         :sys
;;         :id
;;         ->kebab-case-string
;;         qualify-keyword)))



;; (declare ^:private transform)

;; (def ^:private transform-image
;;   [field-key raw linked-assets]
;;   (let [asset (get linked-assets (:id (:sys raw)))
;;         file (:file (:fields asset))]
;;     {:image/original-width (:width (:image (:details file)))
;;      :image/original-height (:height (:image (:details file)))
;;      :image/original-url (str "https:" (:url file))}))

;; (def ^:private reduce-nested-string?
;;   "Helper function for the reducer function that tests for nested string values"
;;   [node]
;;   (and (vector? node) (string? (first node))))

;; (def ^:private reduce-collection?
;;   "Helper function for the reducer function that tests for nested collections"
;;   [node]
;;   (and (coll? node) (:sys (first node))))

;; (def ^:private reducer
;;   [{:keys [entity-type linked-entries linked-assets] :as options} accum k v]
;;   (let [new-key (keyword (name entity-type)
;;                          (->kebab-case-string (name k)))]
;;     (assoc accum new-key
;;            (cond
;;              (map? v) (transform-image new-key v linked-assets)
;;              (reduce-collection? v) (transform (match-linked-entries v linked-entries) options)
;;              (reduce-nested-string? v) (first v)
;;              :else v))))

;; (def ^:private transform-one
;;   [entry {:keys [entity-type linked-entries linked-assets] :as options}]
;;   (when-not (nil? entry)
;;     (merge {:db/id (:id (:sys entry))}
;;            (reduce-kv (partial reducer
;;                                (assoc options :entity-type (extract-entity-type entry)))
;;                       {}
;;                       (:fields entry)))))

;; (def ^:private transform
;;   [entries {:keys [entity-type linked-entries linked-assets] :as options}]
;;   (mapv #(transform-one % options) entries))

;; (def ^:private dictionary-linked-entries
;;   [linked-entries]
;;   (reduce #(assoc %1 (:id (:sys %2)) %2)
;;           {}
;;           linked-entries))

;; (defn get-all-of
;;   [entity-type]
;;   (let [payload (get-contentful-map entries-url (name entity-type))
;;         entries (:items payload)
;;         linked-entries (dictionary-linked-entries (:Entry (:includes payload)))
;;         linked-assets (dictionary-linked-entries (:Asset (:includes payload)))]
;;     (transform entries {:entity-type entity-type
;;                         :linked-entries linked-entries
;;                         :linked-assets linked-assets})))


;;   [entry-map]
;;   (let [entry (first (:items entry-map))
;;         linked-entries (dictionary-linked-entries (:Entry (:includes entry-map)))
;;         linked-assets (dictionary-linked-entries (:Asset (:includes entry-map)))]
;;     (transform-one entry {:entity-type (extract-entity-type entry)
;;                           :linked-entries linked-entries
;;                           :linked-assets linked-assets})))




;; (defn get-one-of
;;   [entry-id]
;;   (reformat-map (get-contentful-map entry-url entry-id)))

;; (defn cities
;;   []
;;   (get-all-of :city-node/city-node))

;; (defn stores
;;   []
;;   (get-all-of :store-node/store-node))

;; (defn customer
;;   [id]
;;   (get-one-of id))

;; (defn customer-by-email [email]
;;   (first
;;    (filter
;;     #(= (:customer-node/email %) email)
;;     (get-all-of :customer-node/customer-node))))

;; (defn products
;;   [store-id]
;;   (get-all-of :product-node/product-node))







;; (defn management-retrieve-entry
;;   [entry-id]
;;   (get-contentful-map management-entry-url entry-id))

;; (defn localize-map
;;   "Visit all v values in the given coll map and replace them by {:en-US v}."
;;   [coll]
;;   (reduce-kv (fn [m k v] (assoc m k {:en-US v})) {} coll))

;; (defn update-entry-from-map
;;   [target-entry-map new-pairs-map]
;;   (reduce-kv (fn [m k v] (spctr/setval [:fields k] {"en-US" v} m))
;;              target-entry-map
;;              new-pairs-map))
