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

(defn save-profile [stream ip {:keys [profile-format type public?]
                               :as _upload-request}]
  (let [profile (case profile-format
                  :collapsed (proc/collapsed-stacks-stream->dense-profile stream)
                  :dense-edn (proc/dense-edn-stream->dense-profile stream))
        edit-token (secret-token)
        read-token (when-not public? (secret-token))
        dpf-array (proc/freeze profile read-token)
        dpf-kb (quot (alength ^bytes dpf-array) 1024)
        id (db/new-unused-id)
        filename (format "%s.dpf" id)]
    (ensure-saved-limits ip dpf-kb)
    (storage/save-file dpf-array filename)
    ;; TODO: replace IP with proper owner at some point
    (-> (dto/->Profile id filename type (:total-samples profile) ip
                       edit-token public? (Instant/now))
        db/insert-profile
        ;; Attach read-token to the response here — it's not in the scheme
        ;; because we don't store it in the DB.
        (assoc :read-token read-token))))

(defn render-profile [profile-id read-token]
  (let [{:keys [is_public file_path] :as profile} (db/get-profile profile-id)]
    ;; Authorization
    (when-not is_public
      (when (nil? read-token)
        (raise 403 "Required read-token to access this resource.")))

    (-> (storage/get-file file_path)
        (proc/read-compressed-profile read-token)
        (render/render-html-flamegraph {}))))

(defn list-public-profiles []
  (db/list-public-profiles 20))

(comment
  (save-profile
   (clojure.java.io/input-stream (clojure.java.io/file "test/res/normal.txt"))
   "me" (dto/->UploadProfileRequest :collapsed :flamegraph "alloc" false)))
