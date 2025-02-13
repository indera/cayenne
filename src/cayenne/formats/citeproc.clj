(ns cayenne.formats.citeproc
  (:require [clj-time.format :as df]
            [clj-time.core :as dt]
            [clj-time.coerce :as dc]
            [clojure.string :as string]
            [cayenne.util :as util]
            [cayenne.ids :as ids]
            [cayenne.ids.doi :as doi-id]
            [cayenne.ids.issn :as issn-id]
            [cayenne.ids.isbn :as isbn-id]
            [cayenne.ids.type :as type-id]))

;; TODO Proper use of container-title vs. collection-title
;; author vs. container-author vs. collection-author etc.

(defn assoc-exists 
  "Like assoc except only performs the assoc if value is
   a non-empty string, non-empty list or a non-nil value."
  [m key value]
  (cond (= (type value) java.lang.String)
        (if (clojure.string/blank? value)
          m
          (assoc m key value))
        (seq? value)
        (if (empty? value)
          m
          (assoc m key value))
        (nil? value)
        m
        :else
        (assoc m key value)))

;; We check number-of-days-in-the-month because some dates in CrossRef
;; metadata have a day that is not in the valid range for the given
;; month, e.g. 31st Feb. In these cases we drop the day.
(defn ->date-parts
  ([year month day]
     (cond (and year month day)
           (if (< (dt/number-of-days-in-the-month year month) day)
             {:date-parts [[year month]]}
             {:date-parts [[year month day]]})
           (and year month)
           {:date-parts [[year month]]}
           :else
           {:date-parts [[year]]}))
  ([date-obj]
   (cond (nil? date-obj)
         nil
         (string? date-obj)
         (let [d (dc/from-long (Long/parseLong date-obj))]
           {:date-parts [[(dt/year d) (dt/month d) (dt/day d)]]
            :date-time (df/unparse (df/formatters :date-time-no-ms) d)
            :timestamp (dc/to-long d)})
         :else
         (let [d (dc/from-date date-obj)]
           {:date-parts [[(dt/year d) (dt/month d) (dt/day d)]]
            :date-time (df/unparse (df/formatters :date-time-no-ms) d)
            :timestamp (dc/to-long d)}))))
        
(defn license [url start-date delay-in-days content-version]
  (-> {:URL url}
      (assoc-exists :start (->date-parts start-date))
      (assoc-exists :delay-in-days delay-in-days)
      (assoc-exists :content-version content-version)))

;; todo In some circumstances a record may not have a publication date.
;; When a license also does not specify a start date, this leaves its
;; implied start date null. This should be fixed in the unixref parser,
;; rather than padding the start dates here.
(defn ->citeproc-licenses [solr-doc]
  (let [padded-start-dates
        (concat
         (get solr-doc "license_start")
         (repeat (- (count (get solr-doc "license_url"))
                    (count (get solr-doc "license_start")))
                 nil))]
    (map license
         (get solr-doc "license_url")
         padded-start-dates
         (get solr-doc "license_delay")
         (get solr-doc "license_version"))))

(defn link [url content-type content-version intended-application]
  (-> {:URL url}
      (assoc-exists :content-type content-type)
      (assoc-exists :content-version content-version)
      (assoc-exists :intended-application intended-application)))

(defn ->citeproc-links [solr-doc]
  (let [padded-ia
        (concat
         (get solr-doc "full_text_application")
         (repeat (- (count (get solr-doc "full_text_url"))
                    (count (get solr-doc "full_text_application")))
                 nil))]
    (map link
         (get solr-doc "full_text_url")
         (get solr-doc "full_text_type")
         (get solr-doc "full_text_version")
         padded-ia)))

(defn ->citeproc-pages [solr-doc]
  (let [first-page (get solr-doc "hl_first_page")
        last-page (get solr-doc "hl_last_page")]
    (cond (and (not (clojure.string/blank? last-page))
               (not (clojure.string/blank? first-page)))
          (str first-page "-" last-page)
          (not (clojure.string/blank? first-page))
          first-page
          :else
          nil)))

(defn sanitize-type
  "Function to sanitize type strings as some have made it
   into the solr index with a prepended ':' due to indexing
   bug."
  [s]
  (keyword (clojure.string/replace-first s #"\:" "")))

(defn contrib 
  "Drop placeholders indicating missing data."
  [type orcid suffix given family]
  (let [has-type? (not= type "-")
        has-orcid? (not= orcid "-")
        has-suffix? (not= suffix "-")
        has-given? (not= given "-")
        has-family? (not= family "-")]
    (-> {}
        (util/?> has-type? assoc :type (sanitize-type type))
        (util/?> has-orcid? assoc :ORCID orcid)
        (util/?> has-suffix? assoc :suffix suffix)
        (util/?> has-given? assoc :given given)
        (util/?> has-family? assoc :family family))))

(defn contrib-affiliations [affiliations]
  (map #(hash-map :name %) affiliations))

(defn ->citeproc-contribs [solr-doc]
  (reduce #(let [t (get %2 :type)]
             (assoc %1 t (conj (or (get %1 t) []) (dissoc %2 :type))))
          {}
          (map-indexed #(let [affils
                              (-> solr-doc
                                  (get (str "contributor_affiliations_" %1))
                                  contrib-affiliations)]
                          (assoc %2 :affiliation affils))
                       (map contrib
                            (get solr-doc "contributor_type")
                            (get solr-doc "contributor_orcid")
                            (get solr-doc "contributor_suffix")
                            (get solr-doc "contributor_given_name")
                            (get solr-doc "contributor_family_name")))))

(defn ->citeproc-awards [solr-doc]
  (map
   #(hash-map :number %1
              :DOI %2
              :name %3)
   (get solr-doc "award_number_display")
   (get solr-doc "award_funder_doi")
   (get solr-doc "award_funder_name")))

(defn ->citeproc-funders [solr-doc]
  (let [awards (->citeproc-awards solr-doc)]
    (map
     #(-> {}
          (util/?> (not= %1 "-") assoc :DOI (doi-id/extract-long-doi %1))
          (util/?> (not= %2 "-") assoc :name %2)
          (assoc :award (set 
                         (concat (when (not= %1 "-") 
                                   (->> awards (filter (fn [a] (= (:DOI a) %1))) (map :number)))
                                 (when (not= %2 "-")
                                   (->> awards (filter (fn [a] (= (:name a) %2))) (map :number)))))))
     (get solr-doc "funder_record_doi")
     (get solr-doc "funder_record_name"))))

(defn ->citeproc-funders-merged 
  "Where the underlying metadata is such that there are multiple funder records
   for the same funder, we should merge them where the funder IDs match. We cannot
   however merge where only names match - these could legitimately be separate
   funding organisations."
  [solr-doc]
  (let [funders (->citeproc-funders solr-doc)]
    (concat 
     (->> funders
          (filter #(not= (:DOI %) nil))
          (reduce #(merge %1 {(:DOI %2) %2}) {})
          vals)
     (filter #(= (:DOI %) nil) funders))))

(defn ->citeproc-updates-to [solr-doc]
  (map 
   #(hash-map
     :DOI (doi-id/extract-long-doi %1)
     :type %2
     :label %3
     :updated (->date-parts %4))
   (get solr-doc "update_doi")
   (get solr-doc "update_type")
   (get solr-doc "update_label")
   (get solr-doc "update_date")))

(defn ->citeproc-updated-by [solr-doc]
  (map 
   #(hash-map
     :DOI (doi-id/extract-long-doi %1)
     :type %2
     :label %3
     :updated (->date-parts %4))
   (get solr-doc "update_by_doi")
   (get solr-doc "update_by_type")
   (get solr-doc "update_by_label")
   (get solr-doc "update_by_date")))

(defn ->citeproc-assertion-group [group-name group-label]
  (-> {}
      (util/?> group-name assoc :name group-name)
      (util/?> group-label assoc :label group-label)))

(defn ->citeproc-assertions [solr-doc]
  (->> (range)
       (take-while #(get solr-doc (str "assertion_name_" %)))
       (map
        #(let [explanation-url (first (get solr-doc (str "assertion_explanation_url_" %)))
               group-name (first (get solr-doc (str "assertion_group_name_" %)))
               group-label (first (get solr-doc (str "assertion_group_label_" %)))]
           (-> {}
               (assoc-exists :value (first (get solr-doc (str "assertion_value_" %))))
               (assoc-exists :URL   (first (get solr-doc (str "assertion_url_" %))))
               (assoc-exists :order (first (get solr-doc (str "assertion_order_" %))))
               (assoc-exists :name  (first (get solr-doc (str "assertion_name_" %))))
               (assoc-exists :label (first (get solr-doc (str "assertion_label_" %))))
               (util/?> explanation-url assoc :explanation {:URL explanation-url})
               (util/?> (or group-name group-label)
                        assoc :group (->citeproc-assertion-group group-name group-label)))))))

(defn ->citeproc [solr-doc]
  (-> {:source (get solr-doc "source")
       :prefix (get solr-doc "owner_prefix")
       :DOI (doi-id/extract-long-doi (get solr-doc "doi"))
       :URL (get solr-doc "doi")
       :issued (->date-parts (get solr-doc "year")
                             (get solr-doc "month")
                             (get solr-doc "day"))
       :created (->date-parts (get solr-doc "first_deposited_at"))
       :deposited (->date-parts (get solr-doc "deposited_at"))
       :indexed (->date-parts (get solr-doc "indexed_at"))
       :publisher (get solr-doc "publisher")
       :reference-count (get solr-doc "citation_count")
       :type (type-id/->type-id (get solr-doc "type"))
       :score (get solr-doc "score")}
      (assoc-exists :article-number (get solr-doc "article_number"))
      (assoc-exists :volume (get solr-doc "hl_volume"))
      (assoc-exists :issue (get solr-doc "hl_issue"))
      (assoc-exists :ISBN (map isbn-id/extract-isbn (get solr-doc "isbn")))
      (assoc-exists :ISSN (map issn-id/extract-issn (get solr-doc "issn")))
      (assoc-exists :alternative-id (map ids/extract-supplementary-id
                                         (get solr-doc "supplementary_id")))
      (assoc-exists :title (set (get solr-doc "hl_title")))
      (assoc-exists :subtitle (set (get solr-doc "hl_subtitle")))
      (assoc-exists :container-title (set (get solr-doc "hl_publication")))
      (assoc-exists :subject (get solr-doc "category"))
      (assoc-exists :archive (get solr-doc "archive"))
      (assoc-exists :update-policy (get solr-doc "update_policy"))
      (assoc-exists :update-to (->citeproc-updates-to solr-doc))
      (assoc-exists :updated-by (->citeproc-updated-by solr-doc))
      (assoc-exists :license (->citeproc-licenses solr-doc))
      (assoc-exists :link (->citeproc-links solr-doc))
      (assoc-exists :page (->citeproc-pages solr-doc))
      (assoc-exists :funder (->citeproc-funders-merged solr-doc))
      (assoc-exists :assertion (->citeproc-assertions solr-doc))
      (merge (->citeproc-contribs solr-doc))))

