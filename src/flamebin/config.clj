(ns flamebin.config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            flamebin.util
            malli.core
            malli.experimental.time
            malli.registry
            [mount.lite :as mount]
            [omniconf.core :as cfg]
            [taoensso.timbre :as log]
            [taoensso.timbre.tools.logging]))

(cfg/set-logging-fn (fn [& args] (log/info (str/join " " args))))

;; Limits logic
;; Allowing 100MB saved per day means (* 100 1024 1/24 1/60 1/60) Kbytes per sec.

(defn- number [v] {:type :number, :default v})

(cfg/define
  {:env {:type :keyword
         :one-of [:local :qa :prod]
         :default :local}

   :storage {:nested
             {:kind {:type :keyword
                     :default :disk}
              :path {:type :directory
                     :required true
                     :verifier cfg/verify-file-exists
                     :default #(when (= (cfg/get :env) :local)
                                 (io/file "storage/"))}}}
   :db {:nested
        {:path {:type :string
                :required true
                :default #(when (= (cfg/get :env) :local) "test.db")}}}

   :server {:nested
            {:port {:type :number
                    :required true
                    :default #(when (= (cfg/get :env) :local) 8086)}
             :host {:type :string
                    :required true
                    :description "host where the server is deployed to"
                    :default #(when (= (cfg/get :env) :local) "localhost")}}}

   :limits {:nested
            {:gzip-max-expansion {:type :number
                                  :description "How much gzipped content is allowed to expand."
                                  :default 100}
             :gzip-max-bytes {:type :number
                              :default 500000000 #_=500MB}
             ;; Rate limits
             :global-processed-kbytes
             {:nested {:buffer (number 10240 #_=10MB)
                       :rps    (number 200 #_=200KB/s)}}

             :per-ip-processed-kbytes
             {:nested {:buffer (number 1024 #_=1MB)
                       :rps    (number 50 #_=50KB/s)}}

             :global-saved-kbytes
             {:nested {:buffer (number 1024 #_=10MB)
                       :rps    (number 1.5 #_=1.5KB/s)}}

             :per-ip-saved-kbytes
             {:nested {:buffer (number 512 #_=512KB)
                       :rps    (number 0.5 #_=1.5KB/s)}}}}

   :metrics {:nested
             {:local-port {:type :number
                           :default 9090}}}})

(defn init-config []
  (cfg/populate-from-env)
  (cfg/verify))

(mount/defstate config
  :start (do (init-config) (fn config-getter [& path] (apply cfg/get path))))

;;;; Misc initialization

(malli.registry/set-default-registry!
 (malli.registry/composite-registry
  (malli.core/default-schemas)
  (malli.experimental.time/schemas)
  flamebin.util/nano-id-registry))

(taoensso.timbre.tools.logging/use-timbre)
