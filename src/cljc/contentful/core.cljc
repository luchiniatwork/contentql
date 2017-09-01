(ns contentful.core
  (:require [om.next :as om]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]
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
;; Image transformation functions
;; ------------------------------

(defn ^:private calc-by-target-width
  "Calculates the potential width and height from an anchor on the target width."
  [t-width o-width o-height]
  (when (not (nil? t-width))
    {:width t-width
     :height (int (/ o-height (/ o-width t-width)))}))

(defn ^:private calc-by-target-height
  "Calculates the potential width and height from an anchor on the target height."
  [t-height o-width o-height]
  (when (not (nil? t-height))
    {:width (int (/ o-width (/ o-height t-height)))
     :height t-height}))

(defn ^:private real-resolution
  "The real resolution is a function of the combined minimum of the target width and height."
  [t-width t-height o-width o-height]
  (let [tmp-1 (calc-by-target-width t-width o-width o-height)
        tmp-2 (calc-by-target-height t-height o-width o-height)
        r-height (min (:height tmp-1) (:height tmp-2))
        r-width (min (:width tmp-1) (:width tmp-2))]
    {:height r-height
     :width r-width}))

(defn ^:private parse-image-params
  "Assoc the real dimensions to an image object. It depends on attributes :url
  :width and :height from the input parameter base-image-obj.

  The params height and width specify the intended height and width. The function will
  calculate the minimum resolution based out of any width or height and proportionally
  scale the other dimension.

  Height or width can be nil."
  [base-image-obj t-height t-width]
  (let [{:keys [url width height]} base-image-obj
        target-width (or t-width width)
        target-height (or t-height width)
        real-dimensions (real-resolution target-width target-height
                                         width height)
        real-width (:width real-dimensions)
        real-height (:height real-dimensions)]
    (assoc base-image-obj
           :url (str url "?w=" real-width "&h=" real-height)
           :width real-width
           :height real-height)))

(defn ^:private transform-image
  "Receives a basic raw Contentful asset representation and a series of linked assets from
  the linked Contentful response and returns a map with `:width`, `:height` and `:url` for
  the image."
  [raw linked-assets]
  (let [asset (get linked-assets (-> raw :sys :id))
        file (-> asset :fields :file)
        image (-> file :details :image)
        {:keys [width height]} image]
    {:width width
     :height height
     :url (str "https:" (:url file))}))

;; ------------------------------
;; Core transformation functions
;; ------------------------------

(declare ^:private transform)

(defn ^:private reduce-nested-string?
  "Helper function for the reducer function that tests for nested string values."
  [node]
  (and (vector? node) (string? (first node))))

(defn ^:private reduce-collection?
  "Helper function for the reducer function that tests for nested collections."
  [node]
  (and (coll? node) (:sys (first node))))

(defn ^:private match-linked-entries
  "Returns a vector with the linked-entries that match the ids of the provided dataset."
  [base-coll linked-entries]
  (remove nil? (mapv #(get linked-entries (-> % :sys :id))
                     base-coll)))

(defn ^:private reducer
  "This reducer is te core of the recursive transformation. It uses the linked entries
  and linked assets provided as part of the partial injection to create the map tree."
  [{:keys [linked-entries linked-assets] :as options} accum k v]
  (let [new-key (->kebab-case-keyword (name k))]
    (assoc accum new-key
           (cond
             (map? v) (transform-image v linked-assets)
             (reduce-collection? v) (transform (assoc options
                                                      :entries
                                                      (match-linked-entries v linked-entries)))
             (reduce-nested-string? v) (first v)
             :else v))))

(defn ^:private transform-one
  "Returns a map representing one entry of a dataset. See `transform` for more details."
  [entry {:keys [linked-entries linked-assets] :as options}]
  (when-not (nil? entry)
    (merge {:id (-> entry :sys :id)}
           (reduce-kv (partial reducer options)
                      {}
                      (:fields entry)))))

(defn ^:private transform
  "Returns a vector with the transformed entries. The input is a map containing
  the raw `:entries` as well as maps for `:linked-entries` and `:linked-assets`.

  These are used to traverse and build the tree of relationships one would expect."
  [{:keys [entries linked-entries linked-assets]
    :as options}]
  (mapv #(transform-one % options) entries))

(defn ^:private linked-items->map
  "Transforms a collection of linked Contentful items into a map keyed by the
  Contentful id of the item - found at `(-> % :sys :id)`."
  [linked-items]
  (reduce #(assoc %1 (-> %2 :sys :id) %2)
          {}
          linked-items))


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


(defn ^:private build-entities-url
  "Builds an entries request URL for Contentful."
  [{:keys [entries-url]} content-type {:keys [params select]}]
  (str entries-url
       "&content_type=" (name content-type)
       "&include=10"
       (select->url-params select)
       (params->url-params params)))

(defn ^:private break-payload
  "Organizes the entries, linked entries and linked assets from Contentful a bit better."
  [raw]
  {:entries (:items raw)
   :linked-entries (linked-items->map
                    (-> raw :includes :Entry))
   :linked-assets (linked-items->map
                   (-> raw :includes :Asset))})

(defn ^:private get-entities
  "Receives the connection and a content-type id, creates the url to fetch, fetches and
  then returns a sligtly digested data structure with the payload.
  
  The optional last parameter is a map with:

  `:select` - a collection of field names to be used when fetching this content-type

  `:params` - any parameters sent to this fetching of this content-type"
  [conn content-type & opts]
  (let [url (build-entities-url conn content-type (first opts))]
    (transform (-> url
                   get-json
                   break-payload))))

;; ------------------------------
;; Query filtering functions
;; ------------------------------

(defn ^:private resolver
  "This resolver is called if params are found during the filtering process.
  It receives an environment, a key, a map of parameters and the object already processed
  by the filtering system.

  Whatever this resolver returns will be used as the object returned to the user.

  The current implementation only deals with the params `:width` and `:height` for
  responsive images."
  [env k params res]
  (let [{:keys [height width]} params]
    (if (or height width)
      (parse-image-params res height width)
      res)))

(declare ^:private filter-query)

(defn ^:private query-reducer
  "Main reducer for the query filtering. See `filter-query` and `filter-entry` for more
  details."
  [entry m {:keys [type dispatch-key children] :as ast}]
  (let [v (get entry dispatch-key)
        res (cond
              (= type :prop) v
              (= type :join) (filter-query v ast))]
    (assoc m dispatch-key res)))

(defn ^:private filter-entry
  "Filters a single entry based on the provided query (here represented as an AST)."
  [entry {:keys [children params dispatch-key] :as ast}]
  (let [res (if params
              (resolver {:ast ast} dispatch-key params entry)
              entry)]
    (reduce (partial query-reducer res) {} children)))

(defn ^:private filter-entries
  "See `filter-query` and `filter-entry` for more details."
  [entries ast]
  (mapv #(filter-entry % ast) entries))

(defn ^:private filter-query
  "Filters out the things not required by the user by the query. Receives a digested collection
  of entries and the query's AST and returns a purified version of the dataset."
  [entries ast]
  (if (map? entries)
    (filter-entry entries ast)
    (filter-entries entries ast)))

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
    (reduce (fn [m {:keys [dispatch-key children params] :as sub-ast}]
              (let [opts {:select (ast-select->select children)
                          :params (ast-params->params params)}]
                (assoc m dispatch-key (-> (get-entities conn dispatch-key opts)
                                          (filter-query sub-ast)))))
            {}
            (:children ast))))
