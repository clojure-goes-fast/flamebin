(ns flamebin.test-utils
  (:require [omniconf.core :as cfg]
            [taoensso.timbre :as log]
            [clojure.test.check.generators :as gen]))

(defmacro with-config-redefs [bindings & body]
  (let [bindings (partition 2 bindings)]
    `(let [old-vals# (mapv cfg/get ~(mapv first bindings))]

       (try ~@(for [[k new-v] bindings]
                `(cfg/set ~k ~new-v))
            ~@body
            (finally
              (dorun (map cfg/set ~(mapv first bindings) old-vals#)))))))
