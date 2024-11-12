(ns flamebin.storage
  (:require [clojure.java.io :as io]
            [flamebin.config :refer [config]]
            [taoensso.timbre :as log])
  (:import java.io.File))

(defn root-dir []
  (@config :storage :path))

(defn save-file [content filename]
  (log/infof "Saving file %s (%s bytes)" filename
             (if (.isArray (class content)) (count content) "?"))
  (with-open [f (io/output-stream (io/file (root-dir) filename))]
    (io/copy content f)))

(defn get-file ^File [path]
  (io/file (root-dir) path))

(defn delete-file [path]
  (log/infof "Deleted file %s: %s" path (.delete (get-file path))))
