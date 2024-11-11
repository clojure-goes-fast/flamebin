(ns flamebin.dto
  (:require [clojure.test.check.generators :as gen]
            [flamebin.config :refer [config]]
            [malli.core :as m]
            [malli.experimental.lite :as mlite]
            [malli.experimental.time.transform]
            [malli.transform :as mt]
            [malli.util :as mu])
  (:import java.time.Instant))

config ;; Don't remove.

(def ^:private int-to-boolean
  {:decoders
   {:boolean #(cond (= % 1) true
                    (= % 0) false
                    :else %)}})

(def global-transformer
  (mt/transformer
   mt/string-transformer
   int-to-boolean
   malli.experimental.time.transform/time-transformer))

(defn coerce [value schema]
  (m/coerce schema value global-transformer))

(defmacro ^:private defschema-and-constructor [schema-name schema-val]
  (assert (= (first schema-val) '->))
  (assert (map? (second schema-val)))
  (let [ks (keys (second schema-val))]
    `(let [sch# ~schema-val]
       (def ~schema-name ~schema-val)
       (defn ~(symbol (str "->" schema-name)) ~(mapv symbol ks)
         (coerce ~(into {} (map #(vector % (symbol %)) ks)) ~schema-name)))))

;;;; Profile

(defschema-and-constructor Profile
  (-> {:id :nano-id
       :file_path [:and {:gen/fmap #(str % ".dpf")} :string]
       :profile_type :keyword
       :sample_count [:maybe nat-int?]
       :owner [:maybe :string]
       :edit_token [:maybe :string]
       :is_public :boolean
       :upload_ts [:and {:gen/gen (gen/fmap Instant/ofEpochSecond
                                            (gen/choose 1500000000 1700000000))}
                   :time/instant]}
      mlite/schema))

#_((requiring-resolve 'malli.generator/sample) Profile)

;;;; DenseProfile

(defschema-and-constructor DenseProfile
  (-> {:stacks [:vector [:tuple [:vector pos-int?] pos-int?]]
       :id->frame [:vector string?]
       :total-samples pos-int?}
      mlite/schema
      (mu/optional-keys [:total-samples])
      mu/closed-schema))

;;;; UploadProfileRequest

(defschema-and-constructor UploadProfileRequest
  (-> {:profile-format [:enum :collapsed :dense-edn]
       :kind [:enum :flamegraph :diffgraph]
       :type [:re #"[\w\.]+"]
       :public? :boolean}
      mlite/schema))

#_(->UploadProfileRequest "collapsed" "diffgraph" "cpu" true)
