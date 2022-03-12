(ns radiale.data
  (:require 
    [babashka.pods :as pods]
    [babashka.deps :as deps]))

(deps/add-deps '{:deps {honeysql/honeysql {:mvn/version "1.0.444"}}})
(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")

(require 
         '[pod.babashka.go-sqlite3 :as sqlite]
         '[honeysql.core :as sql]
         '[honeysql.helpers :as helpers])




(sqlite/execute! "/tmp/foo.db" ["create table if not exists foo (col1 TEXT, col2 TEXT)"])

(def insert
  (-> (helpers/insert-into :foo)
      (helpers/columns :col1 :col2)
      (helpers/values
       [["Foo" "Bar"]
        ["Baz" "Quux"]])
      sql/format))
;; => ["INSERT INTO foo (col1, col2) VALUES (?, ?), (?, ?)" "Foo" "Bar" "Baz" "Quux"]

(sqlite/execute! "/tmp/foo.db" insert)
;; => {:rows-affected 2, :last-inserted-id 2}

(def sqlmap {:select [:col1 :col2]
             :from   [:foo]
             :where  [:= :col1 "Foo"]})

(def select (sql/format sqlmap))
;; => ["SELECT col1, col2 FROM foo WHERE col1 = ?" "Foo"]

(sqlite/query "/tmp/foo.db" select)
;; => [{:col1 "Foo", :col2 "Bar"}]
