(ns com.shortify.api.db.core
  (:import [com.mchange.v2.c3p0 ComboPooledDataSource])
  (:require [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [environ.core :refer [env]]))

(s/def ::host string?)
(s/def ::port int?)
(s/def ::dbtype #{"postgresql"})
(s/def ::dbname string?)
(s/def ::user string?)
(s/def ::password string?)
(s/def ::classname string?)
(s/def ::subprotocol string?)
(s/def ::datasource #(instance? ComboPooledDataSource %))

(s/def ::db-spec
  (s/keys :req-un [::host ::port ::dbname ::user ::password]))

(s/def ::db-spec-partial
  (s/keys :opt-un [::host ::port ::dbname ::user ::password]))

(s/def ::db-spec-long-form
  (s/keys :req-un [::classname ::subprotocol ::subname ::user ::password]))

(s/def ::db
  (s/keys :req-un [::datasource]))

(defn- env-spec
  "Extracts database configuration options from environment variables."
  []
  {:host (:database-host env)
   :port (Integer/parseUnsignedInt (:database-port env))
   :dbtype (:database-type env)
   :dbname (:database-name env)
   :user (:database-username env)
   :password (:database-password env)})

(s/fdef db-spec
        :args (s/cat :overrides ::db-spec-partial)
        :ret ::db-spec)

(defn- db-spec
  "Merges database configuration options from environment variables with a map
  of overrides."
  [overrides]
  (merge (env-spec) overrides))

(defn- subname
  "Constructs the subname required by the 'long-form' database spec."
  [host port name]
  (str "//" host ":" port "/" name))

;; FIXME Add documentation
(def driver-classnames
  {"postgresql" "org.postgresql.Driver"
   "mysql" "com.mysql.jdbc.Driver"})

(defn db-spec-long-form
  "Constructs the 'long-form' database spec required by the c3p0 connection
  pooling library."
  [{:keys [host port dbname dbtype user password]}]
  {:classname (driver-classnames dbtype)
   :subprotocol dbtype
   :subname (subname host port dbname)
   :user user
   :password password})

(s/fdef pool
        :args (s/cat :spec ::db-spec-long-form)
        :ret ::db)

(defn- pool
  "Creates a map containing a connection pool for the configured database.
  This map can be passed in as the first argument of all clojure.java.jdbc
  functions."
  [spec]
  (let [{:keys [classname subprotocol subname user password]} spec
        datasource (doto (ComboPooledDataSource.)
                     (.setDriverClass classname)
                     (.setJdbcUrl (str "jdbc:" subprotocol ":" subname))
                     (.setUser user)
                     (.setPassword password)
                     ;; expire excess connections after 30 minutes of inactivity
                     (.setMaxIdleTimeExcessConnections (* 30 60))
                     ;; expire connections after 3 hours of inactivity:
                     (.setMaxIdleTime (* 3 60 60)))]
    {:datasource datasource}))

(defmethod ig/init-key :db
  [_ config]
  (-> (db-spec config)
      db-spec-long-form
      pool))

(defmethod ig/halt-key! :db
  [_ {:keys [datasource]}]
  (.close datasource))
