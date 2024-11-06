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

(defn coerce [value schema]
  (m/coerce schema value global-transformer))

;;;; Profile

(def Profile
  (mlite/schema
   {:id :nano-id
    :file_path [:and {:gen/fmap #(str % ".dpf")} :string]
    :profile_type :keyword
    :sample_count [:maybe nat-int?]
    :owner [:maybe :string]
    :read_password [:maybe :string]
    :upload_ts [:and {:gen/gen (gen/fmap Instant/ofEpochSecond
                                         (gen/choose 1500000000 1700000000))}
                :time/instant]}))

(defn ->Profile
  [id file_path profile_type sample_count owner read_password upload_ts]
  (-> {:id id, :file_path file_path, :profile_type profile_type,
       :upload_ts upload_ts, :sample_count sample_count, :owner owner
       :read_password read_password}
      (coerce Profile)))

;;;; DenseProfile

(def DenseProfile
  (m/schema [:map {:closed true}
             [:stacks [:vector [:tuple [:vector pos-int?] pos-int?]]]
             [:id->frame [:vector string?]]
             [:total-samples {:optional true} pos-int?]]))

#_((requiring-resolve 'malli.generator/sample) Profile)

;;;; UploadProfileRequest

(def UploadProfileRequest
  (mlite/schema
   {:profile-format [:enum :collapsed :dense-edn]
    :kind [:enum :flamegraph :diffgraph]
    :type [:re #"[\w\.]+"]
    :private? :boolean}))

(defn ->UploadProfileRequest [profile-format kind type private?]
  (-> {:profile-format profile-format, :kind kind, :type type, :private? private?}
      (coerce UploadProfileRequest)))

#_(->UploadProfileRequest "collapsed" "diffgraph" "cpu" true)
