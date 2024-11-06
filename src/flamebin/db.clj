(ns flamebin.db
  (:require [flamebin.config :refer [config]]
            [flamebin.dto :refer [coerce Profile]]
            [flamebin.util :refer [new-id raise with-locking]]
            [malli.core :as m]
            [migratus.core :as migratus]
            [mount.lite :as mount]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql-helpers]
            [taoensso.timbre :as log])
  (:import java.util.concurrent.locks.ReentrantLock))

;;;; Preparation

(def ^:private db-lock (ReentrantLock.))

(defn- db-options []
  {:dbtype "sqlite"
   :dbname (@config :db :path)})

(defn- migratus-config []
  {:store                :database
   :migration-dir        "migrations/"
   :init-script          "init.sql"
   :init-in-transaction? false
   :db                   (db-options)})

(defn migrate []
  (with-locking db-lock
    (migratus/init (migratus-config))
    (migratus/migrate (migratus-config))))

#_(migrate)

(mount/defstate db
  :start (migrate))

;;;; DB interaction

(defn insert-profile [profile]
  (m/assert Profile profile)
  (let [{:keys [id file_path profile_type sample_count owner upload_ts
                read_password]} profile]
    (log/infof "Inserting profile %s from %s" id owner)
    (with-locking db-lock
      (sql-helpers/insert! (db-options) :profile
                           {:id            id
                            :file_path     file_path
                            :profile_type  (name profile_type)
                            :upload_ts     (str upload_ts)
                            :sample_count  sample_count
                            :read_password read_password
                            :owner         owner}))
    profile))

(defn list-profiles []
  (with-locking db-lock
    (->> (jdbc/execute! (db-options) ["SELECT id, file_path, profile_type, sample_count, owner, upload_ts FROM profile"])
         (mapv #(-> (update-keys % (comp keyword name))
                    (assoc :read_password nil)
                    (coerce Profile))))))

(defn list-public-profiles [n]
  (with-locking db-lock
    (->> (jdbc/execute! (db-options) ["SELECT id, file_path, profile_type, sample_count, owner, upload_ts, read_password FROM profile
WHERE read_password IS NULL ORDER BY upload_ts DESC LIMIT ?" n])
         (mapv #(-> (update-keys % (comp keyword name))
                    (coerce Profile))))))

(defn get-profile [profile-id]
  (with-locking db-lock
    (let [q ["SELECT id, file_path, profile_type, sample_count, owner, upload_ts, read_password FROM profile WHERE id = ?" profile-id]
          row (some-> (jdbc/execute-one! (db-options) q)
                      (update-keys (comp keyword name))
                      (coerce Profile))]
      (or row
          (let [msg (format "Profile with ID '%s' not found." profile-id)]
            (log/error msg)
            (raise 404 msg))))))

(defn clear-db []
  (with-locking db-lock
    (.delete (clojure.java.io/file (@config :db :path)))
    (migrate)))

(comment
  (clear-db)
  (insert-profile {:id (new-id) :file_path "no.txt" :profile_type :alloc :sample_count 100 :owner "me"}  )
  (insert-profile {:id (new-id) :file_path "nilsamples" :profile_type "noexist" :sample_count nil :owner "me"})
  (find-ppf-file "xDRA4dpWFM")
  (let [p (malli.generator/generate Profile)]
    (insert-profile p)
    (= p (first (list-profiles))))
  (get-profile "cBkfhWcMPL")
  )
