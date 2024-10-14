(ns flamebin.util.streams
  (:require [flamebin.config :refer [config]])
  (:import (java.util.zip GZIPInputStream)
           (org.apache.commons.io.input BoundedInputStream CountingInputStream)))

(defn safe-gzip-input-stream [in gzipped-content-length]
  ;; Allow gzipped content to only expand so much.
  (let [limit (min (* gzipped-content-length
                      (@config :limits :gzip-max-expansion))
                   (@config :limits :gzip-max-bytes))]
    (-> (GZIPInputStream. in)
        (BoundedInputStream. limit)
        CountingInputStream.)))

(defn get-byte-count [^CountingInputStream stream]
  (.getByteCount stream))
