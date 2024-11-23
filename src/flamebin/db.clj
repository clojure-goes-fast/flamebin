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

(defn- migratus-config [connectable]
  {:store                :database
   :migration-dir        "migrations/"
   :init-script          "init.sql"
   :init-in-transaction? false
   :db                   connectable})

(defn- migrate [db]
  (with-locking (:lock db)
    (let [config (migratus-config db)]
      (migratus/init config)
      (migratus/migrate config))))

(mount/defstate db
  :start (doto {:dbtype "sqlite"
                :dbname (@config :db :path)
                :lock (ReentrantLock.)}
           migrate))

#_(mount/start #'db)

;;;; DB interaction

(defn new-unused-id []
  (loop [tries 5]
    (if (<= tries 0)
      (raise 500 "Can't create a proper unused ID.")
      (let [id (new-id)
            res (with-locking (:lock @db)
                  (jdbc/execute-one! @db ["SELECT count(id) AS cnt FROM profile WHERE id = ?" id]))]
        (if (zero? (:cnt res))
          id
          (recur (dec tries)))))))

#_(new-unused-id)

(defn insert-profile [profile]
  (m/assert Profile profile)
  (let [{:keys [id file_path profile_type sample_count owner upload_ts
                edit_token is_public]} profile]
    (log/infof "Inserting profile %s from %s" id owner)
    (with-locking (:lock @db)
      (jdbc/with-transaction [tx @db]
        (sql-helpers/insert! tx :profile
                             {:id            id
                              :file_path     file_path
                              :profile_type  (name profile_type)
                              :upload_ts     (str upload_ts)
                              :sample_count  sample_count
                              :is_public     is_public
                              :edit_token    edit_token
                              :owner         owner})
        (jdbc/execute-one! tx ["UPDATE stats SET val = val + 1 WHERE stat = 'total_uploaded'"])))
    profile))

#_(jdbc/execute! @db ["SELECT * FROM profile"])

(defn- unqualify-keys [m] (update-keys m (comp keyword name)))

(defn list-profiles []
  (with-locking (:lock @db)
    (->> (jdbc/execute! @db ["SELECT id, file_path, profile_type, sample_count, owner, upload_ts, is_public FROM profile"])
         (mapv #(-> (unqualify-keys %)
                    (assoc :edit_token nil)
                    (coerce Profile))))))

(defn list-public-profiles [n]
  (with-locking (:lock @db)
    (->> (jdbc/execute! @db ["SELECT id, file_path, profile_type, sample_count, owner, upload_ts, is_public, edit_token FROM profile
WHERE is_public = 1 ORDER BY upload_ts DESC LIMIT ?" n])
         (mapv #(-> (unqualify-keys %)
                    (coerce Profile))))))

(defn get-profile [profile-id]
  (with-locking (:lock @db)
    (let [q ["SELECT id, file_path, profile_type, sample_count, owner, upload_ts, edit_token, is_public FROM profile WHERE id = ?" profile-id]
          row (some-> (jdbc/execute-one! @db q)
                      unqualify-keys
                      (coerce Profile))]
      (or row
          (let [msg (format "Profile with ID '%s' not found." profile-id)]
            (log/error msg)
            (raise 404 msg))))))

(defn delete-profile [profile-id]
  (with-locking (:lock @db)
    (let [q ["DELETE FROM profile WHERE id = ?" profile-id]
          {::jdbc/keys [update-count]} (jdbc/execute-one! @db q)]
      (when (zero? update-count)
        (raise 404 (format "Profile with ID '%s' not found." profile-id))))))

(defn clear-db []
  (with-locking (:lock @db)
    (.delete (clojure.java.io/file (@config :db :path)))
    (migrate @db)))

(comment
  (clear-db)
  (insert-profile {:id (new-id) :file_path "no.txt" :profile_type :alloc
                   :sample_count 100 :owner "me" :upload_ts (java.time.Instant/now)
                   :is_public true, :edit_token "sadhjflkaj"})
  (insert-profile {:id (new-id) :file_path "nilsamples" :profile_type "noexist" :sample_count nil :owner "me"})
  (find-ppf-file "xDRA4dpWFM")
  (let [p (malli.generator/generate Profile)]
    (insert-profile p)
    (= p (first (list-profiles))))
  (get-profile "cBkfhWcMPL")
  )
