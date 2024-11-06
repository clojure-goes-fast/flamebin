(ns flamebin.core
  (:require [flamebin.db :as db]
            [flamebin.dto :as dto]
            [flamebin.processing :as proc]
            [flamebin.rate-limiter :as rl]
            [flamebin.render :as render]
            [flamebin.storage :as storage]
            [flamebin.util :refer [raise secret-token new-id]])
  (:import java.time.Instant))

(defn- ensure-saved-limits [ip length-kb]
  (when-not (and ((rl/per-ip-saved-kbytes-limiter ip) length-kb)
                 (@rl/global-saved-kbytes-limiter length-kb))
    (raise 429 "Upload saved bytes limit reached.")))

(defn save-profile [stream ip {:keys [profile-format type private?]
                               :as _upload-request}]
  (let [profile (case profile-format
                  :collapsed (proc/collapsed-stacks-stream->dense-profile stream)
                  :dense-edn (proc/dense-edn-stream->dense-profile stream))
        dpf-array (proc/freeze profile)
        dpf-kb (quot (alength ^bytes dpf-array) 1024)
        id (new-id)
        filename (format "%s.dpf" id)]
    (ensure-saved-limits ip dpf-kb)
    (storage/save-file dpf-array filename)
    (db/insert-profile
     ;; TODO: replace IP with proper owner at some point
     (dto/->Profile id filename type (:total-samples profile) ip
                    (when private? (secret-token)) (Instant/now)))))

(defn render-profile [profile-id provided-read-password]
  (let [{:keys [read_password file_path] :as profile} (db/get-profile profile-id)]
    ;; Authorization
    (when read_password
      (when (nil? provided-read-password)
        (raise 403 "Required read-password to access this resource."))
      (when (not= read_password provided-read-password)
        (raise 403 "Incorrect read-password.")))

    (-> (storage/get-file file_path)
        proc/read-compressed-profile
        (render/render-html-flamegraph {}))))

(defn list-public-profiles []
  (db/list-public-profiles 20))

(comment
  (save-profile
   (clojure.java.io/input-stream (clojure.java.io/file "test/res/normal.txt"))
   "me" (dto/->UploadProfileRequest :collapsed :flamegraph "alloc" true)))
