(ns contentql.core
  #?(:cljs
     (:require-macros [cljs.core.async.macros :refer [go]]))
  (:require [om.next.impl.parser :as parser]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [clojure.string :as string]
            #?@(:clj
                [[clj-http.client :as http]
                 [clojure.core.async :refer [<! >! chan go]]
                 [cheshire.core :as json]]
                :cljs
                [[cljs-http.client :as http]
                 [goog.math :as math]
                 [cljs.core.async :refer [<!] :as async]])))

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

(defn ^:private param-name->url
  "Receives a field name as provided by the query and converts it into what
  Contentful accepts as a query parameters on URL requests."
  [query-name]
  (let [s (name query-name)]
    (if (= "id" s)
      (str "sys." s)
      s)))

(defn ^:private ast-params->params
  "Receives the AST parameters and cleans them up for internal consumption."
  [params]
  params)

(defn ^:private ast-select->select
  "Receives the AST selection fields and cleans them up for internal consumption."
  [children]
  (if children
    (reduce (fn [a {:keys [type dispatch-key]}]
              (if (or (= :prop type) (= :join type))
                (conj a dispatch-key)))
            []
            children)))

(defn ^:private params->url-params
  "Transforms internal params into URL-ready params for Contentful requests."
  [params]
  (if params
    (let [coll (->> params
                    (reduce-kv (fn [a k v]
                                 (conj a (str (param-name->url k) "=" v)))
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
        fields (-> asset :fields)
        title (-> fields :title)
        description (-> fields :description)
        file (-> fields :file)
        image (-> file :details :image)
        contentType (-> file :contentType)
        {:keys [width height]} image]
    {:title title
     :description description
     :content-type contentType
     :width width
     :height height
     :url (if (string? (:url file))
            (str "https:" (:url file))
            nil)}))

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

(defn ^:private reduce-map-asset?
  "Helper function for the reducer function that tests if the given map is of type asset"
  [node]
  (and (map? node) (= (-> node
                          :sys
                          :linkType
                          string/lower-case) "asset")))

(defn ^:private reduce-map-not-asset?
  "Helper function for the reducer function that tests for a map that is not an asset"
  [node]
  (and (map? node) (not (= (-> node
                               :sys
                               :linkType
                               string/lower-case) "asset"))))

(defn ^:private match-linked-entries
  "Returns a vector with the linked-entries that match the ids of the provided dataset."
  [base-coll linked-entries]
  (remove nil? (mapv #(get linked-entries (-> % :sys :id))
                     base-coll)))

(defn ^:private transform-collection
  "Returns a modified linked entries list"
  [node options linked-entries]
  (transform (assoc options
               :entries
               (match-linked-entries node linked-entries)
               :root false)))

(defn ^:private reducer
  "This reducer is te core of the recursive transformation. It uses the linked entries
  and linked assets provided as part of the partial injection to create the map tree."
  [{:keys [linked-entries linked-assets] :as options} accum k v]
  (let [new-key (->kebab-case-keyword (name k))]
    (assoc accum new-key
                 (cond
                   ; There are scenarios in which you can have a map that is not an Asset
                   ; (such as a single reference field).
                   ; These two functions (reduce-map-asset? & reduce-map-not-asset?)
                   ; will differentiate between the two and apply the right transformations.
                   (reduce-map-asset? v) (transform-image v linked-assets)

                   (reduce-map-not-asset? v) (let [vector-v (vector v)]
                                               (transform-collection vector-v options linked-entries))

                   (reduce-collection? v) (transform-collection v options linked-entries)

                   (reduce-nested-string? v) (first v)
                   :else v))))

(defn ^:private transform-one
  "Returns a map representing one entry of a dataset. See `transform` for more details."
  [entry options]
  (when-not (nil? entry)
    (merge {:id (-> entry :sys :id)
            :type-name (-> entry :sys :contentType :sys :id)}
           (reduce-kv (partial reducer options)
                      {}
                      (:fields entry)))))

(defn ^:private transform
  "Returns a vector with the transformed entries. The input is a map containing
  the raw `:entries` as well as maps for `:linked-entries` and `:linked-assets`.

  These are used to traverse and build the tree of relationships one would expect.

  The `:root` field indicates whether this node represents the root node of the
  response and the `:info` node carries pagination information."
  [{:keys [root info entries linked-entries linked-assets]
    :as options}]
  (let [res (mapv #(transform-one % options) entries)]
    (if root
      {:nodes res
       :info info}
      res)))

(defn ^:private linked-items->map
  "Transforms a collection of linked Contentful items into a map keyed by the
  Contentful id of the item - found at `(-> % :sys :id)`."
  [linked-items]
  (reduce #(assoc %1 (-> %2 :sys :id) %2)
          {}
          linked-items))

;; ------------------------------
;; Cross platform wrappers
;; ------------------------------
(defn ^:private ceil [n]
  #?(:clj  (Math/ceil n)
     :cljs (math/safeCeil n)))

(defn ^:private floor [n]
  #?(:clj  (Math/floor n)
     :cljs (math/safeFloor n)))

;; ------------------------------
;; Fetching functions
;; ------------------------------

(defn ^:private get-json
  "Retrieves response body from Contentful's API response."
  [url]
  #?(:cljs
     ;; This manual casting of the body is needed because Contentful returns content-type
     ;; application/vnd.contentful.delivery.v1+json instead of application/json and
     ;; cljs-http does not understand it as a json and, correctly, does not parse the body.
     ;; A more elegant approach would be to extend cljs-http to support a new wrapper for
     ;; this specifc content-type but this seemed like an overkill
     (go (let [res (<! (http/get url {:with-credentials? false}))]
           (as-> res x
             (:body x)
             (.parse js/JSON x)
             (js->clj x :keywordize-keys true))))
     :clj
     (let [c (chan)]
       (go (>! c (-> url
                     (http/get {:accept :json})
                     :body
                     (json/parse-string true))))
       c)))

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
  [{:keys [total skip limit items includes] :as raw}]
  (let [total-pages (int (ceil (/ total limit)))
        current-page (- total-pages (int (floor (/ (- total skip) limit))))
        has-next? (> total-pages current-page)
        has-prev? (> current-page 1)]
    {:root true
     :info {:nodes {:total total}
            :page {:size limit
                   :current current-page
                   :total total-pages
                   :has-next? has-next?
                   :has-prev? has-prev?}
            :pagination {:cursor skip
                         :next-skip (if has-next? (+ skip limit) skip)
                         :prev-skip (if has-prev? (- skip limit) skip)}}
     :entries items
     :linked-entries (linked-items->map (:Entry includes))
     :linked-assets (linked-items->map (:Asset includes))}))

(defn ^:private get-entities
  "Receives the connection and a content-type id, creates the url to fetch, fetches and
  then returns a slightly digested data structure with the payload.

  The optional last parameter is a map with:

  `:select` - a collection of field names to be used when fetching this content-type

  `:params` - any parameters sent to this fetching of this content-type"
  [conn content-type & opts]
  (let [url (build-entities-url conn content-type (first opts))]
    (go (let [res (<! (get-json url))]
          (transform (break-payload res))))))

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
    (if children
      (reduce (partial query-reducer res) {} children)
      res)))

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
  "Config is `{:space-id \"xxx\" :access-token \"xxx\" :mode :live :environment \"xxx\"}`
  `:mode` can be `:live` or `:preview`"
  [{:keys [space-id access-token environment mode]}]
  (let [base-url (if (= mode :live)
                   "https://cdn.contentful.com"
                   "https://preview.contentful.com")
        space-url (str base-url
                       "/spaces/"
                       space-id
                       "/environments/"
                       environment)
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
  (go (let [ast (parser/query->ast query)
            out (atom {})]
        (doseq [{:keys [dispatch-key children params] :as sub-ast} (:children ast)]
          (let [opts {:select (ast-select->select children)
                      :params (ast-params->params params)}
                entities (<! (get-entities conn dispatch-key opts))]
            (swap! out assoc dispatch-key {:nodes (-> entities :nodes (filter-query sub-ast))
                                           :info (:info entities)})))
        @out)))
