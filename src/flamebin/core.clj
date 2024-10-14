(ns flamebin.core
  (:require [flamebin.db :as db]
            [flamebin.dto :as dto]
            [flamebin.processing :as proc]
            [flamebin.rate-limiter :as rl]
            [flamebin.render :as render]
            [flamebin.storage :as storage]
            [flamebin.util :refer [raise]]))

(defn- ensure-saved-limits [ip length-kb]
  (when-not (and ((rl/per-ip-saved-kbytes-limiter ip) length-kb)
                 (@rl/global-saved-kbytes-limiter length-kb))
    (raise 429 "Upload saved bytes limit reached.")))

(defn save-profile [stream ip {:keys [id format event] :as _upload-request}]
  (let [profile (case format
                  :collapsed (proc/collapsed-stacks-stream->dense-profile stream)
                  :dense-edn (proc/dense-edn-stream->dense-profile stream))
        dpf-array (proc/freeze profile)
        dpf-kb (quot (alength ^bytes dpf-array) 1024)
        filename (format "%s.dpf" id)]
    (ensure-saved-limits ip dpf-kb)
    (storage/save-file dpf-array filename)
    (db/insert-profile
     ;; TODO: replace IP with proper owner at some point
     (dto/->Profile id filename event (:total-samples profile) ip))))

(defn read-profile [profile-id]
  (let [f (db/find-dpf-file profile-id)]
    (proc/read-compressed-profile (storage/get-file f))))

(defn render-profile [profile-id]
  (render/render-html-flamegraph (read-profile profile-id) {}))

(defn list-profiles []
  (db/list-latest-profiles 20))

(comment
  (save-profile "test/res/huge-profile.txt" (flamebin.util/new-id) "me")

  (time+ (read-profile "jgnXp3Vfcm")))
