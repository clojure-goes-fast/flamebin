(ns flamebin.db
  (:require [flamebin.config :refer [config]]
            [flamebin.dto :refer [global-transformer Profile]]
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

(defn- now []
  (.toInstant (java.util.Date.)))

#_((requiring-resolve 'malli.generator/sample) Profile)

(defn insert-profile [profile]
  (let [{:keys [id file_path profile_type sample_count owner upload_ts]}
        (m/coerce Profile (update profile :upload_ts #(or % (now))) global-transformer)]
    (log/infof "Inserting profile %s from %s" id owner)
    (with-locking db-lock
      (sql-helpers/insert! (db-options) :profile
                           {:id id
                            :file_path file_path
                            :profile_type (name profile_type)
                            :upload_ts (str upload_ts)
                            :sample_count sample_count
                            :owner owner}))))

(defn find-dpf-file [profile-id]
  (with-locking db-lock
    (let [q ["SELECT file_path FROM profile WHERE id = ?" profile-id]]
      (if-some [row (jdbc/execute-one! (db-options) q)]
        (:profile/file_path row)
        (let [msg (format "Profile with ID '%s' not found." profile-id)]
          (log/error msg)
          (raise 404 msg))))))

(defn list-profiles []
  (with-locking db-lock
    (->> (jdbc/execute! (db-options) ["SELECT id, file_path, profile_type, sample_count, owner, upload_ts FROM profile"])
         (mapv #(m/coerce Profile (update-keys % (comp keyword name)) global-transformer)))))

(defn list-latest-profiles [n]
  (with-locking db-lock
    (->> (jdbc/execute! (db-options) ["SELECT id, file_path, profile_type, sample_count, owner, upload_ts FROM profile ORDER BY upload_ts DESC LIMIT ?" n])
         (mapv #(m/coerce Profile (update-keys % (comp keyword name)) global-transformer)))))

(defn get-profile [profile-id]
  (with-locking db-lock
    (let [q ["SELECT id, file_path, profile_type, sample_count, owner, upload_ts FROM profile WHERE id = ?" profile-id]]
      (if-some [row (update-keys (jdbc/execute-one! (db-options) q) (comp keyword name))]
        (m/coerce Profile row global-transformer)
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
