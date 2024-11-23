(ns flamebin.test-utils
  (:require [clojure.java.shell :as sh]
            [flamebin.config :as config]
            [flamebin.rate-limiter :as rl]
            [flamebin.web :as web]
            [mount.lite :as mount]
            [omniconf.core :as cfg]))

(defmacro with-config-redefs [bindings & body]
  (let [bindings (partition 2 bindings)]
    `(let [old-vals# (mapv cfg/get ~(mapv first bindings))]

       (try ~@(for [[k new-v] bindings]
                `(cfg/set ~k ~new-v))
            ~@body
            (finally
              (dorun (map cfg/set ~(mapv first bindings) old-vals#)))))))

(defmacro with-temp-db [& body]
  `(let [f# (java.io.File/createTempFile "test-db" ".db")]
     (try (with-config-redefs [[:db :path] (str f#)]
            ~@body)
          (finally (.delete f#)))))

(defmacro with-temp-storage [& body]
  `(let [dir# (.toFile
               (java.nio.file.Files/createTempDirectory
                "storage" (into-array java.nio.file.attribute.FileAttribute [])))]
     (try (with-config-redefs [[:storage :path] dir#]
            ~@body)
          (finally (sh/sh "rm" "-rf" (str dir#))))))

(defmacro with-temp-no-ratelimits [& body]
  `(binding [mount/*substitutes*
             (merge
              mount/*substitutes*
              {#'rl/global-processed-kbytes-limiter (mount/state :start (constantly true))
               #'rl/global-saved-kbytes-limiter (mount/state :start (constantly true))
               #'rl/per-ip-limiters (mount/state :start (delay (constantly {:processed (constantly true)
                                                                            :saved (constantly true)})))})]
     ~@body))

(defmacro with-temp-mount [& body]
  `(binding [config/*silent* true]
     (try (mount/stop)
          (println "CONFIG" (omniconf.core/get))
          (binding [mount/*substitutes* (merge mount/*substitutes*
                                               {#'web/prom-app {:start (constantly true)}})]
            (println "Started mount states:" (mount/start))
            (println " __ RL STATES:" @rl/global-processed-kbytes-limiter
                      @rl/global-saved-kbytes-limiter
                      @@#'rl/per-ip-limiters )
            )
          ~@body
          (finally
            (mount/stop)
            (binding [mount/*substitutes* {}]
              (mount/start))))))

(defmacro with-temp [whats & body]
  (first
   (reduce (fn [body what]
             (list (cons (symbol (str "flamebin.test-utils/with-temp-" (name what))) body)))
           body (if (= whats :all) [:mount :db :storage :no-ratelimits] whats))))
