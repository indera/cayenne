(ns cayenne.api.v1.facet)

(def std-facets
  {"type" {:external-field "type"}
   "year" {:external-field "published"}
   "publication" {:external-field "container-title"}
   "category" {:external-field "category"}
   "funder_name" {:external-field "funder-name"}
   "source" {:external-field "source"}
   "publisher" {:external-field "publisher"}})

(defn apply-facets [solr-query]
  (doto solr-query
    (.addFacetField (into-array String (keys std-facets)))
    (.setFacet true)
    (.setFacetLimit (int 10))))

(defn ->response-facet [solr-facet]
  (let [external-name (get-in std-facets [(.getName solr-facet) :external-field])]
    [external-name
     {:value-count (.getValueCount solr-facet)
      :values (->> (.getValues solr-facet)
                   (map #(vector (.getName %) (.getCount %)))
                   (into {}))}]))

(defn ->response-facets [solr-response]
  (into {} (map ->response-facet (.getFacetFields solr-response))))