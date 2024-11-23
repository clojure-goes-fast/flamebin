(ns flamebin.db-test
  (:require [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as tc.prop]
            [flamebin.db :as db]
            [flamebin.dto :as dto]
            [flamebin.test-utils :refer :all]
            [flamebin.util :refer :all]
            [mount.lite :as mount]
            [flamebin.test-utils :refer :all]
            malli.generator
            [taoensso.timbre :as timbre]))

(defmacro with-temp-db-and-state [& body]
  `(try (with-temp-db
          (doto #'db/db mount/stop mount/start)
          ~@body)
        (finally (doto #'db/db mount/stop mount/start))))

(deftest init-test
  (with-temp-db-and-state
    (is (= [] (db/list-profiles)))))

(def ^:private inst1 (java.time.Instant/ofEpochSecond 1234567890))

(deftest manual-test
  (with-temp-db-and-state
    (db/insert-profile (dto/->Profile "QcXAqv" "some-path.dpf" "cpu" 12345
                                      nil "alhdslfglksjdfhg" true inst1))
    (is (= {:id "QcXAqv", :file_path "some-path.dpf", :profile_type :cpu,
            :upload_ts inst1, :sample_count 12345, :owner nil, :is_public true
            :edit_token "alhdslfglksjdfhg"}
           (db/get-profile "QcXAqv")))
    (is (inst? (:upload_ts (db/get-profile "QcXAqv"))))

    (db/insert-profile (dto/->Profile "tX8nuc" "another-path.dpf" "alloc" 54321
                                      "me" nil false inst1))
    (is (= {:id "tX8nuc", :file_path "another-path.dpf", :edit_token nil
            :owner "me", :sample_count 54321, :profile_type :alloc, :is_public false
            :upload_ts inst1}
           (db/get-profile "tX8nuc")))))

;;;; Generative testing

(defspec generative-insert-list-test
  (tc.prop/for-all
   ;; Disable shrinking because it does little but slows down testing.
   [inserts (gen/vector (malli.generator/generator dto/Profile) 10 200)]
   (timbre/with-min-level :warn
     (with-temp-db-and-state
       (run! db/insert-profile inserts)
       (let [fetched (db/list-profiles)
             no-pwd (fn [l] (mapv #(dissoc % :edit_token) l))]
         (and (= (count inserts) (count (db/list-profiles)))
              (= (set (no-pwd inserts)) (set (no-pwd fetched)))))))))
