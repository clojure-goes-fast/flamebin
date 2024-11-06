(ns flamebin.db-test
  (:require [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as tc.prop]
            [flamebin.db :as db]
            [flamebin.dto :as dto]
            [flamebin.test-utils :refer :all]
            [flamebin.util :refer :all]
            malli.generator
            [taoensso.timbre :as timbre]))

(defmacro with-temp-db [& body]
  `(let [f# (java.io.File/createTempFile "test-db" ".db")]
     (try (with-config-redefs [[:db :path] f#]
            (db/migrate)
            ~@body)
          (finally (.delete f#)))))

(deftest init-test
  (with-temp-db
    (is (= [] (db/list-profiles)))))

(def ^:private inst1 (java.time.Instant/ofEpochSecond 1234567890))

(deftest manual-test
  (with-temp-db
    (db/insert-profile (dto/->Profile "QcXAqvnF3G" "some-path.dpf" "cpu" 12345
                                      nil "alhdslfglksjdfhg" inst1))
    (is (= {:id "QcXAqvnF3G", :file_path "some-path.dpf", :profile_type :cpu,
            :upload_ts inst1, :sample_count 12345, :owner nil, :read_password "alhdslfglksjdfhg"}
           (db/get-profile "QcXAqvnF3G")))
    (is (inst? (:upload_ts (db/get-profile "QcXAqvnF3G"))))

    (db/insert-profile (dto/->Profile "tX8nuc5K8v" "another-path.dpf" "alloc" 54321
                                      "me" nil inst1))
    (is (= {:id "tX8nuc5K8v", :file_path "another-path.dpf", :read_password nil
            :owner "me", :sample_count 54321, :profile_type :alloc, :upload_ts inst1}
           (db/get-profile "tX8nuc5K8v")))))

;;;; Generative testing

(defn- maybe-remove-ts [profile remove-ts?]
  (cond-> profile
    remove-ts? (dissoc :upload_ts)))

(defspec generative-insert-list-test
  (tc.prop/for-all
   ;; Disable shrinking because it does little but slows down testing.
   [inserts (gen/vector (malli.generator/generator dto/Profile) 10 200)]
   (timbre/with-min-level :warn
     (with-temp-db
       (run! db/insert-profile inserts)
       (let [fetched (db/list-profiles)
             no-pwd (fn [l] (mapv #(dissoc % :read_password) l))]
         (and (= (count inserts) (count (db/list-profiles)))
              (= (set (no-pwd inserts)) (set (no-pwd fetched)))))))))
