(ns flamebin.infra.metrics
  (:require [mount.lite :as mount])
  (:import (io.micrometer.core.instrument DistributionSummary Metrics Timer)
           (io.micrometer.prometheusmetrics PrometheusConfig PrometheusMeterRegistry)))

(mount/defstate registry
  :start (let [reg (PrometheusMeterRegistry. PrometheusConfig/DEFAULT)]
           (.add (Metrics/globalRegistry) reg)
           reg))

(defn scrape []
  (.scrape @registry))

(defn- tag-map->array [m]
  (into-array String (mapcat (fn [[k v]] [(name k) (str v)]) m)))

(defn- dist-summary [name tags]
  (-> (DistributionSummary/builder name)
      (.tags (tag-map->array tags))
      (.publishPercentiles (double-array [0.0 0.5 0.75 0.9 0.95 0.99 1.0]))
      (.register @registry)))

(defn mark [name tags value]
  (.record (dist-summary name tags) (double value)))

(defn- timer [name tags]
  (-> (Timer/builder name)
      (.tags (tag-map->array tags))
      (.publishPercentiles (double-array [0.0 0.5 0.75 0.9 0.95 0.99 1.0]))
      (.register @registry)))

(defn clock [name tags value]
  (.record (timer name tags) value java.util.concurrent.TimeUnit/NANOSECONDS))

(defn stopwatch []
  (let [start (System/nanoTime)]
    (fn stopwatch*
      ([] (- (System/nanoTime) start))
      ([name tags]
       (clock name tags (stopwatch*))))))

#_((stopwatch) "stopwatch" {})
