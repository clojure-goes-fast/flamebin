(ns flamebin.dto
  (:require [clojure.test.check.generators :as gen]
            [flamebin.util.malli :as fmalli]
            [malli.experimental.lite :as mlite]
            [malli.util :as mu])
  (:import (java.time Instant)))

(def coerce fmalli/coerce)
(reset-meta! #'coerce (meta #'fmalli/coerce))

(defmacro ^:private defschema-and-constructor [schema-name schema-val]
  (assert (= (first schema-val) '->))
  (assert (= (first (second schema-val)) 'array-map))
  (let [ks (mapv first (partition 2 (rest (second schema-val))))]
    `(do (def ~schema-name ~schema-val)
         (defn ~(symbol (str "->" schema-name)) ~(mapv symbol ks)
           (coerce ~(into {} (map #(vector % (symbol %)) ks)) ~schema-name)))))

;;;; Profile

(defschema-and-constructor Profile
  (-> (array-map
       :id :nano-id
       :file_path [:and {:gen/fmap #(str % ".dpf")} :string]
       :profile_type :keyword
       :sample_count [:maybe nat-int?]
       :owner [:maybe :string]
       :edit_token [:maybe :string]
       :is_public :boolean
       :config [:maybe :string]
       :upload_ts [:and {:gen/gen (gen/fmap Instant/ofEpochSecond
                                            (gen/choose 1500000000 1700000000))}
                   :time/instant])
      mlite/schema))

#_((requiring-resolve 'malli.generator/sample) Profile)

;;;; DenseProfile

(defschema-and-constructor DenseProfile
  (-> (array-map
       :stacks [:vector [:tuple [:vector nat-int?] pos-int?]]
       :id->frame [:vector string?]
       :total-samples pos-int?)
      mlite/schema
      (mu/optional-keys [:total-samples])
      mu/closed-schema))

;;;; UploadProfileRequest

(defschema-and-constructor UploadProfileRequestParams
  (-> (array-map
       :format [:enum :collapsed :dense-edn]
       :kind [:schema {:default :flamegraph} [:enum :flamegraph :diffgraph]]
       :type [:re #"[\w\.]+"]
       :public [:schema {:default false} :boolean])
      mlite/schema))

#_(->UploadProfileRequestParams "collapsed" nil "cpu" true)
