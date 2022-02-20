;; Copyright © 2022, JUXT LTD.

(ns juxt.pass.acl-test
  (:require
   [clojure.test :refer [deftest is are testing] :as t]
   [juxt.pass.alpha.authorization :as authz]
   [juxt.test.util :refer [with-xt with-handler submit-and-await!
                           *xt-node* *handler*
                           access-all-areas access-all-apis]]
   [jsonista.core :as json]
   [juxt.jinx.alpha.api :refer [schema validate]]
   [clojure.java.io :as io]
   [juxt.jinx.alpha :as jinx]
   [xtdb.api :as xt]))

(alias 'apex (create-ns 'juxt.apex.alpha))
(alias 'http (create-ns 'juxt.http.alpha))
(alias 'pick (create-ns 'juxt.pick.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))
(alias 'site (create-ns 'juxt.site.alpha))

(t/use-fixtures :each with-xt with-handler)

(defn fail [ex-data] (throw (ex-info "FAIL" ex-data)))

(deftest scenario-1-test)

(defn check [db session action resource expected-count]
  (let [acls (authz/acls db session action resource)]
    (is (= expected-count (count acls)))
    (when-not (= expected-count (count acls))
      (fail {:session session
             :action action
             :resource resource
             :expected-count expected-count
             :actual-count (count acls)}))))

(defn list-resources [db session action ruleset expected-resources]
  (let [acls (authz/list-resources db session action ruleset)
        actual-resources (set (mapcat ::pass/resource acls))]
    (is (= expected-resources actual-resources))
    (when-not (= expected-resources actual-resources)
      (fail {:session session
             :action action
             :expected-resources expected-resources
             :actual-resources actual-resources}))))


(defn get-subject [db session]
  (authz/get-subject-from-session db "https://example.org/ruleset" session))

((t/join-fixtures [with-xt with-handler])
 (fn []
   (submit-and-await!
    [
     [::xt/put
      {:xt/id "https://example.org/index"
       ::http/methods #{:get}
       ::http/content-type "text/html;charset=utf-8"
       ::http/content "Hello World!"
       ;; We'll define this lower down
       ::pass/ruleset "https://example.org/ruleset"}]

     [::xt/put
      {:xt/id "https://example.org/~alice/index"
       ::http/methods #{:get}
       ::http/content-type "text/html;charset=utf-8"
       ::http/content "Alice's page"
       ::pass/ruleset "https://example.org/ruleset"}]

     ;; This is Alice.
     [::xt/put
      {:xt/id "https://example.org/people/alice"
       ::type "User"
       :juxt.pass.jwt/sub "alice"}]

     ;; This is Bob.
     [::xt/put
      {:xt/id "https://example.org/people/bob"
       ::type "User"
       :juxt.pass.jwt/sub "bob"}]

     [::xt/put
      {:xt/id "urn:site:session:bob"
       :juxt.pass.jwt/sub "bob"
       ::pass/scope "read:index"}]

     [::xt/put
      {:xt/id "https://example.org/roles/manager"
       ::type "Role"}]

     ;; Bob's access will be via his 'manager' role.
     [::xt/put
      {:xt/id "https://example.org/roles/bob-is-manager"
       ::site/type "ACL"
       :juxt.pass.jwt/sub "bob"
       ::pass/role "https://example.org/roles/manager"}]

     ;; Carl isn't a manager.

     [::xt/put
      {:xt/id "urn:site:session:carl"
       :juxt.pass.jwt/sub "carl"
       ::pass/scope "read:index"}]

     ;; A note on cacheing - each token can cache the resources it has access
     ;; to, keyed by action and transaction time. If a resource is updated, the
     ;; cache will fail. If an ACL is revoked, such that read access would no
     ;; longer be possible, the cache can still be used (avoiding the need to
     ;; detect changes to ACLs). See 'new enemy'
     ;; problem. https://duckduckgo.com/?t=ffab&q=authorization+%22new+enemy%22&ia=web

     [::xt/put
      {:xt/id "https://example.org/grants/alice-can-access-index"
       ::site/description "Alice is granted access to some resources"
       ::site/type "ACL"

       :juxt.pass.jwt/sub "alice"

       ;; A resource can be any XT document, a superset of web resources. Common
       ;; authorization terminology uses the term 'resource' for anything that
       ;; can be protected.
       ::pass/resource #{"https://example.org/index" "https://example.org/~alice/index"}
       ::pass/action "read"
       ::pass/scope "read:index"}]

     ;; TODO: Resource 'sets'

     [::xt/put
      {:xt/id "https://example.org/grants/managers-can-access-index"
       ::site/description "Managers are granted access to /index"
       ::site/type "ACL"

       ::pass/role "https://example.org/roles/manager"

       ;; A resource can be any XT document, a superset of web resources. Common
       ;; authorization terminology uses the term 'resource' for anything that
       ;; can be protected.
       ::pass/resource "https://example.org/index"
       ::pass/action "read"
       ::pass/scope "read:index"}]

     [::xt/put
      {:xt/id "https://example.org/rules/1"
       ::site/description "Allow read access of resources to granted subjects"
       ::pass/rule-content
       (pr-str '[[(check acl subject action resource)
                  [acl ::pass/resource resource]
                  (granted acl subject)
                  [acl ::pass/action action]

                  ;; A subject may be constrained to a scope. In this case, only
                  ;; matching ACLs are literally 'in scope'.
                  [acl ::pass/scope scope]
                  [subject ::pass/scope scope]]

                 [(granted acl subject)
                  [acl :juxt.pass.jwt/sub sub]
                  [subject :juxt.pass.jwt/sub sub]]

                 [(granted acl subject)
                  [acl ::pass/role role]
                  [subject :juxt.pass.jwt/sub sub]

                  [role ::type "Role"]
                  [role-membership ::site/type "ACL"]
                  [role-membership :juxt.pass.jwt/sub sub]
                  [role-membership ::pass/role role]]

                 [(list-resources acl subject action)
                  [acl ::pass/resource resource]
                  ;; Any acl, in scope, that references a resource (or set of
                  ;; resources)
                  [acl ::pass/scope scope]
                  [subject ::pass/scope scope]
                  [acl ::pass/action action]
                  (granted acl subject)]

                 [(get-subject-from-session session subject)
                  [subject ::type "User"]
                  [subject :juxt.pass.jwt/sub sub]
                  [session :juxt.pass.jwt/sub sub]]])}]


     ;; Establish a session for Alice.
     [::xt/put
      {:xt/id "urn:site:session:alice"
       :juxt.pass.jwt/sub "alice"
       ::pass/scope "read:index"}]

     ;; An access-token
     [::xt/put
      {:xt/id "urn:site:access-token:alice-without-read-index-scope"
       :juxt.pass.jwt/sub "alice"}]

     ;; We can now define the ruleset
     [::xt/put
      {:xt/id "https://example.org/ruleset"
       ::pass/rules ["https://example.org/rules/1"]}]])

   ;; Is subject allowed to do action to resource?
   ;; ACLs involved will include any limitations on actions

   ;; Which resources is subject allowed to do action on?
   ;; e.g. list of documents
   ;; This might be a solution to the n+1 problem in our graphql

   ;; Let's log in and create sessions




   (let [db (xt/db *xt-node*)

         subject (get-subject db "urn:site:session:alice")]

     (when-not (= subject "https://example.org/people/alice")
       (fail {:subject subject}))

     (check db "urn:site:session:alice" "read" "https://example.org/index" 1)
     (check db "urn:site:access-token:alice-without-read-index-scope" "read" "https://example.org/index" 0)

     ;; Fuzz each of the parameters to check that the ACL fails
     (check db nil "read" "https://example.org/index" 0)
     (check db "urn:site:session:alice" "read" "https://example.org/other-page" 0)
     (check db "urn:site:session:alice" "write" "https://example.org/index" 0)

     ;; Bob can read index
     (check db "urn:site:session:bob" "read" "https://example.org/index" 1)

     ;; But Carl cannot
     (check db "urn:site:session:carl" "read" "https://example.org/index" 0)

     ;; Which resources can Alice access?
     (list-resources
      db
      "urn:site:session:alice" "read" "https://example.org/ruleset"
      #{"https://example.org/~alice/index" "https://example.org/index"})

     ;; TODO: Alice sets up her own home-page, complete with an API for a test project
     ;; she's working on.

     ;; TODO: Alice has a number of documents. Some she wants to share some of
     ;; these with Bob. Others she classifies INTERNAL (so visible to all
     ;; colleagues), and others she classifies PUBLIC, so visible to anyone. The
     ;; remainder are private and only she can access.

     [::xt/put
      {:xt/id "https://example.org/alice-docs/document-1"
       ::site/description "A document owned by Alice, to be shared with Bob"
       ::http/methods #{:get}
       ::http/content-type "text/html;charset=utf-8"
       ::http/content "My Document"
       ::pass/ruleset "https://example.org/ruleset"}]

     ;; An ACL that grants Alice ownership of a document
     [::xt/put
      {:xt/id "https://example.org/alice-owns-document-1"
       ::site/description "An ACL that grants Alice ownership of a document"
       ::pass/resource "https://example.org/alice-docs/document-1"
       ::pass/owner "alice"}]

     ;; Check Alice can read her own documents, via ::pass/owner
     ;;(check "urn:site:session:alice" "read" "https://example.org/alice-docs/document-1" 0)

     ;; Alice accesses her own documents. A rule exists that automatically
     ;; grants full access to your own documents.

     ;; Bob lists Alice's documents.

     ;; This means that Alice should be able to create an ACL for Bob, which see
     ;; owns. But she can only create an ACL that references documents she owns.


     ;; TODO: Add resources to represent Alice, Bob and Carl, as subjects.


     {:status :ok :message "All tests passed"}




     )


   ))

;; Create a non-trivial complex scenario which contains many different
;; characters and rulesets.

;; TODO: INTERNAL classification
;; TODO: User content
;; TODO: Consent-based access control
;; TODO: Extend to GraphQL
