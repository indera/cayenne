(ns cayenne.tasks.funder
  (:require [somnium.congomongo :as m]
            [clojure.core.memoize :as memoize]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [cayenne.item-tree :as itree]
            [cayenne.rdf :as rdf]
            [cayenne.conf :as conf]
            [cayenne.util :as util]
            [cayenne.ids.fundref :as fundref]
            [cayenne.tasks.geoname :as geoname]))

(defn ensure-funder-indexes! [collection-name]
  (m/with-mongo (conf/get-service :mongo)
    (m/add-index! collection-name [:level])
    (m/add-index! collection-name [:name_tokens])
    (m/add-index! collection-name [:id])
    (m/add-index! collection-name [:uri])
    (m/add-index! collection-name [:parent])
    (m/add-index! collection-name [:children])
    (m/add-index! collection-name [:affiliated])))

(defn add-tokens [existing tokens]
  (let [existing-tokens (or (:name_tokens existing) [])]
    (assoc existing :name_tokens (set (concat existing-tokens tokens)))))

(defn add-name [existing name name-type]
  (if (= name-type :primary)
    (-> existing
        (assoc :primary_name_display (.trim name))
        (add-tokens (util/tokenize-name name)))
    (let [other-names (or (:other_names existing) [])
          other-names-display (or (:other_names_display existing) [])]
      (-> existing
          (assoc :other_names_display (conj other-names-display (.trim name)))
          (add-tokens (util/tokenize-name name))))))

(defn insert-funder [id name name-type]
  (m/with-mongo (conf/get-service :mongo)
    (if-let [existing-doc (m/fetch-one :funders :where {:id id})]
      (m/update! :funders existing-doc (add-name existing-doc name name-type))
      (m/insert! :funders (add-name {:id id :uri (fundref/id-to-doi-uri id)} name name-type)))))

(defn insert-full-funder [id name country alt-names parent-id child-ids
                          affiliation-ids replaced-by-ids replaces-ids]
  (if (nil? name)
    (prn "no pref label for " id)
    (m/with-mongo (conf/get-service :mongo)
      (m/insert! :fundersloading
                 {:id id
                  :uri (fundref/id-to-doi-uri id)
                  :country country
                  :primary_name_display name
                  :other_names_display alt-names
                  :name_tokens (concat (util/tokenize-name name)
                                       (mapcat util/tokenize-name alt-names))
                                        ; hack here since funders can appear as their own parent in
                                        ; the registry. bug in the registry.
                  :parent (if (not= parent-id id) parent-id nil)
                  :children (or child-ids [])
                  :replaces (or replaces-ids [])
                  :replaced-by (or replaced-by-ids [])
                  :affiliated (or affiliation-ids [])}))))
  
(defn load-funders-csv []
  (ensure-funder-indexes! :funders)
  (with-open [rdr (io/reader (conf/get-resource :funders))]
    (let [funders (csv/read-csv rdr :separator \tab)]
      (doseq [funder funders]
        (let [name-type (if (= (nth funder 2) "N") :primary :other)]
          (insert-funder (first funder)
                         (second funder)
                         name-type))))))

(defn find-funders [model]
  (-> (rdf/select model
                  :predicate (rdf/rdf model "type")
                  :object (rdf/skos-type model "Concept"))
      (rdf/subjects)))

(defn res->id [funder-concept-node]
  (when funder-concept-node
    (last (string/split (rdf/->uri funder-concept-node) #"/"))))

(def svf-el (partial rdf/get-property "http://www.elsevier.com/xml/schema/grant/grant-1.2/"))
(def svf-cr (partial rdf/get-property "http://data.fundref.org/xml/schema/grant/grant-1.2/"))

(defn get-labels [model node kind]
  (->> (rdf/select model :subject node :predicate (rdf/skos-xl model kind))
       (rdf/objects)
       (mapcat #(rdf/select model :subject % :predicate (rdf/skos-xl model "literalForm")))
       (rdf/objects)
       (map #(.getString %))))

(defn select-country-stmts [model node]
  (concat
   (rdf/select model 
               :subject node
               :predicate (svf-el model "country"))
   (rdf/select model
               :subject node
               :predicate (svf-cr model "country"))))

(defn select-affil-with-stmts [model node]
  (concat
   (rdf/select model
               :subject node
               :predicate (svf-el model "affilWith"))
   (rdf/select model
               :subject node
               :predicate (svf-cr model "affilWith"))))

(defn get-country-literal-name [model node]
  (let [country-obj (-> (select-country-stmts model node)
                        (rdf/objects)
                        (first))]
    (if (or (nil? country-obj) 
            (= (rdf/->uri country-obj) 
               "http://sws.geonames.org//"))
      (do 
        (prn "Found node with no country: " node)
        "Unknown")
      (-> country-obj
       (rdf/->uri)
       (str "about.rdf")
       (geoname/get-geoname-name-memo)))))
      
(defn funder-concept->map [model funder-concept-node]
  {:id (res->id funder-concept-node)
   :country (get-country-literal-name model funder-concept-node)
   :broader-id (-> (rdf/select model
                               :subject funder-concept-node
                               :predicate (rdf/skos model "broader"))
                   (rdf/objects)
                   (first)
                   (res->id))
   :narrower-ids (->> (rdf/select model
                                  :subject funder-concept-node
                                  :predicate (rdf/skos model "narrower"))
                      (rdf/objects)
                      (map res->id))
   :affiliated-ids (->> (select-affil-with-stmts model funder-concept-node)
                        (rdf/objects)
                        (map res->id))
   :replaced-by-ids (->> (rdf/select model
                                     :subject funder-concept-node
                                     :predicate (rdf/dct model "isReplacedBy"))
                         (rdf/objects)
                         (map res->id))
   :replaces-ids (->> (rdf/select model
                                  :subject funder-concept-node
                                  :predicate (rdf/dct model "replaces"))
                      (rdf/objects)
                      (map res->id))
   :name (first (get-labels model funder-concept-node "prefLabel"))
   :alternative-names (get-labels model funder-concept-node "altLabel")})

(declare get-funder-ancestors-memo)
(declare get-funder-children-memo)
(declare get-funder-descendants-memo)
(declare get-funder-primary-name-memo)

(defn has-children? [collection-name id]
  (not (empty? (get-funder-children-memo collection-name id))))

(defn add-nesting [collection-name nesting path]
  (let [leaf (last path)
        children (get-funder-children-memo collection-name leaf)
        with-path (assoc-in nesting path {})]
    (reduce
     #(assoc-in %1 
                (conj (vec path) %2)
                (if (has-children? collection-name %2)
                  {:more true}
                  {}))
     with-path
     children)))

(defn build-nestings [collection-name]
  (m/with-mongo (conf/get-service :mongo)
    (doseq [record (m/fetch collection-name)]
      (let [id (:id record)
            lineage (reverse (cons id (get-funder-ancestors-memo collection-name id)))
            paths (util/patherize lineage)
            nesting (reduce
                     #(add-nesting collection-name %1 %2)
                     {}
                     paths)
            descendants (get-funder-descendants-memo collection-name id)
            descendant-names (into
                              {}
                              (map
                               #(vector % (get-funder-primary-name-memo collection-name %))
                               descendants))
            nesting-names (into 
                           {}
                           (map 
                            #(vector % (get-funder-primary-name-memo collection-name %))
                            (util/keys-in nesting)))]
        (m/update! 
         collection-name 
         {:id id} 
         {"$set" {:descendants descendants
                  :descendant_names descendant-names
                  :level (count lineage)
                  :nesting nesting
                  :nesting_names nesting-names}})))))

(defn load-funders-rdf [rdf-file]
  (ensure-funder-indexes! :fundersloading)
  (let [model (rdf/document->model rdf-file)]
    (doall
     (->> (find-funders model)
          (map (partial funder-concept->map model))
          (map #(insert-full-funder
                 (:id %)
                 (:name %)
                 (:country %)
                 (:alternative-names %)
                 (:broader-id %)
                 (:narrower-ids %)
                 (:affiliated-ids %)
                 (:replaced-by-ids %)
                 (:replaces-ids %))))))
  (build-nestings :fundersloading))

(defn rdf->funder-names [rdf-file]
  (let [model (rdf/document->model rdf-file)
        funders (find-funders model)]
    (map #(first (get-labels %1 %2 "prefLabel")) (repeat model) funders)))

(defn rdf->funder-ids [rdf-file]
  (->> rdf-file
       (rdf/document->model)
       (find-funders)
       (map res->id)))

(defn diff-funders-rdf-names
  "Returns a list of funder names found in the new RDF file but not in the old
   RDF file."
  [old-rdf-file new-rdf-file]
  (let [old-funder-names (set (rdf->funder-names old-rdf-file))
        new-funder-names (set (rdf->funder-names new-rdf-file))]
    (clojure.set/difference new-funder-names old-funder-names)))

(defn diff-funders-rdf-ids
  "Returns a list of funder IDs found in the new RDF file but not in the old
   RDF file."
  [old-rdf-file new-rdf-file]
  (let [old-funder-ids (set (rdf->funder-ids old-rdf-file))
        new-funder-ids (set (rdf->funder-ids new-rdf-file))]
    (clojure.set/difference new-funder-ids old-funder-ids)))

(defn stat-funders-rdf
  "Generate some statistics on funder concepts in RDF."
  [rdf-file]
  (let [concept-ids (rdf->funder-ids rdf-file)]
    {:concepts (count concept-ids)
     :unique-ids (count (set concept-ids))}))

(defn get-funder-names [funder-uri]
  (m/with-mongo (conf/get-service :mongo)
    (let [funder (m/fetch-one :funders :where {:uri funder-uri})]
      (conj (or (:other_names_display funder) []) 
            (:primary_name_display funder)))))

(defn get-funder-primary-name 
  ([funder-uri]
     (m/with-mongo (conf/get-service :mongo)
       (:primary_name_display (m/fetch-one :funders :where {:uri funder-uri}))))
  ([collection-name id]
     (m/with-mongo (conf/get-service :mongo)
       (:primary_name_display (m/fetch-one collection-name :where {:id id})))))

(defn get-funder-ancestors [collection-name id]
  (m/with-mongo (conf/get-service :mongo)
    (when-let [parent-id (:parent (m/fetch-one collection-name :where {:id id}))]
      (cons parent-id
            (lazy-seq (get-funder-ancestors collection-name parent-id))))))

(defn get-funder-siblings [collection-name id]
  (m/with-mongo (conf/get-service :mongo)
    (let [parent-id (:parent (m/fetch-one collection-name :where {:id id}))]
      (map :id (m/fetch collection-name :where {:parent parent-id})))))
  ;; todo what about affiliated?

(defn get-funder-children [collection-name id]
  (m/with-mongo (conf/get-service :mongo)
    (map :id (m/fetch collection-name :where {:parent id}))))

(def get-funder-children-memo (memoize/memo-lru get-funder-children))
(def get-funder-siblings-memo (memoize/memo-lru get-funder-siblings))
(def get-funder-ancestors-memo (memoize/memo-lru get-funder-ancestors))
(def get-funder-names-memo (memoize/memo-lru get-funder-names))
(def get-funder-primary-name-memo (memoize/memo-lru get-funder-primary-name))

(defn get-funder-descendants [collection-name id]
  (let [children (get-funder-children-memo collection-name id)]
    (concat
     children
     (mapcat
      (partial get-funder-children-memo collection-name)
      children))))

(def get-funder-descendants-memo (memoize/memo-lru get-funder-descendants))

(defn clear! []
  (memoize/memo-clear! get-funder-descendants)
  (memoize/memo-clear! get-funder-children-memo)
  (memoize/memo-clear! get-funder-siblings-memo)
  (memoize/memo-clear! get-funder-ancestors-memo)
  (memoize/memo-clear! get-funder-primary-name-memo)
  (memoize/memo-clear! get-funder-names-memo))

(defn canonicalize-funder-name
  [funder-item]
  (let [funder-uri (first (:id funder-item))]
    (if-let [canonical-name (get-funder-primary-name-memo funder-uri)]
      (merge funder-item {:name canonical-name :canonical true})
      funder-item)))

(defn drop-loading-collection []
  (m/with-mongo (conf/get-service :mongo)
    (m/drop-coll! :funderslast)
    (m/drop-coll! :fundersloading)))

(defn swapin-loading-collection
  []
  (let [db-api-layer (:db (conf/get-service :mongo))
        current-collection (.getCollection db-api-layer "funders")
        new-collection (.getCollection db-api-layer "fundersloading")]
    (.rename current-collection "funderslast")
    (.rename new-collection "funders")))

(defn apply-to 
  "If a funder specifies an ID, replace its publisher-provided name with our
   canonical primary name."
  ([item]
     (itree/update-tree-rel canonicalize-funder-name item :funder))
  ([id item]
     [id (apply-to item)]))
