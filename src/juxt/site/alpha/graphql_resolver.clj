;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.graphql-resolver
  (:require
   [juxt.site.alpha.repl :as repl]
   [crux.api :as xt]
   [clojure.set :as set]))

(defn system [_]
  (let [system (repl/system)]
    {"configuration"
     (delay
       (let [config (repl/config)]
         {"baseUri" (:juxt.site.alpha/base-uri config)
          "unixPassPasswordPrefix"
          (:juxt.site.alpha.unix-pass/password-prefix config)
          "serverPortNumber"
          (get-in config [:ig/system
                          :juxt.site.alpha.server/server
                          :juxt.site.alpha/port])}))
     "database"
     (delay
       (let [node (:juxt.site.alpha.db/crux-node system)
             status (xt/status node)]
         (merge
          (set/rename-keys
           status
           {:crux.version/version "version"
            :crux.version/revision "revision"
            :crux.index/index-version "indexVersion"
            :crux.kv/kv-store "kvStore"
            :crux.kv/estimate-num-keys "estimateNumKeys"
            :crux.kv/size "kvSize"})
          {"attributeStats"
           (delay
             (for [[name frequency] (xt/attribute-stats node)]
               {"attribute" (str name) "frequency" frequency}))})))
     #_#_"jvm"
     (delay
       )}))