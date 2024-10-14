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

(deftest manual-test
  (with-temp-db
    (db/insert-profile {:id "QcXAqvnF3G" :file_path "some-path.dpf"
                        :profile_type "cpu" :sample_count 12345 :owner nil})
    (is (= {:id "QcXAqvnF3G" :file_path "some-path.dpf" :owner nil
            :sample_count 12345, :profile_type :cpu}
           (dissoc (db/get-profile "QcXAqvnF3G") :upload_ts)))
    (is (inst? (:upload_ts (db/get-profile "QcXAqvnF3G"))))

    (db/insert-profile {:id "tX8nuc5K8v" :file_path "another-path.dpf"
                        :profile_type "alloc" :sample_count 54321 :owner "me"})
    (is (= {:id "tX8nuc5K8v", :file_path "another-path.dpf",
            :owner "me", :sample_count 54321, :profile_type :alloc}
           (dissoc (db/get-profile "tX8nuc5K8v") :upload_ts)))))

;;;; Generative testing

(defn- maybe-remove-ts [profile remove-ts?]
  (cond-> profile
    remove-ts? (dissoc :upload_ts)))

(defspec generative-insert-list-test
  (tc.prop/for-all
   ;; Disable shrinking because it does little but slows down testing.
   [inserts (gen/vector (malli.generator/generator dto/Profile) 10 200)
    remove-ts? gen/boolean]
   (timbre/with-min-level :warn
     (with-temp-db
       (let [inserts (mapv #(maybe-remove-ts % remove-ts?) inserts)]
         (run! db/insert-profile inserts)
         (let [fetched (mapv #(maybe-remove-ts % remove-ts?) (db/list-profiles))]
           (and (= (count inserts) (count (db/list-profiles)))
                (= (set inserts) (set fetched)))))))))
