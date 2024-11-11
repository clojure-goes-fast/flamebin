(ns flamebin.processing
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [flamebin.dto :refer [DenseProfile]]
            [flamebin.util :refer [raise]]
            [malli.core :as m]
            [taoensso.nippy :as nippy])
  (:import clj_async_profiler.Helpers
           (java.io BufferedReader InputStream PushbackReader)
           (java.util HashMap Map$Entry)))

;; Collapsed stacks:
;;    a;b;c 10
;;    a;b;d;e 20
;; Intermediate profile:
;;   HashMap{"a;b;c" 10, "a;b;d;e" 20}
;; Dense profile:
;;   {:id->frame ["a" "b" "c" "d" "e"]
;;    :stacks [[[0 1 2 3] 10] [[2 4 5] 20]]}
;;   The first value in each stack is the number of frames from the previous
;;   stack that should be reused.

;; Heuristic - 1MB gzipped collapsed stacks = 9MB intermediate profile in-mem.

(defn- collapsed-stacks-stream->intermediate-profile [input-stream]
  (with-open [^BufferedReader s (io/reader input-stream)]
    (let [demunge-cache (HashMap.)
          acc (HashMap.)]
      (loop []
        (when-let [line (.readLine s)]
          (let [sep (.lastIndexOf line " ")
                _ (when (= sep -1)
                    (raise 422 "Bad collapsed stacks."))
                stack (.substring line 0 sep)
                samples (Long/parseLong (.substring line (inc sep)))
                xstack (Helpers/demungeJavaClojureFrames stack demunge-cache)
                value (.getOrDefault acc xstack 0)]
            (.put acc xstack (+ value samples))
            (recur))))
      acc)))

(defn- split-by-semicolon-and-transform-to-indices
  [^String s, ^HashMap frame->id-map]
  (let [l (java.util.ArrayList.)]
    (loop [last-pos 0]
      (let [last-pos (unchecked-int last-pos)
            pos (.indexOf s ";" last-pos)
            frame (if (= pos -1)
                    (.substring s last-pos)
                    (.substring s last-pos pos))
            frame-idx (or (.get frame->id-map frame)
                          (let [cnt (.size frame->id-map)]
                            (.put frame->id-map frame cnt)
                            cnt))]
        (.add l frame-idx)
        (if (= pos -1)
          (vec l)
          (recur (inc pos)))))))

(defn- count-same [frames-a frames-b]
  (loop [i 0]
    (let [frame-a (nth frames-a i nil)
          frame-b (nth frames-b i nil)]
      (if (and frame-a frame-b (= frame-a frame-b))
        (recur (inc i))
        i))))

;; Heuristic - 1Mb gzipped collapsed stacks = 500Kb dense profile in memory
;;             = 18Kb nippy-frozen.

(defn- intermediate-profile->dense-profile
  "Transform intermediate profile into dense profile structure which sorts
  stacks and reuses stack prefixes and occupies less space when serialized."
  [^HashMap intermediate-profile]
  (let [frame->id-map (HashMap.)
        last-stack (object-array [nil])
        total-samples (long-array [0])
        acc (java.util.ArrayList. (.size intermediate-profile))
        ;; Quite unconventional way to iterate over the map, but we want to sort
        ;; by the key without creating intermediate sequences.
        _ (-> (.entrySet intermediate-profile)
              .stream
              (.sorted (Map$Entry/comparingByKey))
              (.forEach
               (fn [^Map$Entry entry]
                 (let [stack (split-by-semicolon-and-transform-to-indices
                              (.getKey entry) frame->id-map)
                       value (.getValue entry)
                       same (count-same stack (aget last-stack 0))
                       dense-stack (into [same] (drop same stack))]
                   (.add acc [dense-stack value])
                   (aset last-stack 0 stack)
                   (aset total-samples 0 (+ (aget total-samples 0) ^long value))))))
        id->frame-arr (object-array (.size frame->id-map))]
    (run! (fn [[k v]] (aset id->frame-arr v k)) frame->id-map)
    {:stacks (vec acc)
     :id->frame (vec id->frame-arr)
     :total-samples (aget total-samples 0)}))

(def ^:private nippy-compressor nippy/zstd-compressor)

(defn freeze [object read-token]
  (nippy/freeze object {:compressor nippy-compressor
                        :password (when read-token [:salted read-token])}))

(defn collapsed-stacks-stream->dense-profile [input-stream]
  (-> input-stream
      collapsed-stacks-stream->intermediate-profile
      intermediate-profile->dense-profile))

(defn dense-edn-stream->dense-profile [^InputStream input-stream]
  (with-open [rdr (PushbackReader. (io/reader input-stream))]
    (let [profile (select-keys (edn/read rdr) [:stacks :id->frame :total-samples])]
      (m/assert DenseProfile profile)
      ;; Calculate total samples if not provided.
      (update profile :total-samples
              #(or % (transduce (map second) + 0 (:stacks profile)))))))

(defn read-compressed-profile [source-file read-token]
  (try (nippy/thaw-from-file source-file {:password (when read-token
                                                      [:salted read-token])})
       (catch clojure.lang.ExceptionInfo ex
         (if (str/includes? (ex-message ex) "decryption")
           (raise 403 "Failed to decrypt flamegraph, incorrect read-token.")
           (throw ex)))))

(comment
  (defn file-as-gzip-input-stream [file]
    (java.util.zip.GZIPInputStream.
     (java.io.ByteArrayInputStream.
      (let [baos (java.io.ByteArrayOutputStream.)]
        (with-open [s (java.util.zip.GZIPOutputStream. baos)]
          (io/copy (io/file file) s))
        (.toByteArray baos)))))

  (nippy/thaw
   (freeze
    (intermediate-profile->dense-profile
     (collapsed-stacks-stream->intermediate-profile
      (file-as-gzip-input-stream "test/res/normal.txt")))
    "key1")
   {:password  [:salted "key2"]})

  (with-open [w (io/writer (io/file "test/res/huge.edn"))]
    (binding [*out* w]
      (pr (intermediate-profile->dense-profile
           (collapsed-stacks-stream->intermediate-profile
            (file-as-gzip-input-stream "test/res/huge.txt")))))))
