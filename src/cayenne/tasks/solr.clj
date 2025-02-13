(ns cayenne.tasks.solr
  (:use cayenne.item-tree)
  (:import [org.apache.solr.common SolrInputDocument]
           [org.apache.solr.client.solrj.request CoreAdminRequest]
           [org.apache.solr.common.params CoreAdminParams$CoreAdminAction])
  (:require [clj-time.core :as t]
            [clojure.string :as string]
            [cayenne.conf :as conf]
            [cayenne.ids.doi :as doi]
            [cayenne.ids :as ids]
            [cayenne.util :as util]
            [metrics.gauges :refer [defgauge] :as gauge]
            [metrics.meters :refer [defmeter] :as meter]
            [taoensso.timbre :as timbre :refer [error info]]))

(def insert-list (atom []))

(def insert-count (agent 0))

(set-error-handler! 
 insert-count 
 (fn [agt ex] 
   (error (str "Solr agent failed:" ex))
   (restart-agent insert-count 0)))

(defmeter [cayenne solr insert-events] "insert-events")

(defgauge [cayenne solr inserts-so-far] @insert-count)

(defgauge [cayenne solr insert-waiting-list-size]
  (count @insert-list))

(defn flush-insert-list [c insert-list]
  (doseq [update-server (conf/get-service :solr-update-list)]
    (try
      (.add update-server insert-list)
      (.commit update-server false false)
      (meter/mark! insert-events)
      (catch Exception e (error e "Solr insert failed" update-server))))
  (inc c))

(defn add-to-insert-list [insert-list doc]
  (if (>= (count insert-list)
           (conf/get-param [:service :solr :insert-list-max-size]))
    (do
      (send-off insert-count flush-insert-list insert-list)
      [doc])
    (conj insert-list doc)))

(defn flush-and-clear-insert-list [insert-list]
  (when (not (zero? (count insert-list)))
    (send-off insert-count flush-insert-list insert-list))
  [])

(defn force-flush-insert-list []
   (swap! insert-list flush-and-clear-insert-list))

(defn get-categories [item]
  (if-let [journal (find-item-of-subtype item :journal)]
    (or (:category journal) [])
    []))

(defn particle->date-time [particle]
  (let [year (-> particle :year util/parse-int-safe)
        month (-> particle :month util/parse-int-safe)
        day (-> particle :day util/parse-int-safe)]
    (cond (and year month day)
          (if (< (t/number-of-days-in-the-month year month) day)
            (t/date-time year month)
            (t/date-time year month day))
          (and year month)
          (t/date-time year month)
          :else
          (t/date-time year))))

(defn get-earliest-pub-date [item]
  (->> (concat 
        (get-tree-rel item :published-print)
        (get-tree-rel item :published-online)
        (get-tree-rel item :published))
       (sort-by particle->date-time)
       first))

(defn get-print-or-earliest-pub-date [item]
  (or
   (first (get-tree-rel item :published-print))
   (get-earliest-pub-date item)))

(defn get-contributor-orcids [item]
  (let [contributors (mapcat #(get-item-rel item %) contributor-rels)]
    (filter (complement nil?) (mapcat :id contributors))))

(defn get-contributor-affiliations [item]
  (->> contributor-rels
       (mapcat #(get-item-rel item %))
       (mapcat #(get-item-rel % :affiliation))
       (map :name)))

(defn initials [first-name]
  (when first-name
    (string/join " " (map first (string/split first-name #"[\s\-]+")))))

(defn as-name [org-or-person]
  (cond (= :org (get-item-type org-or-person))
        (:name org-or-person)
        (= :person (get-item-type org-or-person))
        (str (:first-name org-or-person) " " (:last-name org-or-person))))

(defn as-initials [org-or-person]
  (cond (= :org (get-item-type org-or-person))
        (as-name org-or-person)
        (= :person (get-item-type org-or-person))
        (str (initials (:first-name org-or-person)) " " (:last-name org-or-person))))

(defn get-contributor-names
  "Contributor names as a concatenated string."
  [item type]
  (let [contributors (get-item-rel item type)]
    (string/join ", " (map as-name contributors))))

(defn as-details [contributor type]
  {:given-name (:first-name contributor)
   :family-name (:last-name contributor)
   :suffix (:suffix contributor)
   :affiliations (map :name (get-item-rel contributor :affiliation))
   :orcid (first (get-item-ids contributor :orcid))
   :type type})

(defn get-contributor-details*
  "For each person contributor, return a map of name, ORCID and
   type of contribution."
  [item type]
  (let [contributors (filter #(= (get-item-type %) :person) 
                             (get-item-rel item type))]
    (map as-details contributors (repeat type))))

(defn get-contributor-details [item]
  (concat
   (get-contributor-details* item :author)
   (get-contributor-details* item :chair)
   (get-contributor-details* item :editor)
   (get-contributor-details* item :translator)
   (get-contributor-details* item :contributor)))

(defn get-primary-author [item]
  (first (get-item-rel item :author)))

(defn get-contributors [item]
  (mapcat (partial get-item-rel item) contributor-rels)) ;; todo deal with orgs

(defn get-container-titles [item]
  (let [titles (mapcat #(get-item-rel % :title)
                       (get-item-rel item :ancestor))]
    (map :value titles)))

(defn get-oa-status [item]
  (let [journal (find-item-of-subtype item :journal)]
    (or (:oa-status journal) "Other")))

(defn get-update-policy [item]
  (when-let [policy (first (get-tree-rel item :update-policy))]
    (:value policy)))

(defn get-article-number [item]
  (->> (get-tree-rel item :number)
       (filter #(= "article-number" (:kind %)))
       (map :value)
       first))

(defn get-updates [item]
  (find-items-of-type item :update))

(defn get-assertion-names [item]
  (->> (find-items-of-type item :assertion)
       (filter :name)
       (map :name)
       set))

(defn get-assertion-group-names [item]
  (->> (find-items-of-type item :assertion)
       (filter :group-name)
       (map :group-name)
       set))

(defn as-solr-base-field [item]
  (string/join 
   " "
   (-> []
       (conj (:year (get-print-or-earliest-pub-date item))) ; year
       (conj (:issue (find-item-of-subtype item :journal-issue))) ; issue
       (conj (:volume (find-item-of-subtype item :journal-volume))) ; volume
       (conj (:first-page item)) ; pages
       (conj (:last-page item)) ; pages
       (concat (map :value (get-item-rel item :title))) ; work titles
       (concat (get-container-titles item))))) ; publication titles

(defn as-solr-citation-field [item]
  (string/join
   " "
   (-> [(as-solr-base-field item)]
       (concat (map as-initials (get-contributors item)))))) ; names with initials

(defn as-solr-content-field [item]
  (string/join
   " "
   (-> [(as-solr-base-field item)]
       (conj (:description item))
       (concat (map ids/extract-supplementary-id (get-tree-ids item :supplementary))) ; plain supp ids
       (concat (map as-name (get-contributors item))) ; full names
       (concat (mapcat get-item-ids (get-tree-rel item :awarded))) ; grant numbers
       (concat (map :name (get-tree-rel item :funder)))))) ; funder names

(defn as-grant-map [item]
  (letfn [(combine [memo nxt]
            (let [funder-name (:name nxt)
                  awards (or (get memo funder-name) [])
                  new-awards (mapcat :id (get-item-rel nxt :awarded))]
              (assoc memo funder-name (concat awards new-awards))))]
    (reduce combine {} (get-tree-rel item :funder))))

(defn as-license-list 
  "Returns all licenses within an item tree. If the license has no
   explicit start date it is assumed to have a start date equal to
   the preferred published date of the item."
  [item]
  (let [pub-date (get-earliest-pub-date item)
        licenses (get-tree-rel item :license)]
    (map #(if (:start %) % (assoc % :start pub-date))
         licenses)))

(defn as-datetime [particle-date]
  (let [converted-date {:year (util/parse-int-safe (:year particle-date))
                        :month (util/parse-int-safe (:month particle-date))
                        :day (util/parse-int-safe (:day particle-date))
                        :hour (util/parse-int-safe (:hour particle-date))
                        :minute (util/parse-int-safe (:minute particle-date))
                        :second (util/parse-int-safe (:second particle-date))}]
    (cond (:second converted-date)
          (t/date-time (:year converted-date)
                       (:month converted-date)
                       (:day converted-date)
                       (:hour converted-date)
                       (:minute converted-date)
                       (:second converted-date))

          (:day converted-date)
          (t/date-time (:year converted-date)
                       (:month converted-date)
                       (:day converted-date))
          
          (:month converted-date)
          (t/date-time (:year converted-date)
                       (:month converted-date))
          
          :else
          (t/date-time (:year converted-date)))))

(defn as-day-diff [left-particle-date right-particle-date]
  (let [left (as-datetime left-particle-date)
        right (as-datetime right-particle-date)]
    (if (t/after? left right)
      0
      (-> (t/interval left right)
          (t/in-days)))))

(defn ->license-start-date [license pub-date]
  (let [start-date (first (get-item-rel license :start))]
    (cond (not (nil? start-date))
          (as-datetime start-date)
          (not (nil? pub-date))
          (as-datetime pub-date))))

(defn ->license-delay [license pub-date]
  (if-let [start-date (first (get-item-rel license :start))]
    (as-day-diff pub-date start-date)
    0))

(defn as-solr-grant-info-field [item]
  (letfn [(funder-info [funder-name award-ids]
              (str
               funder-name
               " "
               (if-not (empty? award-ids)
                 (str "(" (string/join ", " award-ids) ")")
                 "")))]
    (string/join " | " (for [[k v] (as-grant-map item)] (funder-info k v)))))

(defn as-license-compound [license pub-date]
  (let [license-delay (->license-delay license pub-date)
        license-uri (util/slugify (:value license))
        license-version (:content-version license)]
    {(str "license_version_delay_" license-version) [license-delay]
     (str "license_url_delay_" license-uri) [license-delay]
     (str "license_url_version_" license-uri) [license-version]
     (str "license_url_version_delay_" license-uri "_" license-version) [license-delay]}))

(defn as-full-text-compound [full-text-resource]
  (let [content-type (-> full-text-resource (:content-type) (util/slugify))
        intended-application (-> full-text-resource :intended-application util/slugify)]
    {(str "full_text_type_version_" content-type) [(:content-version full-text-resource)]
     (str "full_text_type_application_" content-type) [(:intended-application full-text-resource)]
     (str "full_text_application_version_" intended-application) [(:content-version full-text-resource)]
     (str "full_text_type_application_version_" content-type "_" intended-application) [(:content-version full-text-resource)]}))

(defn as-award-compound [funder award]
  (let [funder-name (or (:name funder) "-")
        funder-doi (or (-> funder get-item-ids first) "-")
        award-number (or (-> award get-item-ids first) "-")
        normalized-award-number (-> award-number string/lower-case (string/replace #"[\s_\-]+" ""))
        slug-doi (or (-> funder get-item-ids first util/slugify) "-")]
    {(str "award_funder_doi_number_" slug-doi) [normalized-award-number]
     "award_number" [normalized-award-number]
     "award_number_display" [award-number]
     "award_funder_name" [funder-name]
     "award_funder_doi" [funder-doi]}))

(defn as-funder-award-compounds [funder]
  (let [awards (map as-award-compound (repeat funder) (get-item-rel funder :awarded))]
    (apply merge-with #(concat %1 %2) awards)))

(defn as-license-compounds [licenses pub-date]
  (let [compounds (map as-license-compound licenses (repeat pub-date))]
    (apply merge-with #(concat %1 %2) compounds)))

(defn as-full-text-compounds [full-text-resources]
  (let [compounds (map as-full-text-compound full-text-resources)]
    (apply merge-with #(concat %1 %2) compounds)))

(defn as-award-compounds [funders]
  (let [compounds (map as-funder-award-compounds funders)]
    (apply merge-with #(concat %1 %2) compounds)))

(defn as-contributor-affiliation-lists [contrib-details]
  (into {}
        (map-indexed #(vector (str "contributor_affiliations_" %1)
                              (:affiliations %2))
                     contrib-details)))

(defn as-assertion-list [assertions]
  (->> assertions
       (map-indexed
        #(-> {}
             (util/assoc-str (str "assertion_name_" %1) (:name %2))
             (util/assoc-str (str "assertion_label_" %1) (:label %2))
             (util/assoc-str (str "assertion_group_name_" %1) (:group-name %2))
             (util/assoc-str (str "assertion_group_label_" %1) (:group-label %2))
             (util/assoc-str (str "assertion_url_" %1) (:url %2))
             (util/assoc-str (str "assertion_explanation_url_" %1) (:explanation-url %2))
             (util/assoc-str (str "assertion_value_" %1) (:value %2))
             (util/assoc-int (str "assertion_order_" %1) (:order %2))))
       (apply merge)))

(defn as-solr-document [item]
  (let [grant-map (as-grant-map item)
        licenses (as-license-list item)
        funder-names (set (map :name (get-tree-rel item :funder)))
        funder-dois (set (mapcat :id (get-tree-rel item :funder)))
        publisher (first (get-tree-rel item :publisher))
        full-text-resources (get-item-rel item :resource-fulltext)
        funders (get-tree-rel item :funder)
        assertions (get-tree-rel item :assertion)
        pub-date (get-earliest-pub-date item)
        primary-author (get-primary-author item)
        container-titles (get-container-titles item)
        deposit-date (first (get-tree-rel item :deposited))
        first-deposit-date (first (get-tree-rel item :first-deposited))
        contrib-details (get-contributor-details item)
        updates (get-updates item)
        doi (first (get-item-ids item :long-doi))]
    (-> {"source" (:source item)
         "indexed_at" (t/now)
         "deposited_at" (if deposit-date (as-datetime deposit-date) (t/now))
         "first_deposited_at"
         (or (when first-deposit-date (as-datetime first-deposit-date))
             (when deposit-date (as-datetime deposit-date))
             (t/now))
         "prefix" (doi/extract-long-prefix doi)
         "doi_key" doi
         "doi" doi
         "issn" (get-tree-ids item :issn)
         "isbn" (get-tree-ids item :isbn)
         "supplementary_id" (get-tree-ids item :supplementary)
         "orcid" (get-contributor-orcids item)
         "article_number" (get-article-number item)
         "affiliation" (get-contributor-affiliations item)
         "assertion_name" (get-assertion-names item)
         "assertion_group_name" (get-assertion-group-names item)
         "category" (get-categories item)
         "funder_name" funder-names
         "funder_doi" funder-dois
         "type" (subtype-labels (get-item-subtype item))
         "first_author_given" (:first-name primary-author)
         "first_author_surname" (:last-name primary-author)
         "content" (as-solr-content-field item)
         "content_citation" (as-solr-citation-field item)
         "publication" container-titles
         "oa_status" (get-oa-status item)
         "hl_publication" container-titles
         "year" (:year pub-date)
         "month" (:month pub-date)
         "day" (:day pub-date)
         "contributor_given_name" (map (util/?- :given-name) contrib-details)
         "contributor_family_name" (map (util/?- :family-name) contrib-details)
         "contributor_suffix" (map (util/?- :suffix) contrib-details)
         "contributor_orcid" (map (util/?- :orcid) contrib-details)
         "contributor_type" (map (util/?- :type) contrib-details)
         "hl_description" (:description item)
         "hl_year" (:year pub-date)
         "hl_authors" (get-contributor-names item :author)
         "hl_editors" (get-contributor-names item :editor)
         "hl_chairs" (get-contributor-names item :chair)
         "hl_translators" (get-contributor-names item :translator)
         "hl_contributors" (get-contributor-names item :contributor)
         "hl_first_page" (:first-page item)
         "hl_last_page" (:last-page item)
         "hl_funder_name" funder-names
         "hl_grant" (as-solr-grant-info-field item)
         "hl_issue" (:issue (find-item-of-subtype item :journal-issue))
         "hl_volume" (:volume (find-item-of-subtype item :journal-volume))
         "hl_title" (->> (get-item-rel item :title)
                         (filter #(not= (:subtype %) :secondary))
                         (map :value))
         "hl_subtitle" (->> (get-item-rel item :title)
                            (filter #(= (:subtype %) :secondary))
                            (map :value))
         "archive" (map :name (get-tree-rel item :archived-with))
         "license_url" (map (util/?- :value) licenses)
         "license_version" (map (util/?- :content-version) licenses)
         "license_start" (map ->license-start-date licenses (repeat pub-date))
         "license_delay" (map ->license-delay licenses (repeat pub-date))
         "references" false ;now
         "cited_by_count" 0 ;now
         "citation_count" (count (get-tree-rel item :citation))
         "full_text_type" (map (util/?- :content-type) full-text-resources)
         "full_text_url" (map (util/?- :value) full-text-resources)
         "full_text_version" (map (util/?- :content-version) full-text-resources)
         "full_text_application" (map (util/?- :intended-application) full-text-resources)
         "publisher" (:name publisher)
         "hl_publisher" (:name publisher)
         "owner_prefix" (or (first (get-item-ids publisher :owner-prefix)) "none")
         "update_policy" (get-update-policy item)
         "update_doi" (map :value updates)
         "update_type" (map :subtype updates)
         "update_label" (map :label updates)
         "update_date" (map #(-> (get-item-rel % :updated) first as-datetime) updates)
         "funder_record_name" (map (util/?- :name) funders)
         "funder_record_doi" (map (util/?fn- (comp first get-item-ids)) funders)}
        (merge (as-assertion-list assertions))
        (merge (as-contributor-affiliation-lists contrib-details))
        (merge (as-award-compounds funders))
        (merge (as-license-compounds licenses pub-date))
        (merge (as-full-text-compounds full-text-resources)))))

(defn as-solr-input-document [solr-map]
  (let [doc (SolrInputDocument.)]
    (doseq [[k v] solr-map]
      (.addField doc k v))
    doc))
     
(defn insert-item [item]
  (let [solr-map (as-solr-document item)]
    (if-not (get solr-map "doi_key")
      (throw (Exception. "No DOI in item tree when inserting into solr."))
      (let [solr-doc (as-solr-input-document solr-map)]
        (swap! insert-list add-to-insert-list solr-doc)))))

(defn insert-solr-doc [solr-doc]
  (swap! insert-list add-to-insert-list solr-doc))

  
