;; Copyright © 2022, JUXT LTD.

(ns juxt.pass.alpha.authorization
  (:require
   [xtdb.api :as xt]
   [clojure.tools.logging :as log]))

(alias 'http (create-ns 'juxt.http.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))
(alias 'site (create-ns 'juxt.site.alpha))

(defn rules [db resource]
  (assert (string? resource))
  (mapv
   (comp read-string first)
   (xt/q
    db
    '{:find [rule-content]
      :where [[resource ::pass/ruleset ruleset]
              [ruleset ::pass/rules rule]
              [rule ::pass/rule-content rule-content]]
      :in [resource]}
    resource)))

(defn acls
  "Return ACLs. The session argument can be nil, the resource argument must not
  be."
  [db subject action resource]
  ;; Subject can be nil, resource cannot be
  (assert (or (nil? subject) (string? subject)))
  (assert (string? action))
  (assert (string? resource))
  (let [rules (rules db resource)
        query {:find ['(pull acl [*])]
               :where '[[acl ::site/type "ACL"]
                        (check acl subject action resource)]
               :rules rules
               :in '[subject action resource]}]
    (if (seq rules)
      (map first (xt/q db query subject action resource))
      #{})))

(defn authorize-resource [{::site/keys [db uri]
                           :ring.request/keys [method]
                           ::pass/keys [session] :as req}]
  (let [acls (acls
              db
              (:xt/id session) ; for now we treat the session as representing
                               ; the subject
              (case method
                :get "read")
              uri)]
    (log/tracef "acls are %s" acls)
    (cond-> req
      (seq acls) (assoc-in [::site/resource ::pass/authorization ::pass/acls] acls)
      ;;true (assoc-in [::site/resource ::pass/authorization] {:debug true})
      )))
