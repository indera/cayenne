(ns cayenne.api.route
  (:require [cayenne.conf :as conf]
            [cayenne.api.v1.routes :as v1]
            [cayenne.api.v1.doc :as v1-doc]
            [cayenne.api.v1.graph :as graph-v1]
            [cayenne.api.v1.feed :as feed-v1]
            [cayenne.api.conneg :as conneg]
            [cayenne.api.auth.crossref :as cr-auth]
            [cayenne.api.auth.token :as token-auth]
            [ring.middleware.logstash :as logstash]
            [heartbeat.ring :refer [wrap-heartbeat]]
            [heartbeat.core :refer [def-web-check]]
            [liberator.dev :refer [wrap-trace]]
            [metrics.ring.expose :refer [expose-metrics-as-json]]
            [metrics.ring.instrument :refer [instrument]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace-web]]
            [ring.middleware.basic-authentication :refer [wrap-basic-authentication]]
            [compojure.handler :as handler]
            [ring.util.response :refer [redirect]]
            [org.httpkit.server :as hs]
            [compojure.core :refer [defroutes routes context ANY]]))

(def-web-check :doi-unixref-query
  (str (conf/get-param [:upstream :unixref-url])
       (conf/get-param [:test :doi])))

(def-web-check :doi-unixsd-query
  (str (conf/get-param [:upstream :unixsd-url])
       (conf/get-param [:test :doi])))

(defn create-protected-api-routes []
  (wrap-basic-authentication
   (routes
    v1/restricted-api-routes
    (context "/v1" [] v1/restricted-api-routes)
    (context "/v1.0" [] v1/restricted-api-routes))
   cr-auth/authenticated?))

(defn create-unprotected-api-routes []
  (routes
   v1/api-routes
   v1-doc/api-doc-routes
   (context "/v1" [] v1/api-routes)
   (context "/v1" [] v1-doc/api-doc-routes)
   (context "/v1.0" [] v1/api-routes)
   (context "/v1.0" [] v1-doc/api-doc-routes)))

(defn create-docs-routes []
  (routes 
   (ANY "/help/bestpractice" []
        (redirect "https://github.com/CrossRef/rest-api-doc/blob/master/funder_kpi_metadata_best_practice.md"))
   (ANY "/help" []
        (redirect "https://github.com/CrossRef/rest-api-doc/blob/master/rest_api.md"))
   (ANY "/" [] 
        (redirect "/help"))))

(defn create-graph-routes []
  (routes
   (context "/graph" [] graph-v1/graph-api-routes)
   (context "/v1/graph" [] graph-v1/graph-api-routes)
   (context "/v1.0/graph" [] graph-v1/graph-api-routes)))

(defn create-feed-routes []
  (wrap-basic-authentication
   (routes
    (context "/feeds" [] feed-v1/feed-api-routes)
    (context "/v1/feeds" [] feed-v1/feed-api-routes)
    (context "/v1.0/feeds" [] feed-v1/feed-api-routes))
   token-auth/authenticated?))

(defn create-all-routes [& {:keys [graph-api feed-api]
                            :or {graph-api false
                                 feed-api false}}]
  (apply routes
         (cond-> [(create-unprotected-api-routes)
                  (create-docs-routes)]
           graph-api (conj (create-graph-routes))
           feed-api (conj (create-feed-routes))
           true (conj (create-protected-api-routes)))))

(defn wrap-cors
  [h]
  (fn [request]
    (-> (h request)
        (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
        (assoc-in [:headers "Access-Control-Allow-Headers"]
                  "X-Requested-With"))))

(defn create-handler [& {:keys [graph-api feed-api] :or {graph-api false
                                                         feed-api false}}]
  (-> (create-all-routes :graph-api graph-api :feed-api feed-api)
      (logstash/wrap-logstash :host (conf/get-param [:service :logstash :host])
                              :port (conf/get-param [:service :logstash :port])
                              :name (conf/get-param [:service :logstash :name]))
      (handler/api)
      (wrap-cors)
      (expose-metrics-as-json)
      (instrument)
      (wrap-heartbeat)
      ; (wrap-trace :ui)
      ; disabled due to bug in apache2 reverse proxy
      ; (creates headers that are incompatible)
      (wrap-stacktrace-web)
      (conneg/wrap-accept)))

(conf/with-core :default
  (conf/add-startup-task
   :api
   (fn [profiles]
     (when (some #{:graph} profiles)
       (require 'cayenne.tasks.datomic)
       (cayenne.tasks.datomic/connect!))
     (conf/set-service! 
      :api 
      (hs/run-server 
       (create-handler :graph-api (some #{:graph-api} profiles)
                       :feed-api (some #{:feed-api} profiles))
       {:join? false
        :port (conf/get-param [:service :api :port])})))))
