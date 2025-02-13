(ns cayenne.conf
  (:import [org.apache.solr.client.solrj.impl HttpSolrServer]
           [java.net URI]
           [java.util UUID]
           [java.util.concurrent Executors])
  (:use [clojure.core.incubator :only [dissoc-in]])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.trace :as trace]
            [somnium.congomongo :as m]
            [clj-http.conn-mgr :as conn]
            [clojure.tools.nrepl.server :as nrepl]
            [robert.bruce :as rb]))

(def cores (atom {}))
(def ^:dynamic *core-name*)

(def startup-tasks (atom {}))

(def shutdown-tasks (atom {}))

(defn get-param [path & default]
  (or (get-in @cores (concat [*core-name* :parameters] path) default)))

(defn set-param! [path value]
  (swap! cores assoc-in (concat [*core-name* :parameters] path) value))

(defn get-service [key]
  (get-in @cores [*core-name* :services key]))

(defn set-service! [key obj]
  (swap! cores assoc-in [*core-name* :services key] obj))

(defn add-startup-task [key task]
  (swap! startup-tasks assoc key task))

(defn get-resource [name]
  (-> (get-param [:res name]) io/resource))

(defn file-writer [file-name]
  (let [wrtr (io/writer file-name)]
    (add-watch (agent nil) :file-writer
               (fn [key agent old new]
                 (.write wrtr new)
                 (.flush wrtr)))))

(defn write-to [file-writer msg]
  (send file-writer (constantly msg)))

(defmacro with-core
  "Run operations on a particular core."
  [name & body]
  `(binding [*core-name* ~name]
     ~@body))

(defn create-core! [name]
  (swap! cores assoc name {}))

(defn create-core-from! [name copy-from-name]
  (let [params (get-in @cores [copy-from-name :parameters])]
    (swap! cores assoc-in [name :parameters] params)))

(defn start-core!
  "Create a new named core, initializes various services."
  [name & profiles]
  (let [with-base-profiles (conj profiles :base)]
    (with-core name
      (doseq [[name task] @startup-tasks]
        (when (some #{name} with-base-profiles)
          (print "Starting" name "... ")
          (task profiles)
          (println "done.")))
      (set-param! [:status] :running))))

(defn stop-core! [name]
  (with-core name
    ((get-service :api)) ; stop http kit
    (set-param! [:status] :stopped)))

(defn set-core! [name]
  (alter-var-root #'*core-name* (constantly name)))

(defn test-input-file [name]
  (io/file (str (get-param [:dir :test-data]) "/" name ".xml")))

(defn test-accepted-file [name test-name]
  (io/file (str (get-param [:dir :test-data]) "/" name "-" test-name ".accepted")))

(defn test-output-file [name test-name]
  (io/file (str (get-param [:dir :test-data]) "/" name "-" test-name ".out")))

(defn remote-file [url]
  (rb/try-try-again
   {:tries 10
    :error-hook #(prn "Failed to retrieve url " url " - " %)}
   #(let [content (slurp (URI. url))
          path (str (get-param [:dir :tmp]) "/remote-" (UUID/randomUUID) ".tmp")]
      (spit (io/file path) content)
      (io/file path))))

;; todo move default service config to the files that maintain maintan the service.

(with-core :default
  (set-param! [:env] :none)
  (set-param! [:status] :stopped)
  (set-param! [:dir :home] (System/getProperty "user.dir"))
  (set-param! [:dir :data] (str (get-param [:dir :home]) "/data"))
  (set-param! [:dir :test-data] (str (get-param [:dir :home]) "/test-data"))
  (set-param! [:dir :tmp] (str (get-param [:dir :home]) "/tmp"))

  (set-param! [:service :solr :update-list]
              [{:url "http://144.76.35.104:8983/solr" :core "crmds1"}])

  (set-param! [:service :mongo :db] "crossref")
  (set-param! [:service :mongo :host] "78.46.67.131")
  (set-param! [:service :solr :url] "http://localhost:8983/solr")
  (set-param! [:service :solr :query-core] "crmds1")                
  (set-param! [:service :solr :insert-list-max-size] 10000)
  (set-param! [:service :datomic :url] "datomic:mem://test")
  (set-param! [:service :api :port] 3000)
  (set-param! [:service :queue :host] "5.9.51.150")
  (set-param! [:service :logstash :host] "5.9.51.2")
  (set-param! [:service :logstash :port] 4444)
  (set-param! [:service :logstash :name] "cayenne-api")

  (set-param! [:deposit :email] "crlabs@fastmail.fm")

  (set-param! [:oai :datacite :dir] (str (get-param [:dir :data]) "/oai/datacite"))
  (set-param! [:oai :datacite :url] "http://oai.datacite.org/oai")
  (set-param! [:oai :datacite :type] "datacite")

  (set-param! [:id :issn :path] "http://id.crossref.org/issn/")
  (set-param! [:id :isbn :path] "http://id.crossref.org/isbn/")
  (set-param! [:id :orcid :path] "http://orcid.org/")
  (set-param! [:id :owner-prefix :path] "http://id.crossref.org/prefix/")
  (set-param! [:id :long-doi :path] "http://dx.doi.org/")
  (set-param! [:id :short-doi :path] "http://doi.org/")
  (set-param! [:id :supplementary :path] "http://id.crossref.org/supp/")
  (set-param! [:id :contributor :path] "http://id.crossref.org/contributor/")
  (set-param! [:id :member :path] "http://id.crossref.org/member/")
  
  (set-param! [:id-generic :path] "http://id.crossref.org/")
  (set-param! [:id-generic :data-path] "http://data.crossref.org/")

  (set-param! [:res :tld-list] "tlds.txt")
  (set-param! [:res :funders] "funders.csv")
  (set-param! [:res :locales] "locales.edn")
  (set-param! [:res :styles] "styles.edn")
  (set-param! [:res :tokens] "tokens.edn")
  (set-param! [:res :funder-update] "data/funder-update.date")

  (set-param! [:location :cr-titles-csv] "http://www.crossref.org/titlelist/titleFile.csv")
  (set-param! [:location :cr-funder-registry] "http://dx.doi.org/10.13039/fundref_registry")

  (set-param! [:test :doi] "10.5555/12345678")

  (set-param! [:upstream :pdf-service] "http://46.4.83.72:3000/pdf")
  (set-param! [:upstream :crmds-dois] "http://search.crossref.org/dois?q=")
  (set-param! [:upstream :funder-dois-live] "http://search.crossref.org/funders/dois?rows=10000000000")
  (set-param! [:upstream :funder-dois-dev] "http://search-dev.labs.crossref.org/funders/dois?rows=10000000000")
  (set-param! [:upstream :openurl-url] "http://www.crossref.org/openurl/?noredirect=true&pid=cnproxy@crossref.org&format=unixref&id=doi:")
  (set-param! [:upstream :doi-url] "http://doi.crossref.org/search/doi?pid=cnproxy@crossref.org&format=unixsd&doi=")
  (set-param! [:upstream :unixref-url] "http://doi.crossref.org/search/doi?pid=cnproxy@crossref.org&format=unixref&doi=")
  (set-param! [:upstream :unixsd-url] "http://doi.crossref.org/search/doi?pid=cnproxy@crossref.org&format=unixsd&doi=")
  (set-param! [:upstream :prefix-info-url] "http://www.crossref.org/getPrefixPublisher/?prefix=")
  (set-param! [:upstream :ra-url] "https://doi.crossref.org/doiRA/")
  (set-param! [:upstream :crossref-auth] "https://doi.crossref.org/info")
  (set-param! [:upstream :crossref-test-auth] "http://test.crossref.org/info"))

(with-core :default
  (add-startup-task 
   :base
   (fn [profiles]
     (set-service! :executor (Executors/newScheduledThreadPool 20))
     (set-service! :conn-mgr (conn/make-reusable-conn-manager {:timeout 120 :threads 10}))
     (set-service! :mongo (m/make-connection (get-param [:service :mongo :db])
                                             :host (get-param [:service :mongo :host])))
     (set-service! :solr (HttpSolrServer. (get-param [:service :solr :url])))
     (set-service! :solr-update-list
                   (map #(HttpSolrServer. (str (:url %) "/" (:core %)))
                        (get-param [:service :solr :update-list]))))))

(with-core :default
  (add-startup-task
   :nrepl
   (fn [profiles]
     (set-service! :nrepl (nrepl/start-server :port 7888)))))

(set-core! :default)

