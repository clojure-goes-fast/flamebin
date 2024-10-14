(ns flamebin.dto
  (:require [clojure.test.check.generators :as gen]
            [flamebin.config :refer [config]]
            [malli.core :as m]
            [malli.experimental.lite :as mlite]
            [malli.experimental.time.transform]
            [malli.transform :as mt])
  (:import java.time.Instant))

config ;; Don't remove.

(def global-transformer
  (mt/transformer
   mt/string-transformer
   malli.experimental.time.transform/time-transformer))

;;;; Profile

(def Profile
  (mlite/schema
   {:id :nano-id
    :file_path [:and {:gen/fmap #(str % ".dpf")} :string]
    :profile_type :keyword
    :upload_ts [:and {:gen/gen (gen/fmap Instant/ofEpochSecond
                                         (gen/choose 1500000000 1700000000))}
                :time/instant]
    :sample_count [:maybe nat-int?]
    :owner [:maybe string?]}))

(defn ->Profile
  ([id file_path profile_type sample_count owner upload_ts]
   (let [obj {:id id, :file_path file_path, :profile_type profile_type,
              :upload_ts upload_ts, :sample_count sample_count, :owner owner}]
     (m/coerce Profile obj global-transformer)))
  ([id file_path profile_type sample_count owner]
   (->Profile id file_path profile_type sample_count owner (Instant/now))))

;;;; DenseProfile

(def DenseProfile
  (m/schema [:map {:closed true}
             [:stacks [:vector [:tuple [:vector pos-int?] pos-int?]]]
             [:id->frame [:vector string?]]
             [:total-samples {:optional true} pos-int?]]))

#_((requiring-resolve 'malli.generator/sample) DenseProfile)

;;;; UploadProfileRequest

(def UploadProfileRequest
  (mlite/schema
   {:id :nano-id
    :format [:enum :collapsed :dense-edn]
    :kind [:enum :flamegraph :diffgraph]
    :event [:re #"[\w\.]+"]}))

#_(malli.core/coerce UploadProfileRequest {:id "h8JsY4Sg7y" :format "collapsed", :event "cpu", :kind :diffgraph} global-transformer)
